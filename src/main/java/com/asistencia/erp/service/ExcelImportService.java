package com.asistencia.erp.service;

import com.asistencia.erp.entity.*;
import com.asistencia.erp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelImportService {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final AttendanceRepository attendanceRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ImportBatchLogRepository importBatchLogRepository;

    // Pattern para detectar emoticonos/emojis en cadenas de texto
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u26FF\\u2700-\\u27BF]");

    // Encabezados estandarizados 100% idénticos para Plantilla y Exportación
    private static final String[] HEADERS_OFICIALES = {
            "Nombre Acudiente",
            "Teléfono Acudiente",
            "Nombre Deportista",
            "Fecha Nacimiento (AAAA-MM-DD)",
            "Sede",
            "Nivel/Grupo",
            "Saldo Abono ($)",
            "Debe ($)"
    };

    @Transactional
    public Map<String, Object> importarExcel(MultipartFile file) {
        int totalProcesados = 0;
        int deportistasCreados = 0;
        int padresCreados = 0;
        int[] sedesCreadasHolder = new int[]{0};
        int nivelesCreados = 0;
        List<String> errores = new ArrayList<>();

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo Excel no puede estar vacío");
        }

        String batchId = UUID.randomUUID().toString();
        ImportBatchLog batchLog = new ImportBatchLog();
        batchLog.setBatchId(batchId);
        batchLog.setTimestamp(LocalDateTime.now());

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            int numHojas = workbook.getNumberOfSheets();
            if (numHojas == 0) {
                throw new IllegalArgumentException("El archivo Excel no contiene hojas de datos");
            }

            for (int sIdx = 0; sIdx < numHojas; sIdx++) {
                Sheet sheet = workbook.getSheetAt(sIdx);
                if (sheet == null) continue;

                String sheetName = sheet.getSheetName().trim().toLowerCase();
                boolean esHojaInactiva = sheetName.contains("inactivo") || sheetName.contains("retirado");

                String estadoParentImport = esHojaInactiva ? "INACTIVO" : "ACTIVO";
                Student.StudentStatus estadoStudentImport = esHojaInactiva ? Student.StudentStatus.RETIRADO : Student.StudentStatus.ACTIVO;

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                Map<String, Integer> colIndex = parseHeaders(headerRow);

                int lastRow = sheet.getLastRowNum();
                for (int r = 1; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || isRowEmpty(row)) continue;

                    totalProcesados++;
                    try {
                        String nombreAcudienteRaw = getCellValue(row, colIndex.getOrDefault("acudiente", 0));
                        String telefonoAcudiente = getCellValue(row, colIndex.getOrDefault("telefono", 1));
                        String nombreDeportistaRaw = getCellValue(row, colIndex.getOrDefault("deportista", 2));
                        String fechaNacStr = getCellValue(row, colIndex.getOrDefault("fechanacimiento", 3));
                        String nombreSedeRaw = getCellValue(row, colIndex.getOrDefault("sede", 4));
                        String nombreNivelRaw = getCellValue(row, colIndex.getOrDefault("nivel", 5));
                        String saldoAbonoRaw = getCellValue(row, colIndex.getOrDefault("saldo", 6));

                        // Si viene en formato de columna combinada legado
                        if ((nombreSedeRaw.isBlank() || nombreNivelRaw.isBlank()) && colIndex.containsKey("sedecombinada")) {
                            String combinada = getCellValue(row, colIndex.get("sedecombinada"));
                            if (!combinada.isBlank()) {
                                if (combinada.contains("(") && combinada.contains(")")) {
                                    int idxP1 = combinada.indexOf("(");
                                    int idxP2 = combinada.indexOf(")");
                                    if (nombreSedeRaw.isBlank()) {
                                        nombreSedeRaw = combinada.substring(0, idxP1).trim();
                                    }
                                    if (nombreNivelRaw.isBlank() && idxP2 > idxP1) {
                                        nombreNivelRaw = combinada.substring(idxP1 + 1, idxP2).trim();
                                    }
                                } else if (nombreSedeRaw.isBlank()) {
                                    nombreSedeRaw = combinada.trim();
                                }
                            }
                        }

                        if (nombreDeportistaRaw.isBlank() && nombreAcudienteRaw.isBlank()) {
                            continue;
                        }

                        if (nombreDeportistaRaw.isBlank()) {
                            errores.add("Hoja [" + sheet.getSheetName() + "] Fila " + (r + 1) + ": Nombre del deportista es obligatorio.");
                            continue;
                        }

                        String nombreDeportista = toTitleCase(nombreDeportistaRaw);
                        String telLimpio = telefonoAcudiente.trim();

                        // 1. Sede Inteligente con coincidencia insensible a tildes/acentos
                        Sede sede = null;
                        if (!nombreSedeRaw.isBlank()) {
                            sede = buscarOSustituirSede(nombreSedeRaw, batchLog, sedesCreadasHolder);
                        }

                        // 2. Emoji & Grupo con coincidencia insensible a tildes/acentos
                        String nivelRaw = nombreNivelRaw.trim();
                        String emojiInExcel = extraerEmoji(nivelRaw);
                        String nivelNombreClean = toTitleCase(extraerTextoSinEmoji(nivelRaw));

                        String nivelFormatted = nivelNombreClean;
                        if (sede != null && !nivelNombreClean.isBlank()) {
                            if (sede.getGrupos() == null) {
                                sede.setGrupos(new ArrayList<>());
                            }

                            final String searchNombreNorm = normalizarTexto(nivelNombreClean);
                            GrupoSede grupoMatch = sede.getGrupos().stream()
                                    .filter(g -> g != null && g.getNombre() != null &&
                                            (normalizarTexto(g.getNombre()).equals(searchNombreNorm) ||
                                             normalizarTexto(extraerTextoSinEmoji(g.getNombre())).equals(searchNombreNorm)))
                                    .findFirst().orElse(null);

                            if (grupoMatch == null) {
                                String colorDefault = "#059669";
                                GrupoSede nuevoGrupo = new GrupoSede(nivelNombreClean, emojiInExcel, colorDefault);
                                sede.getGrupos().add(nuevoGrupo);
                                sedeRepository.save(sede);
                                grupoMatch = nuevoGrupo;
                                nivelesCreados++;
                                log.info("Grupo/Nivel '{}' creado en sede '{}'", nivelNombreClean, sede.getNombre());
                            } else {
                                if (!emojiInExcel.isBlank() && (grupoMatch.getEmoji() == null || grupoMatch.getEmoji().isBlank())) {
                                    grupoMatch.setEmoji(emojiInExcel);
                                    sedeRepository.save(sede);
                                }
                            }

                            String emojiFinal = (grupoMatch.getEmoji() != null && !grupoMatch.getEmoji().isBlank())
                                    ? grupoMatch.getEmoji().trim()
                                    : emojiInExcel;

                            nivelFormatted = (!emojiFinal.isBlank() ? emojiFinal + " " : "") + grupoMatch.getNombre();
                        }

                        // 3. Saldo Abono: Si está vacío, por defecto 0
                        BigDecimal saldoAbono = BigDecimal.ZERO;
                        if (!saldoAbonoRaw.isBlank()) {
                            try {
                                String numClean = saldoAbonoRaw.replaceAll("[^0-9.-]", "");
                                if (!numClean.isBlank()) {
                                    saldoAbono = new BigDecimal(numClean);
                                }
                            } catch (Exception ignored) {
                                saldoAbono = BigDecimal.ZERO;
                            }
                        }

                        // 4. Parent (Acudiente) con coincidencia insensible a tildes/acentos
                        Parent parent = null;
                        if (!telLimpio.isBlank()) {
                            parent = parentRepository.findByTelefono(telLimpio);
                        }
                        if (parent == null && !nombreAcudienteRaw.isBlank() && !nombreAcudienteRaw.matches("^\\d+$")) {
                            final String searchAcudienteNorm = normalizarTexto(nombreAcudienteRaw);
                            parent = parentRepository.findAll().stream()
                                    .filter(p -> p.getNombreCompleto() != null && normalizarTexto(p.getNombreCompleto()).equals(searchAcudienteNorm))
                                    .findFirst().orElse(null);
                        }

                        String nombreAcudiente = toTitleCase(nombreAcudienteRaw);
                        if (nombreAcudiente.isBlank() || nombreAcudiente.matches("^\\d+$")) {
                            if (parent != null && parent.getNombreCompleto() != null && !parent.getNombreCompleto().matches("^\\d+$")) {
                                nombreAcudiente = parent.getNombreCompleto();
                            } else {
                                nombreAcudiente = !telLimpio.isBlank() ? "Acudiente " + telLimpio : "Acudiente " + nombreDeportista;
                            }
                        }

                        if (parent != null) {
                            // Capturar Snapshot del padre ANTES de modificarlo para revertir 100%
                            final Long targetParentId = parent.getId();
                            boolean yaGuardadoSnap = batchLog.getParentSnapshots().stream()
                                    .anyMatch(s -> s.getParentId().equals(targetParentId));

                            if (!yaGuardadoSnap) {
                                ParentSnapshot snap = new ParentSnapshot(
                                        parent.getId(),
                                        parent.getNombreCompleto(),
                                        parent.getTelefono(),
                                        parent.getEstado(),
                                        parent.getSaldoAbono()
                                );
                                batchLog.getParentSnapshots().add(snap);
                            }

                            // Regla de preservación: NUNCA sobrescribir un nombre real por acudientes genéricos o números
                            boolean updated = false;
                            if (parent.getNombreCompleto() == null || parent.getNombreCompleto().isBlank() || parent.getNombreCompleto().startsWith("Acudiente ")) {
                                if (!nombreAcudiente.isBlank() && !nombreAcudiente.matches("^\\d+$") && !nombreAcudiente.startsWith("Acudiente ")) {
                                    parent.setNombreCompleto(nombreAcudiente);
                                    updated = true;
                                }
                            }
                            if ((parent.getTelefono() == null || parent.getTelefono().isBlank()) && !telLimpio.isBlank()) {
                                parent.setTelefono(telLimpio);
                                updated = true;
                            }
                            if (updated) {
                                parentRepository.save(parent);
                            }
                        } else {
                            parent = new Parent();
                            parent.setNombreCompleto(nombreAcudiente);
                            parent.setTelefono(telLimpio.isBlank() ? "300" + System.currentTimeMillis() % 10000000 : telLimpio);
                            parent.setEstado(estadoParentImport);
                            parent.setSaldoAbono(saldoAbono);
                            parent.setSecretToken(UUID.randomUUID().toString());
                            parent = parentRepository.save(parent);
                            padresCreados++;
                            batchLog.getCreatedParentIds().add(parent.getId());
                        }

                        // 5. Student (Deportista) con coincidencia insensible a tildes/acentos
                        final String searchDeportistaNorm = normalizarTexto(nombreDeportista);
                        Student student = null;
                        if (parent.getStudents() != null) {
                            student = parent.getStudents().stream()
                                    .filter(s -> s.getNombreCompleto() != null && normalizarTexto(s.getNombreCompleto()).equals(searchDeportistaNorm))
                                    .findFirst().orElse(null);
                        }

                        LocalDate fechaNac = parseFecha(fechaNacStr, row, colIndex.getOrDefault("fechanacimiento", 3));
                        Integer edadCalculada = calcularEdad(fechaNac);

                        if (student == null) {
                            student = new Student();
                            student.setParent(parent);
                            student.setNombreCompleto(nombreDeportista);
                            student.setFechaNacimiento(fechaNac);
                            student.setEdad(edadCalculada);
                            student.setEstado(estadoStudentImport);
                            student.setMatriculas(new ArrayList<>());
                            student = studentRepository.save(student);
                            deportistasCreados++;
                            batchLog.getCreatedStudentIds().add(student.getId());
                        }

                        // 6. Enrollment (Matrícula)
                        if (sede != null && !nivelFormatted.isBlank()) {
                            final Sede targetSede = sede;
                            final String targetNivelNorm = normalizarTexto(nivelFormatted);

                            boolean yaMatriculado = student.getMatriculas() != null && student.getMatriculas().stream().anyMatch(m ->
                                    m.getSede() != null && m.getSede().getId().equals(targetSede.getId()) &&
                                            m.getNivel() != null && normalizarTexto(m.getNivel()).equals(targetNivelNorm)
                            );

                            if (!yaMatriculado) {
                                Enrollment enrollment = new Enrollment();
                                enrollment.setStudent(student);
                                enrollment.setSede(sede);
                                enrollment.setNivel(nivelFormatted);
                                enrollment = enrollmentRepository.save(enrollment);

                                if (student.getMatriculas() == null) {
                                    student.setMatriculas(new ArrayList<>());
                                }
                                student.getMatriculas().add(enrollment);
                                studentRepository.save(student);

                                batchLog.getCreatedEnrollmentIds().add(enrollment.getId());
                            }
                        }

                    } catch (Exception ex) {
                        log.error("Error procesando Hoja {} Fila {}: {}", sheet.getSheetName(), (r + 1), ex.getMessage(), ex);
                        errores.add("Hoja [" + sheet.getSheetName() + "] Fila " + (r + 1) + ": " + ex.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error al leer el archivo Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al procesar el archivo Excel: " + e.getMessage());
        }

        // Persistir el registro del lote en base de datos
        importBatchLogRepository.save(batchLog);

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("batchId", batchId);
        resultado.put("totalProcesados", totalProcesados);
        resultado.put("deportistasCreados", deportistasCreados);
        resultado.put("padresCreados", padresCreados);
        resultado.put("sedesCreadas", sedesCreadasHolder[0]);
        resultado.put("nivelesCreados", nivelesCreados);
        resultado.put("errores", errores);

        return resultado;
    }

    /**
     * Deshace una importación revirtiendo 100% de las matrículas, estudiantes, padres, sedes y ediciones realizadas a acudientes preexistentes.
     */
    @Transactional
    public Map<String, Object> deshacerImportacion(String batchId) {
        ImportBatchLog batchLog = importBatchLogRepository.findById(batchId).orElse(null);
        if (batchLog == null || Boolean.TRUE.equals(batchLog.getReverted())) {
            throw new IllegalArgumentException("El lote ya fue revertido o no se encuentra en el sistema.");
        }

        int[] matriculasEliminadas = new int[]{0};
        int[] deportistasEliminados = new int[]{0};
        int[] padresEliminados = new int[]{0};
        int[] sedesEliminadas = new int[]{0};
        int[] padresRestaurados = new int[]{0};

        // 1. Revertir ediciones realizadas a acudientes preexistentes (Snapshots)
        if (batchLog.getParentSnapshots() != null && !batchLog.getParentSnapshots().isEmpty()) {
            for (ParentSnapshot snap : batchLog.getParentSnapshots()) {
                parentRepository.findById(snap.getParentId()).ifPresent(p -> {
                    p.setNombreCompleto(snap.getOriginalNombreCompleto());
                    p.setTelefono(snap.getOriginalTelefono());
                    p.setEstado(snap.getOriginalEstado());
                    p.setSaldoAbono(snap.getOriginalSaldoAbono());
                    parentRepository.save(p);
                    padresRestaurados[0]++;
                });
            }
        }

        // 2. Eliminar matrículas creadas específicamente en esta importación
        if (batchLog.getCreatedEnrollmentIds() != null && !batchLog.getCreatedEnrollmentIds().isEmpty()) {
            for (Long enrollmentId : batchLog.getCreatedEnrollmentIds()) {
                enrollmentRepository.findById(enrollmentId).ifPresent(e -> {
                    Student s = e.getStudent();
                    if (s != null && s.getMatriculas() != null) {
                        s.getMatriculas().remove(e);
                        studentRepository.save(s);
                    }
                    enrollmentRepository.delete(e);
                    enrollmentRepository.flush();
                    matriculasEliminadas[0]++;
                });
            }
        }

        // 3. Eliminar estudiantes creados en la importación
        for (Long studentId : batchLog.getCreatedStudentIds()) {
            studentRepository.findById(studentId).ifPresent(student -> {
                Parent p = student.getParent();
                if (p != null && p.getStudents() != null) {
                    p.getStudents().remove(student);
                    parentRepository.save(p);
                }
                studentRepository.delete(student);
                studentRepository.flush();
                deportistasEliminados[0]++;
            });
        }

        // 4. Eliminar padres creados en la importación
        for (Long parentId : batchLog.getCreatedParentIds()) {
            parentRepository.findById(parentId).ifPresent(parent -> {
                if (parent.getStudents() == null || parent.getStudents().isEmpty()) {
                    parentRepository.delete(parent);
                    parentRepository.flush();
                    padresEliminados[0]++;
                }
            });
        }

        // 5. Eliminar sedes creadas en la importación
        for (Long sedeId : batchLog.getCreatedSedeIds()) {
            sedeRepository.findById(sedeId).ifPresent(sede -> {
                try {
                    List<Enrollment> enrollments = enrollmentRepository.findBySedeId(sedeId);
                    for (Enrollment e : enrollments) {
                        Student student = e.getStudent();
                        if (student != null && student.getMatriculas() != null) {
                            student.getMatriculas().remove(e);
                            studentRepository.save(student);
                        }
                    }
                    enrollmentRepository.deleteAll(enrollments);
                    enrollmentRepository.flush();

                    sedeRepository.delete(sede);
                    sedeRepository.flush();
                    sedesEliminadas[0]++;
                } catch (Exception ex) {
                    log.warn("Sede ID {} en uso, se conserva al deshacer importación: {}", sedeId, ex.getMessage());
                }
            });
        }

        batchLog.setReverted(true);
        importBatchLogRepository.save(batchLog);

        Map<String, Object> res = new HashMap<>();
        res.put("mensaje", "Importación deshecha correctamente. Se revirtieron todos los cambios y ediciones.");
        res.put("matriculasEliminadas", matriculasEliminadas[0]);
        res.put("deportistasEliminados", deportistasEliminados[0]);
        res.put("padresEliminados", padresEliminados[0]);
        res.put("padresRestaurados", padresRestaurados[0]);
        res.put("sedesEliminadas", sedesEliminadas[0]);

        return res;
    }

    /**
     * Exporta todos los clientes (Acudientes y Deportistas) a un archivo Excel .xlsx con formato nativo de Fecha (Date) y Moneda (Currency COP sin decimales).
     * Aplica exactamente el mismo formato a "Clientes Activos" y "Clientes Inactivos".
     */
    public byte[] exportarClientesExcel() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle dateStyle = crearEstiloFecha(workbook);
            CellStyle currencyStyle = crearEstiloMoneda(workbook);

            List<Parent> todosLosPadres = parentRepository.findAll();

            List<Parent> activos = todosLosPadres.stream()
                    .filter(p -> p.getEstado() == null || !p.getEstado().equalsIgnoreCase("INACTIVO"))
                    .toList();

            List<Parent> inactivos = todosLosPadres.stream()
                    .filter(p -> p.getEstado() != null && p.getEstado().equalsIgnoreCase("INACTIVO"))
                    .toList();

            // Hoja 1: Clientes Activos
            Sheet sheetActivos = workbook.createSheet("Clientes Activos");
            construirHojaClientes(sheetActivos, activos, headerStyle, dateStyle, currencyStyle);

            // Hoja 2: Clientes Inactivos
            Sheet sheetInactivos = workbook.createSheet("Clientes Inactivos");
            construirHojaClientes(sheetInactivos, inactivos, headerStyle, dateStyle, currencyStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error al exportar clientes a Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al exportar clientes a Excel: " + e.getMessage());
        }
    }

    /**
     * Genera la Plantilla oficial .xlsx con 2 Hojas y formato nativo de Fecha (Date) y Moneda (Currency COP sin decimales).
     * Aplica exactamente el mismo formato a "Clientes Activos" y "Clientes Inactivos".
     */
    public byte[] generarPlantillaExcel() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle dateStyle = crearEstiloFecha(workbook);
            CellStyle currencyStyle = crearEstiloMoneda(workbook);

            // Hoja 1: Clientes Activos
            Sheet sheetActivos = workbook.createSheet("Clientes Activos");
            crearEncabezados(sheetActivos, headerStyle);

            Object[][] datosActivos = {
                    {"Juan Gómez", "3001234567", "Mateo Gómez", LocalDate.of(2015, 5, 20), "Sede Principal", "🌱 Iniciación", 0.0, 0.0},
                    {"María Rodríguez", "3109876543", "Sofía Rodríguez", LocalDate.of(2013, 11, 10), "Sede Norte", "Avanzado", 50000.0, 0.0}
            };
            escribirFilasPlantilla(sheetActivos, datosActivos, dateStyle, currencyStyle);
            autoajustarColumnas(sheetActivos);

            // Hoja 2: Clientes Inactivos
            Sheet sheetInactivos = workbook.createSheet("Clientes Inactivos");
            crearEncabezados(sheetInactivos, headerStyle);

            Object[][] datosInactivos = {
                    {"Pedro Ramírez", "3150009988", "Camilo Ramírez", LocalDate.of(2012, 8, 15), "Colina", "🦉 Grandes", 0.0, 0.0}
            };
            escribirFilasPlantilla(sheetInactivos, datosInactivos, dateStyle, currencyStyle);
            autoajustarColumnas(sheetInactivos);

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error generando plantilla Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar la plantilla de Excel: " + e.getMessage());
        }
    }

    private void escribirFilasPlantilla(Sheet sheet, Object[][] datos, CellStyle dateStyle, CellStyle currencyStyle) {
        for (int r = 0; r < datos.length; r++) {
            Row row = sheet.createRow(r + 1);
            row.createCell(0).setCellValue((String) datos[r][0]);
            row.createCell(1).setCellValue((String) datos[r][1]);
            row.createCell(2).setCellValue((String) datos[r][2]);

            Cell cFecha = row.createCell(3);
            if (datos[r][3] instanceof LocalDate ld) {
                cFecha.setCellValue(java.sql.Date.valueOf(ld));
            } else {
                cFecha.setCellValue((String) datos[r][3]);
            }
            cFecha.setCellStyle(dateStyle);

            row.createCell(4).setCellValue((String) datos[r][4]);
            row.createCell(5).setCellValue((String) datos[r][5]);

            Cell cSaldo = row.createCell(6);
            cSaldo.setCellValue((Double) datos[r][6]);
            cSaldo.setCellStyle(currencyStyle);

            Cell cDebe = row.createCell(7);
            cDebe.setCellValue((Double) datos[r][7]);
            cDebe.setCellStyle(currencyStyle);
        }
    }

    private CellStyle crearEstiloEncabezado(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(font);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        return headerStyle;
    }

    private CellStyle crearEstiloFecha(Workbook workbook) {
        CellStyle dateStyle = workbook.createCellStyle();
        DataFormat df = workbook.createDataFormat();
        // Formato nativo de celda tipo Date (yyyy-mm-dd)
        dateStyle.setDataFormat(df.getFormat("yyyy-mm-dd"));
        return dateStyle;
    }

    private CellStyle crearEstiloMoneda(Workbook workbook) {
        CellStyle currencyStyle = workbook.createCellStyle();
        DataFormat df = workbook.createDataFormat();
        // Formato nativo de celda tipo Currency en COP sin decimales ($#,##0)
        currencyStyle.setDataFormat(df.getFormat("$#,##0"));
        return currencyStyle;
    }

    private void crearEncabezados(Sheet sheet, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < HEADERS_OFICIALES.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(HEADERS_OFICIALES[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void construirHojaClientes(Sheet sheet, List<Parent> padres, CellStyle headerStyle, CellStyle dateStyle, CellStyle currencyStyle) {
        crearEncabezados(sheet, headerStyle);
        int r = 1;

        for (Parent parent : padres) {
            String nombreParent = parent.getNombreCompleto() != null ? parent.getNombreCompleto() : "";
            String telParent = parent.getTelefono() != null ? parent.getTelefono() : "";
            BigDecimal saldo = parent.getSaldoAbono() != null ? parent.getSaldoAbono() : BigDecimal.ZERO;

            List<Attendance> deudas = attendanceRepository.findUnpaidByParentIdOrderByFechaDesc(parent.getId());
            BigDecimal debeTotal = BigDecimal.ZERO;
            if (deudas != null) {
                for (Attendance d : deudas) {
                    if (d.getPrecioCobrado() != null) {
                        debeTotal = debeTotal.add(d.getPrecioCobrado());
                    }
                }
            }

            if (parent.getStudents() != null && !parent.getStudents().isEmpty()) {
                for (Student s : parent.getStudents()) {
                    String nombreDep = s.getNombreCompleto() != null ? s.getNombreCompleto() : "";
                    LocalDate fechaNac = s.getFechaNacimiento();

                    if (s.getMatriculas() != null && !s.getMatriculas().isEmpty()) {
                        for (Enrollment m : s.getMatriculas()) {
                            String nomSede = (m.getSede() != null) ? m.getSede().getNombre() : "";
                            String nomNivel = (m.getNivel() != null) ? m.getNivel() : "";

                            Row row = sheet.createRow(r++);
                            row.createCell(0).setCellValue(nombreParent);
                            row.createCell(1).setCellValue(telParent);
                            row.createCell(2).setCellValue(nombreDep);

                            Cell cFecha = row.createCell(3);
                            if (fechaNac != null) {
                                cFecha.setCellValue(java.sql.Date.valueOf(fechaNac));
                            } else {
                                cFecha.setCellValue("");
                            }
                            cFecha.setCellStyle(dateStyle);

                            row.createCell(4).setCellValue(nomSede);
                            row.createCell(5).setCellValue(nomNivel);

                            Cell cSaldo = row.createCell(6);
                            cSaldo.setCellValue(saldo.doubleValue());
                            cSaldo.setCellStyle(currencyStyle);

                            Cell cDebe = row.createCell(7);
                            cDebe.setCellValue(debeTotal.doubleValue());
                            cDebe.setCellStyle(currencyStyle);
                        }
                    } else {
                        Row row = sheet.createRow(r++);
                        row.createCell(0).setCellValue(nombreParent);
                        row.createCell(1).setCellValue(telParent);
                        row.createCell(2).setCellValue(nombreDep);

                        Cell cFecha = row.createCell(3);
                        if (fechaNac != null) {
                            cFecha.setCellValue(java.sql.Date.valueOf(fechaNac));
                        } else {
                            cFecha.setCellValue("");
                        }
                        cFecha.setCellStyle(dateStyle);

                        row.createCell(4).setCellValue("");
                        row.createCell(5).setCellValue("");

                        Cell cSaldo = row.createCell(6);
                        cSaldo.setCellValue(saldo.doubleValue());
                        cSaldo.setCellStyle(currencyStyle);

                        Cell cDebe = row.createCell(7);
                        cDebe.setCellValue(debeTotal.doubleValue());
                        cDebe.setCellStyle(currencyStyle);
                    }
                }
            } else {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nombreParent);
                row.createCell(1).setCellValue(telParent);
                row.createCell(2).setCellValue("");

                Cell cFecha = row.createCell(3);
                cFecha.setCellValue("");
                cFecha.setCellStyle(dateStyle);

                row.createCell(4).setCellValue("");
                row.createCell(5).setCellValue("");

                Cell cSaldo = row.createCell(6);
                cSaldo.setCellValue(saldo.doubleValue());
                cSaldo.setCellStyle(currencyStyle);

                Cell cDebe = row.createCell(7);
                cDebe.setCellValue(debeTotal.doubleValue());
                cDebe.setCellStyle(currencyStyle);
            }
        }

        autoajustarColumnas(sheet);
    }

    private void autoajustarColumnas(Sheet sheet) {
        for (int i = 0; i < HEADERS_OFICIALES.length; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i) + 1200, 3800));
        }
    }

    private Map<String, Integer> parseHeaders(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String val = cell.getStringCellValue().toLowerCase().trim();

                if (val.contains("estado")) {
                    map.put("estado", i);
                } else if (val.contains("sedes / grupos") || val.contains("sedes/grupos") || val.contains("matriculados")) {
                    map.put("sedecombinada", i);
                } else if (val.contains("teléfono") || val.contains("telefono") || val.contains("celular") || val.contains("tel")) {
                    map.put("telefono", i);
                } else if (val.contains("acudiente") || val.contains("padre") || val.contains("papá") || val.contains("tutor")) {
                    map.put("acudiente", i);
                } else if (val.contains("deportista") || val.contains("estudiante") || val.contains("niño") || val.contains("alumno")) {
                    map.put("deportista", i);
                } else if (val.contains("nacimiento") || val.contains("fecha")) {
                    map.put("fechanacimiento", i);
                } else if (val.contains("sede")) {
                    map.put("sede", i);
                } else if (val.contains("nivel") || val.contains("grupo") || val.contains("categoría")) {
                    map.put("nivel", i);
                } else if (val.contains("saldo") || val.contains("abono")) {
                    map.put("saldo", i);
                } else if (val.contains("debe") || val.contains("deuda")) {
                    map.put("debe", i);
                }
            }
        }
        return map;
    }

    private Sede buscarOSustituirSede(String rawSedeText, ImportBatchLog batchLog, int[] sedesCreadasHolder) {
        if (rawSedeText == null || rawSedeText.isBlank()) return null;

        String cleanText = rawSedeText.trim();
        // Si contiene paréntesis (ej: "Sede 10 (Sede Principal Iniciación)"), extraer contenido de parentesis o antes
        if (cleanText.contains("(") && cleanText.contains(")")) {
            int p1 = cleanText.indexOf("(");
            int p2 = cleanText.indexOf(")");
            String inside = cleanText.substring(p1 + 1, p2).trim();
            String outside = cleanText.substring(0, p1).trim();

            Sede matchInside = buscarSedeExistente(inside);
            if (matchInside != null) return matchInside;

            Sede matchOutside = buscarSedeExistente(outside);
            if (matchOutside != null) return matchOutside;

            cleanText = inside.isBlank() ? outside : inside;
        }

        Sede match = buscarSedeExistente(cleanText);
        if (match != null) {
            return match;
        }

        String norm = cleanText.replaceAll("(?i)^sede\\s*\\d*\\s*", "").trim();
        if (!norm.isBlank()) {
            Sede matchNorm = buscarSedeExistente(norm);
            if (matchNorm != null) return matchNorm;
        }

        String finalNombre = toTitleCase(cleanText.toLowerCase().startsWith("sede ") ? cleanText : "Sede " + cleanText);
        Sede nuevaSede = new Sede();
        nuevaSede.setNombre(finalNombre);
        nuevaSede.setActiva(true);
        nuevaSede.setGrupos(new ArrayList<>());
        nuevaSede = sedeRepository.save(nuevaSede);
        sedesCreadasHolder[0]++;
        batchLog.getCreatedSedeIds().add(nuevaSede.getId());
        return nuevaSede;
    }

    private Sede buscarSedeExistente(String text) {
        if (text == null || text.isBlank()) return null;
        String targetNorm = normalizarTexto(text);
        List<Sede> todas = sedeRepository.findAll();
        for (Sede s : todas) {
            if (s.getNombre() == null) continue;
            String sNorm = normalizarTexto(s.getNombre());

            if (sNorm.equals(targetNorm) || targetNorm.contains(sNorm) || sNorm.contains(targetNorm)) {
                return s;
            }

            String sClean = sNorm.replaceAll("(?i)^sede\\s*", "").trim();
            String tClean = targetNorm.replaceAll("(?i)^sede\\s*", "").trim();
            if (!sClean.isBlank() && !tClean.isBlank() && (sClean.equals(tClean) || tClean.contains(sClean) || sClean.contains(tClean))) {
                return s;
            }
        }
        return null;
    }

    private String normalizarTexto(String texto) {
        if (texto == null) return "";
        String normalized = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").trim().toLowerCase();
    }

    private String getCellValue(Row row, int col) {
        if (col < 0) return "";
        Cell cell = row.getCell(col);
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    Date d = cell.getDateCellValue();
                    if (d != null) {
                        LocalDate ld = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        return ld.toString();
                    }
                }
                double num = cell.getNumericCellValue();
                if (num == (long) num) {
                    return String.valueOf((long) num);
                } else {
                    return String.valueOf(num);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf((long) cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    private LocalDate parseFecha(String dateStr, Row row, int colIndex) {
        if (dateStr == null || dateStr.isBlank()) return null;
        Cell cell = row.getCell(colIndex);
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            Date d = cell.getDateCellValue();
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        String cleaned = dateStr.trim();
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("M/d/yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("d/M/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd")
        );

        for (DateTimeFormatter fmt : formatters) {
            try {
                return LocalDate.parse(cleaned, fmt);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer calcularEdad(LocalDate fechaNacimiento) {
        if (fechaNacimiento == null) return null;
        return Period.between(fechaNacimiento, LocalDate.now()).getYears();
    }

    private String extraerEmoji(String texto) {
        if (texto == null || texto.isBlank()) return "";
        Matcher matcher = EMOJI_PATTERN.matcher(texto);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    private String extraerTextoSinEmoji(String texto) {
        if (texto == null) return "";
        return EMOJI_PATTERN.matcher(texto).replaceAll("").replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
    }

    private String toTitleCase(String text) {
        if (text == null || text.isBlank()) return "";
        String[] words = text.trim().toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1));
                }
            }
        }
        return sb.toString();
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !getCellValue(row, c).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
