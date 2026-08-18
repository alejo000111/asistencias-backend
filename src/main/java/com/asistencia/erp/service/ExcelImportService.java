package com.asistencia.erp.service;

import com.asistencia.erp.entity.*;
import com.asistencia.erp.repository.*;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
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
    private final FinancialLogRepository financialLogRepository;
    private final AppUserRepository appUserRepository;
    private final ClubConfigRepository clubConfigRepository;
    private final PlanMensualidadRepository planMensualidadRepository;
    private final com.asistencia.erp.service.billing.MonthlyBillingService monthlyBillingService;
    private final FinancialService financialService;
    private final StudentComplementoRepository studentComplementoRepository;

    // Pattern para detectar emoticonos/emojis en cadenas de texto
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u26FF\\u2700-\\u27BF]");

    /**
     * Columnas de la hoja de trabajo (plantilla y exportación), calculadas según lo que el club
     * tiene activado — nunca se muestra una columna de un concepto que el club no cobra. Matrícula
     * y Seguro solo aparecen como columnas cuando son OPCIONALES (el admin decide caso por caso);
     * si son obligatorios se cobran automático a todos sin necesidad de columna, y si el club los
     * tiene desactivados no aparecen en absoluto.
     */
    private static class ColumnasPlantilla {
        List<String> headers = new ArrayList<>();
        int idxAcudiente, idxTelefono, idxDeportista, idxFecha, idxSede, idxGrupo, idxPlan, idxSedePrincipal;
        int idxMatriculaActiva = -1, idxMatriculaPagada = -1;
        int idxSeguroActiva = -1, idxSeguroPagada = -1;
        int idxComplementos = -1;
        int idxAbono, idxUltima;
        boolean cobraMatricula, matriculaObligatoria;
        boolean cobraSeguro, seguroObligatoria;
        ClubConfig.EsquemaCobro esquema;
    }

    /** Sobrecarga de compatibilidad: sin columna de Complementos (usada por la plantilla de importación, que no tiene datos existentes que mostrar). */
    private ColumnasPlantilla construirColumnas(ClubConfig config, ClubConfig.EsquemaCobro esquema) {
        return construirColumnas(config, esquema, false);
    }

    private ColumnasPlantilla construirColumnas(ClubConfig config, ClubConfig.EsquemaCobro esquema, boolean incluirComplementos) {
        ColumnasPlantilla c = new ColumnasPlantilla();
        c.esquema = esquema;
        c.idxAcudiente = agregar(c.headers, "Nombre Acudiente");
        c.idxTelefono = agregar(c.headers, "Teléfono Acudiente");
        c.idxDeportista = agregar(c.headers, "Nombre Deportista");
        c.idxFecha = agregar(c.headers, "Fecha Nacimiento (AAAA-MM-DD)");
        c.idxSede = agregar(c.headers, "Sede");
        c.idxGrupo = agregar(c.headers, "Nivel/Grupo");
        c.idxPlan = agregar(c.headers, "Plan");
        c.idxSedePrincipal = agregar(c.headers, "Sede Principal (Sí/No)");

        c.cobraMatricula = config != null && Boolean.TRUE.equals(config.getCobraMatricula());
        c.matriculaObligatoria = c.cobraMatricula && Boolean.TRUE.equals(config.getMatriculaObligatoria());
        if (c.cobraMatricula && !c.matriculaObligatoria) {
            c.idxMatriculaActiva = agregar(c.headers, "Matrícula (Sí/No)");
            c.idxMatriculaPagada = agregar(c.headers, "Matrícula Pagada (Sí/No)");
        }

        c.cobraSeguro = config != null && Boolean.TRUE.equals(config.getCobraSeguro());
        c.seguroObligatoria = c.cobraSeguro && Boolean.TRUE.equals(config.getSeguroObligatorio());
        if (c.cobraSeguro && !c.seguroObligatoria) {
            c.idxSeguroActiva = agregar(c.headers, "Seguro Deportivo (Sí/No)");
            c.idxSeguroPagada = agregar(c.headers, "Seguro Pagado (Sí/No)");
        }

        if (incluirComplementos) {
            c.idxComplementos = agregar(c.headers, "Complementos Activos (nombre: precio, estado)");
        }

        c.idxAbono = agregar(c.headers, "Abono ($)");
        String ultimaColumna;
        if (esquema == ClubConfig.EsquemaCobro.PAQUETE) {
            ultimaColumna = "Clases Disponibles";
        } else if (esquema == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            ultimaColumna = "Mensualidad Pendiente (Sí/No)";
        } else {
            ultimaColumna = "Debe ($)";
        }
        c.idxUltima = agregar(c.headers, ultimaColumna);
        return c;
    }

    private int agregar(List<String> headers, String nombre) {
        headers.add(nombre);
        return headers.size() - 1;
    }

    /** Resuelve el esquema de cobro del club actualmente autenticado (fallback: POR_CLASE). */
    private ClubConfig.EsquemaCobro resolverEsquemaActual() {
        Long clubId = SecurityUtils.getClubId();
        if (clubId == null) return ClubConfig.EsquemaCobro.POR_CLASE;
        return clubConfigRepository.findByAdminId(clubId)
                .map(ClubConfig::getEsquemaCobro)
                .orElse(ClubConfig.EsquemaCobro.POR_CLASE);
    }

    /** Interpreta texto libre (Sí/No, Yes/No, X, 1/0, etc.) como booleano, insensible a mayúsculas/acentos. */
    private boolean esValorAfirmativo(String raw) {
        if (raw == null) return false;
        String norm = normalizarTexto(raw.trim());
        return norm.equals("si") || norm.equals("s") || norm.equals("x")
                || norm.equals("yes") || norm.equals("y") || norm.equals("true")
                || norm.equals("1") || norm.equals("pendiente") || norm.equals("debe");
    }

    /** Valida que una celda Sí/No solo contenga eso — vacío se acepta como "No". Devuelve el mensaje de error (y lo agrega a la lista) o null si es válida. */
    private String validarSiNo(String raw, String nombreColumna, String ubicacion, List<String> errores) {
        if (raw == null || raw.isBlank()) return null;
        String norm = normalizarTexto(raw);
        boolean esSi = norm.equals("si") || norm.equals("s");
        boolean esNo = norm.equals("no") || norm.equals("n");
        if (!esSi && !esNo) {
            String msg = ubicacion + ": la columna '" + nombreColumna + "' debe ser 'Sí' o 'No' (se escribió '" + raw + "').";
            errores.add(msg);
            return msg;
        }
        return null;
    }

    /** Lanzada cuando la validación previa encuentra problemas: no se persiste nada, se listan todos los errores para corregir y resubir. */
    public static class ImportValidationException extends RuntimeException {
        private final List<String> errores;
        public ImportValidationException(List<String> errores) {
            super("La importación no se realizó: " + errores.size() + " error(es) encontrados.");
            this.errores = errores;
        }
        public List<String> getErrores() { return errores; }
    }

    /** Una fila ya validada y resuelta contra la base de datos, lista para persistirse en la fase 2. */
    private static class FilaImport {
        String sheetName;
        int filaExcel;
        boolean esHojaInactiva;
        String nombreAcudienteRaw;
        String telLimpio;
        String nombreDeportista;
        LocalDate fechaNac;
        Sede sede;
        String nivelFormatted;
        PlanMensualidad plan;
        boolean marcadoSi;
        boolean sedePrincipalExplicita;
        boolean marcarPrincipal;
        BigDecimal saldoAbono;
        String ultimaColRaw;
        String deportistaKey;
        boolean matriculaActiva;
        boolean matriculaPagada;
        boolean seguroActivo;
        boolean seguroPagado;
    }

    @Transactional
    public Map<String, Object> importarExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo Excel no puede estar vacío");
        }

        final Long importClubId = SecurityUtils.getClubId();
        // El esquema de cobro del club determina cómo se interpreta la última columna
        // de la plantilla ("Debe" / "Mensualidad Pendiente" / "Clases Disponibles").
        final ClubConfig.EsquemaCobro esquemaImport = resolverEsquemaActual();
        final ClubConfig configImport = clubConfigRepository.findByAdminId(importClubId).orElse(null);
        final ColumnasPlantilla columnas = construirColumnas(configImport, esquemaImport);

        List<String> errores = new ArrayList<>();
        List<FilaImport> filas = new ArrayList<>();

        // ─────────────────────────── FASE 1: validar TODO, sin tocar la base de datos ───────────────────────────
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            int numHojas = workbook.getNumberOfSheets();
            if (numHojas == 0) {
                throw new IllegalArgumentException("El archivo Excel no contiene hojas de datos");
            }

            for (int sIdx = 0; sIdx < numHojas; sIdx++) {
                Sheet sheet = workbook.getSheetAt(sIdx);
                if (sheet == null) continue;

                String sheetNameLower = sheet.getSheetName().trim().toLowerCase();

                // Hojas de solo referencia de la propia plantilla (Inicio, Sedes y Grupos, Planes) —
                // NUNCA se procesan como datos a importar, sin importar cómo se llame la hoja.
                if (sheetNameLower.equals("inicio") || sheetNameLower.equals("sedes y grupos") || sheetNameLower.equals("planes")) {
                    continue;
                }

                boolean esHojaInactiva = sheetNameLower.contains("inactivo") || sheetNameLower.contains("retirado");

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                Map<String, Integer> colIndex = parseHeaders(headerRow);

                // Salvaguarda adicional: si la hoja no tiene una columna reconocible de "Nombre
                // Deportista", no es una hoja de importación válida — se ignora en vez de generar
                // errores sin sentido leyendo texto de instrucciones como si fueran filas de datos.
                if (!colIndex.containsKey("deportista")) {
                    continue;
                }

                // "Deportista de la fila de arriba" dentro de esta hoja — se reinicia en cada hoja.
                String contextoNombreDeportista = null;
                String contextoAcudienteRaw = "";

                int lastRow = sheet.getLastRowNum();
                for (int r = 1; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || isRowEmpty(row)) continue;

                    String ubicacion = "Hoja [" + sheet.getSheetName() + "] Fila " + (r + 1);

                    String nombreAcudienteRaw = getCellValue(row, colIndex.getOrDefault("acudiente", 0));
                    String telefonoAcudiente = getCellValue(row, colIndex.getOrDefault("telefono", 1));
                    String nombreDeportistaRaw = getCellValue(row, colIndex.getOrDefault("deportista", 2));
                    String fechaNacStr = getCellValue(row, colIndex.getOrDefault("fechanacimiento", 3));
                    String nombreSedeRaw = getCellValue(row, colIndex.getOrDefault("sede", 4));
                    String nombreNivelRaw = getCellValue(row, colIndex.getOrDefault("nivel", 5));
                    String nombrePlanRaw = getCellValue(row, colIndex.getOrDefault("plan", 6));
                    String sedePrincipalRaw = getCellValue(row, colIndex.getOrDefault("sedeprincipal", 7));
                    String matriculaActivaRaw = getCellValue(row, colIndex.getOrDefault("matriculaactiva", -1));
                    String matriculaPagadaRaw = getCellValue(row, colIndex.getOrDefault("matriculapagada", -1));
                    String seguroActivoRaw = getCellValue(row, colIndex.getOrDefault("seguroactivo", -1));
                    String seguroPagadoRaw = getCellValue(row, colIndex.getOrDefault("seguropagado", -1));
                    String saldoAbonoRaw = getCellValue(row, colIndex.getOrDefault("saldo", 8));
                    // La columna 10 (índice 9) se interpreta según el esquema de cobro del club:
                    // POR_CLASE lee "debe" ($), MENSUALIDAD lee "mensualidadPendiente" (Sí/No),
                    // PAQUETE lee "clasesDisponibles" (entero, admite negativos). Todas caen por
                    // defecto en la columna 9 si el encabezado no matchea exactamente.
                    String ultimaColRaw = getCellValue(row, colIndex.getOrDefault(
                            esquemaImport == ClubConfig.EsquemaCobro.PAQUETE ? "clasesDisponibles"
                                    : esquemaImport == ClubConfig.EsquemaCobro.MENSUALIDAD ? "mensualidadPendiente"
                                    : "debe",
                            9));

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

                    // Nombre del deportista: si esta fila lo deja en blanco, es una SEDE ADICIONAL
                    // del deportista de la fila justo de arriba en esta misma hoja — hereda su nombre
                    // y acudiente, así no hay que volver a escribirlos. Esta fila solo necesita Sede,
                    // Nivel/Grupo y Plan (los propios de la sede que se está agregando).
                    String nombreDeportista;
                    if (!nombreDeportistaRaw.isBlank()) {
                        nombreDeportista = toTitleCase(nombreDeportistaRaw);
                        contextoNombreDeportista = nombreDeportista;
                        contextoAcudienteRaw = nombreAcudienteRaw;
                    } else if (contextoNombreDeportista != null) {
                        nombreDeportista = contextoNombreDeportista;
                        if (nombreAcudienteRaw.isBlank()) {
                            nombreAcudienteRaw = contextoAcudienteRaw;
                        }
                    } else {
                        errores.add(ubicacion + ": falta el nombre del deportista. Si esta fila es una sede "
                                + "adicional de alguien de arriba, debe haber una fila de ese mismo deportista justo antes en esta hoja.");
                        continue;
                    }

                    String telLimpio = telefonoAcudiente.trim();

                    // Sede: debe existir YA en el club — la plantilla ya trae las sedes reales,
                    // así que si no coincide con ninguna se rechaza en vez de crearla.
                    if (nombreSedeRaw.isBlank()) {
                        errores.add(ubicacion + ": la columna 'Sede' es obligatoria (deportista '" + nombreDeportista + "').");
                        continue;
                    }
                    Sede sede = buscarSedePorTexto(nombreSedeRaw);
                    if (sede == null) {
                        String disponibles = sedeRepository.findByClubId(importClubId).stream()
                                .map(Sede::getNombre).filter(Objects::nonNull).sorted()
                                .reduce((a, b) -> a + ", " + b).orElse("(tu club no tiene sedes creadas)");
                        errores.add(ubicacion + ": la sede '" + nombreSedeRaw + "' no existe en tu club. Sedes disponibles: "
                                + disponibles + ". Créala primero en el ERP (Gestión de Sedes) si hace falta.");
                        continue;
                    }

                    // Grupo/Nivel: debe existir YA en esa sede (coincidencia insensible a emoji/tildes/mayúsculas).
                    if (nombreNivelRaw.isBlank()) {
                        errores.add(ubicacion + ": la columna 'Nivel/Grupo' es obligatoria (deportista '" + nombreDeportista + "').");
                        continue;
                    }
                    GrupoSede grupoMatch = buscarGrupoEnSede(sede, nombreNivelRaw);
                    if (grupoMatch == null) {
                        String disponibles = (sede.getGrupos() == null || sede.getGrupos().isEmpty())
                                ? "(la sede '" + sede.getNombre() + "' no tiene grupos creados)"
                                : sede.getGrupos().stream().map(GrupoSede::getNombre).filter(Objects::nonNull).sorted()
                                    .reduce((a, b) -> a + ", " + b).orElse("(ninguno)");
                        errores.add(ubicacion + ": el grupo/nivel '" + nombreNivelRaw + "' no existe en la sede '" + sede.getNombre()
                                + "'. Grupos disponibles ahí: " + disponibles + ".");
                        continue;
                    }
                    String emojiFinal = (grupoMatch.getEmoji() != null && !grupoMatch.getEmoji().isBlank()) ? grupoMatch.getEmoji().trim() : "";
                    String nivelFormatted = (!emojiFinal.isBlank() ? emojiFinal + " " : "") + grupoMatch.getNombre();

                    // Plan: solo aplica en la sede PRINCIPAL del deportista — ahí es donde se le cobra.
                    // Todavía no sabemos en esta fila si es la principal (eso se decide más abajo,
                    // comparando todas las filas de este deportista), así que aquí solo se valida que,
                    // SI se escribió algo, ese plan exista en esta sede. La obligatoriedad (principal)
                    // o prohibición (sede adicional) se revisa después de resolver la sede principal.
                    PlanMensualidad planMatch = null;
                    if (!nombrePlanRaw.isBlank()) {
                        planMatch = buscarPlanEnSede(sede, nombrePlanRaw);
                        if (planMatch == null) {
                            String disponibles = planMensualidadRepository.findBySedeId(sede.getId()).stream()
                                    .map(PlanMensualidad::getNombre).filter(Objects::nonNull).sorted()
                                    .reduce((a, b) -> a + ", " + b).orElse("(la sede '" + sede.getNombre() + "' no tiene planes creados)");
                            errores.add(ubicacion + ": el plan '" + nombrePlanRaw + "' no existe en la sede '" + sede.getNombre()
                                    + "'. Planes disponibles ahí: " + disponibles + ".");
                            continue;
                        }
                    }

                    // Sede Principal: solo acepta Sí/No — cualquier otra cosa se rechaza en vez de adivinar.
                    if (!sedePrincipalRaw.isBlank()) {
                        String norm = normalizarTexto(sedePrincipalRaw);
                        boolean esSi = norm.equals("si") || norm.equals("s");
                        boolean esNo = norm.equals("no") || norm.equals("n");
                        if (!esSi && !esNo) {
                            errores.add(ubicacion + ": la columna 'Sede Principal' debe ser 'Sí' o 'No' (se escribió '" + sedePrincipalRaw + "').");
                            continue;
                        }
                    }
                    boolean marcadoSi = esValorAfirmativo(sedePrincipalRaw);
                    boolean sedePrincipalExplicita = !sedePrincipalRaw.isBlank();

                    // Abono: debe ser numérico si se llenó.
                    BigDecimal saldoAbono = BigDecimal.ZERO;
                    if (!saldoAbonoRaw.isBlank()) {
                        String numClean = saldoAbonoRaw.replaceAll("[^0-9.-]", "");
                        if (numClean.isBlank()) {
                            errores.add(ubicacion + ": el Abono '" + saldoAbonoRaw + "' no es un número válido.");
                            continue;
                        }
                        try {
                            saldoAbono = new BigDecimal(numClean);
                        } catch (NumberFormatException nfe) {
                            errores.add(ubicacion + ": el Abono '" + saldoAbonoRaw + "' no es un número válido.");
                            continue;
                        }
                    }

                    // Fecha de nacimiento: si se escribió algo, debe poder interpretarse.
                    LocalDate fechaNac = parseFecha(fechaNacStr, row, colIndex.getOrDefault("fechanacimiento", 3));
                    if (!fechaNacStr.isBlank() && fechaNac == null) {
                        errores.add(ubicacion + ": la Fecha de Nacimiento '" + fechaNacStr + "' no tiene un formato válido (usa AAAA-MM-DD).");
                        continue;
                    }

                    // Matrícula / Seguro (solo si el club los tiene como OPCIONALES — si son
                    // obligatorios o el club no los cobra, estas columnas ni existen en el archivo).
                    boolean matriculaActiva = false;
                    boolean matriculaPagada = false;
                    if (columnas.idxMatriculaActiva >= 0) {
                        String siNoErr = validarSiNo(matriculaActivaRaw, "Matrícula (Sí/No)", ubicacion, errores);
                        if (siNoErr != null) continue;
                        matriculaActiva = esValorAfirmativo(matriculaActivaRaw);
                        if (matriculaActiva) {
                            String siNoErr2 = validarSiNo(matriculaPagadaRaw, "Matrícula Pagada (Sí/No)", ubicacion, errores);
                            if (siNoErr2 != null) continue;
                            matriculaPagada = esValorAfirmativo(matriculaPagadaRaw);
                        }
                    }
                    boolean seguroActivo = false;
                    boolean seguroPagado = false;
                    if (columnas.idxSeguroActiva >= 0) {
                        String siNoErr = validarSiNo(seguroActivoRaw, "Seguro Deportivo (Sí/No)", ubicacion, errores);
                        if (siNoErr != null) continue;
                        seguroActivo = esValorAfirmativo(seguroActivoRaw);
                        if (seguroActivo) {
                            String siNoErr2 = validarSiNo(seguroPagadoRaw, "Seguro Pagado (Sí/No)", ubicacion, errores);
                            if (siNoErr2 != null) continue;
                            seguroPagado = esValorAfirmativo(seguroPagadoRaw);
                        }
                    }

                    FilaImport f = new FilaImport();
                    f.sheetName = sheet.getSheetName();
                    f.filaExcel = r + 1;
                    f.esHojaInactiva = esHojaInactiva;
                    f.nombreAcudienteRaw = nombreAcudienteRaw;
                    f.telLimpio = telLimpio;
                    f.nombreDeportista = nombreDeportista;
                    f.fechaNac = fechaNac;
                    f.sede = sede;
                    f.nivelFormatted = nivelFormatted;
                    f.plan = planMatch;
                    f.marcadoSi = marcadoSi;
                    f.sedePrincipalExplicita = sedePrincipalExplicita;
                    f.saldoAbono = saldoAbono;
                    f.ultimaColRaw = ultimaColRaw;
                    f.matriculaActiva = matriculaActiva;
                    f.matriculaPagada = matriculaPagada;
                    f.seguroActivo = seguroActivo;
                    f.seguroPagado = seguroPagado;
                    // La clave es solo el nombre del deportista (normalizado): así una fila puede
                    // dejar el teléfono en blanco (se completa solo desde otra fila del mismo
                    // deportista) sin que eso rompa el agrupamiento. Si dos deportistas distintos
                    // se llaman igual, la instrucción es diferenciarlos en el propio nombre.
                    f.deportistaKey = normalizarTexto(nombreDeportista);
                    filas.add(f);
                }
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            log.error("Error al leer el archivo Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al procesar el archivo Excel: " + e.getMessage());
        }

        // Regla: si un deportista aparece en más de una fila (varias sedes), EXACTAMENTE una debe
        // estar marcada 'Sede Principal = Sí'. Si solo tiene una fila, esa es su principal sin
        // necesidad de marcarla — pero con 2+ filas, no se adivina: se exige explícito para evitar
        // que un error humano deje a un deportista con dos sedes cobrando mensualidad, o con ninguna.
        Map<String, List<FilaImport>> porDeportista = new LinkedHashMap<>();
        for (FilaImport f : filas) {
            porDeportista.computeIfAbsent(f.deportistaKey, k -> new ArrayList<>()).add(f);
        }
        for (List<FilaImport> grupoFilas : porDeportista.values()) {
            String nombreMostrar = grupoFilas.get(0).nombreDeportista;
            if (grupoFilas.size() == 1) {
                FilaImport unica = grupoFilas.get(0);
                // Si su única sede viene marcada explícitamente como 'No', es una contradicción:
                // el deportista se quedaría sin ninguna sede principal donde cobrarle. Se rechaza
                // en vez de forzarla a 'Sí' en silencio.
                if (unica.sedePrincipalExplicita && !unica.marcadoSi) {
                    errores.add("Hoja [" + unica.sheetName + "] Fila " + unica.filaExcel + ": el deportista '" + nombreMostrar
                            + "' solo tiene una sede (" + unica.sede.getNombre() + ") pero la marcaste como Sede Principal = No. "
                            + "Debe tener una sede principal — pon 'Sí' o deja la celda vacía.");
                    continue;
                }
                unica.marcarPrincipal = true;
                continue;
            }
            List<FilaImport> marcadas = grupoFilas.stream().filter(f -> f.marcadoSi).toList();
            String filasTxt = grupoFilas.stream().map(f -> "Hoja [" + f.sheetName + "] Fila " + f.filaExcel)
                    .reduce((a, b) -> a + " y " + b).orElse("");
            if (marcadas.size() > 1) {
                String filasConflicto = marcadas.stream().map(f -> "Hoja [" + f.sheetName + "] Fila " + f.filaExcel)
                        .reduce((a, b) -> a + " y " + b).orElse("");
                errores.add("El deportista '" + nombreMostrar + "' tiene más de una fila marcada como Sede Principal = Sí ("
                        + filasConflicto + "). Deja marcada 'Sí' en una sola fila y 'No' (o vacío) en las demás.");
            } else if (marcadas.isEmpty()) {
                errores.add("El deportista '" + nombreMostrar + "' aparece en " + grupoFilas.size() + " sedes distintas ("
                        + filasTxt + ") pero ninguna fila está marcada como Sede Principal = Sí. Marca 'Sí' en la sede donde se le debe cobrar.");
            } else {
                marcadas.get(0).marcarPrincipal = true;
            }
        }

        // El Plan y la Mensualidad Pendiente solo tienen sentido en la sede PRINCIPAL: es ahí donde
        // se cobra. En una sede adicional, el deportista solo aparece en la lista de esa sede — no
        // se le cobra ahí, así que Plan debe quedar vacío y no puede tener mensualidad pendiente.
        for (FilaImport f : filas) {
            String ubicacionFila = "Hoja [" + f.sheetName + "] Fila " + f.filaExcel;
            if (f.marcarPrincipal) {
                if (f.plan == null) {
                    String disponibles = planMensualidadRepository.findBySedeId(f.sede.getId()).stream()
                            .map(PlanMensualidad::getNombre).filter(Objects::nonNull).sorted()
                            .reduce((a, b) -> a + ", " + b).orElse("(la sede '" + f.sede.getNombre() + "' no tiene planes creados — créalos primero en el ERP)");
                    errores.add(ubicacionFila + ": falta el Plan en la sede principal del deportista '" + f.nombreDeportista
                            + "' (ahí es donde se le cobra). Planes disponibles en '" + f.sede.getNombre() + "': " + disponibles + ".");
                }
            } else {
                if (f.plan != null) {
                    errores.add(ubicacionFila + ": el Plan solo se asigna en la fila de la Sede Principal del deportista '"
                            + f.nombreDeportista + "' — en esta sede adicional debe quedar vacío (ahí no se le cobra, solo queda registrado).");
                }
                if (esquemaImport == ClubConfig.EsquemaCobro.MENSUALIDAD && esValorAfirmativo(f.ultimaColRaw)) {
                    errores.add(ubicacionFila + ": 'Mensualidad Pendiente' no se puede marcar en una sede que no es la principal del deportista '"
                            + f.nombreDeportista + "'. Esa columna solo aplica en la fila de su Sede Principal.");
                }
            }
        }

        // Un deportista con varias sedes solo necesita escribir Teléfono, Fecha de Nacimiento y
        // Abono UNA vez (en cualquiera de sus filas) — las demás se pueden dejar en blanco. Si hay
        // dos filas con valores DISTINTOS para el mismo campo, es un error humano real (como el que
        // se ve al copiar/pegar) y se rechaza en vez de quedarse con uno de los dos en silencio.
        for (List<FilaImport> grupoFilas : porDeportista.values()) {
            String nombreMostrar = grupoFilas.get(0).nombreDeportista;

            List<String> telefonos = grupoFilas.stream().map(f -> f.telLimpio).filter(t -> !t.isBlank())
                    .map(String::trim).distinct().toList();
            if (telefonos.size() > 1) {
                errores.add("El deportista '" + nombreMostrar + "' tiene teléfonos distintos en sus filas ("
                        + String.join(" / ", telefonos) + "). Escríbelo en una sola fila y deja las demás en blanco.");
            } else if (telefonos.size() == 1) {
                grupoFilas.forEach(f -> f.telLimpio = telefonos.get(0));
            }

            List<LocalDate> fechas = grupoFilas.stream().map(f -> f.fechaNac).filter(Objects::nonNull).distinct().toList();
            if (fechas.size() > 1) {
                String fechasTxt = fechas.stream().map(LocalDate::toString).reduce((a, b) -> a + " / " + b).orElse("");
                errores.add("El deportista '" + nombreMostrar + "' tiene Fechas de Nacimiento distintas en sus filas ("
                        + fechasTxt + "). Escríbela en una sola fila y deja las demás en blanco.");
            } else if (fechas.size() == 1) {
                grupoFilas.forEach(f -> f.fechaNac = fechas.get(0));
            }

            List<BigDecimal> abonos = grupoFilas.stream().map(f -> f.saldoAbono)
                    .filter(a -> a != null && a.compareTo(BigDecimal.ZERO) != 0)
                    .distinct().toList();
            if (abonos.size() > 1) {
                String abonosTxt = abonos.stream().map(BigDecimal::toPlainString).reduce((a, b) -> a + " / " + b).orElse("");
                errores.add("El deportista '" + nombreMostrar + "' tiene Abonos distintos en sus filas ($" + abonosTxt
                        + "). Escríbelo en una sola fila (déjalo en $0 en las demás) — si no, no queda claro cuál es el real.");
            } else if (abonos.size() == 1) {
                grupoFilas.forEach(f -> f.saldoAbono = abonos.get(0));
            }
        }

        if (!errores.isEmpty()) {
            throw new ImportValidationException(errores);
        }

        // ─────────────────────────── FASE 2: todo validado, ahora sí se persiste ───────────────────────────
        int deportistasCreados = 0;
        int padresCreados = 0;
        int matriculasCreadas = 0;

        String batchId = UUID.randomUUID().toString();
        ImportBatchLog batchLog = new ImportBatchLog();
        batchLog.setBatchId(batchId);
        batchLog.setTimestamp(LocalDateTime.now());
        batchLog.setClubId(importClubId);

        for (FilaImport f : filas) {
            String estadoParentImport = f.esHojaInactiva ? "INACTIVO" : "ACTIVO";
            Student.StudentStatus estadoStudentImport = f.esHojaInactiva ? Student.StudentStatus.RETIRADO : Student.StudentStatus.ACTIVO;

            // Parent (Acudiente) — SEC: restringido al club del usuario que importa (aislamiento multi-tenant).
            Parent parent = null;
            if (!f.telLimpio.isBlank()) {
                parent = parentRepository.findByTelefonoAndClubId(f.telLimpio, importClubId);
            }
            if (parent == null && !f.nombreAcudienteRaw.isBlank() && !f.nombreAcudienteRaw.matches("^\\d+$")) {
                final String searchAcudienteNorm = normalizarTexto(f.nombreAcudienteRaw);
                parent = parentRepository.findByClubId(importClubId).stream()
                        .filter(p -> p.getNombreCompleto() != null && normalizarTexto(p.getNombreCompleto()).equals(searchAcudienteNorm))
                        .findFirst().orElse(null);
            }

            String nombreAcudiente = toTitleCase(f.nombreAcudienteRaw);
            if (nombreAcudiente.isBlank() || nombreAcudiente.matches("^\\d+$")) {
                if (parent != null && parent.getNombreCompleto() != null && !parent.getNombreCompleto().matches("^\\d+$")) {
                    nombreAcudiente = parent.getNombreCompleto();
                } else {
                    nombreAcudiente = !f.telLimpio.isBlank() ? "Acudiente " + f.telLimpio : "Acudiente " + f.nombreDeportista;
                }
            }

            if (parent != null) {
                final Long targetParentId = parent.getId();
                boolean yaGuardadoSnap = batchLog.getParentSnapshots().stream()
                        .anyMatch(s -> s.getParentId().equals(targetParentId));
                if (!yaGuardadoSnap) {
                    ParentSnapshot snap = new ParentSnapshot(
                            parent.getId(), parent.getNombreCompleto(), parent.getTelefono(),
                            parent.getEstado(), parent.getSaldoAbono());
                    batchLog.getParentSnapshots().add(snap);
                }

                boolean updated = false;
                if (parent.getNombreCompleto() == null || parent.getNombreCompleto().isBlank() || parent.getNombreCompleto().startsWith("Acudiente ")) {
                    if (!nombreAcudiente.isBlank() && !nombreAcudiente.matches("^\\d+$") && !nombreAcudiente.startsWith("Acudiente ")) {
                        parent.setNombreCompleto(nombreAcudiente);
                        updated = true;
                    }
                }
                if ((parent.getTelefono() == null || parent.getTelefono().isBlank()) && !f.telLimpio.isBlank()) {
                    parent.setTelefono(f.telLimpio);
                    updated = true;
                }
                if (updated) parentRepository.save(parent);
            } else {
                String phoneCandidate = !f.telLimpio.isBlank() ? f.telLimpio : "300" + (System.currentTimeMillis() % 10000000);
                Parent existingByPhone = parentRepository.findByTelefonoAndClubId(phoneCandidate, importClubId);
                if (existingByPhone != null) {
                    parent = existingByPhone;
                } else {
                    parent = new Parent();
                    parent.setNombreCompleto(nombreAcudiente);
                    parent.setTelefono(phoneCandidate);
                    parent.setEstado(estadoParentImport);
                    parent.setSaldoAbono(f.saldoAbono);
                    parent.setSecretToken(UUID.randomUUID().toString());
                    if (importClubId != null) parent.setClubId(importClubId);
                    parent = parentRepository.save(parent);
                    padresCreados++;
                    batchLog.getCreatedParentIds().add(parent.getId());
                }
            }

            // Student (Deportista)
            final String searchDeportistaNorm = normalizarTexto(f.nombreDeportista);
            Student student = null;
            if (parent.getStudents() != null) {
                student = parent.getStudents().stream()
                        .filter(s -> s.getNombreCompleto() != null && normalizarTexto(s.getNombreCompleto()).equals(searchDeportistaNorm))
                        .findFirst().orElse(null);
            }
            boolean estudianteNuevo = (student == null);
            Integer edadCalculada = calcularEdad(f.fechaNac);

            if (student == null) {
                student = new Student();
                student.setParent(parent);
                student.setNombreCompleto(f.nombreDeportista);
                student.setFechaNacimiento(f.fechaNac);
                student.setEdad(edadCalculada);
                student.setEstado(estadoStudentImport);
                if (importClubId != null) student.setClubId(importClubId);
                student.setMatriculas(new ArrayList<>());
                student = studentRepository.save(student);
                deportistasCreados++;
                batchLog.getCreatedStudentIds().add(student.getId());

                // CRÍTICO: agregar el estudiante recién creado a la colección en memoria del padre.
                // Sin esto, si el mismo deportista aparece en una fila posterior de este mismo
                // archivo (otra sede), la búsqueda de "¿ya existe este deportista?" no lo
                // encuentra (la colección quedó desactualizada) y se crea un SEGUNDO deportista
                // duplicado en vez de agregarle la sede al mismo — dejando a cada uno con una sola
                // sede y sin que nunca aparezca el selector de Sede Principal entre ambas.
                if (parent.getStudents() == null) {
                    parent.setStudents(new ArrayList<>());
                }
                parent.getStudents().add(student);
            }

            // Enrollment (Matrícula): sede + nivel + plan (opcional) + sede principal — ya validados en fase 1.
            final Sede targetSede = f.sede;
            final String targetNivelNorm = normalizarTexto(f.nivelFormatted);
            Enrollment matriculaExistente = student.getMatriculas() == null ? null
                    : student.getMatriculas().stream()
                        .filter(m -> m.getSede() != null && m.getSede().getId().equals(targetSede.getId()) &&
                                m.getNivel() != null && normalizarTexto(m.getNivel()).equals(targetNivelNorm))
                        .findFirst().orElse(null);

            if (f.marcarPrincipal && student.getMatriculas() != null) {
                for (Enrollment otra : student.getMatriculas()) {
                    boolean esLaMisma = matriculaExistente != null && otra.getId().equals(matriculaExistente.getId());
                    if (!esLaMisma && Boolean.TRUE.equals(otra.getEsPrincipal())) {
                        otra.setEsPrincipal(false);
                        enrollmentRepository.save(otra);
                    }
                }
            }

            if (matriculaExistente == null) {
                Enrollment enrollment = new Enrollment();
                enrollment.setStudent(student);
                enrollment.setSede(f.sede);
                enrollment.setNivel(f.nivelFormatted);
                enrollment.setPlanMensualidad(f.plan);
                enrollment.setEsPrincipal(f.marcarPrincipal);
                enrollment = enrollmentRepository.save(enrollment);

                if (student.getMatriculas() == null) student.setMatriculas(new ArrayList<>());
                student.getMatriculas().add(enrollment);
                studentRepository.save(student);

                batchLog.getCreatedEnrollmentIds().add(enrollment.getId());
                matriculasCreadas++;
            } else {
                boolean actualizada = false;
                if (f.plan != null && !f.plan.equals(matriculaExistente.getPlanMensualidad())) {
                    matriculaExistente.setPlanMensualidad(f.plan);
                    actualizada = true;
                }
                if (f.marcarPrincipal && !Boolean.TRUE.equals(matriculaExistente.getEsPrincipal())) {
                    matriculaExistente.setEsPrincipal(true);
                    actualizada = true;
                }
                if (actualizada) enrollmentRepository.save(matriculaExistente);
            }

            // Matrícula / Seguro deportivo: reutiliza la MISMA lógica real que usa el registro manual
            // de un deportista (FinancialService.aplicarCargosAEstudiante) — respeta vigencia y evita
            // reinventar reglas de cobro. Si el club los tiene desactivados no hace nada. Si son
            // obligatorios, se activan y cobran a TODOS sin depender de columnas del Excel. Si son
            // opcionales, siguen lo marcado en 'Matrícula/Seguro (Sí/No)'; si además viene marcado
            // como ya pagado, se retira el cargo para no dejarlo como deuda flotante.
            if (columnas.cobraMatricula || columnas.cobraSeguro) {
                if (columnas.matriculaObligatoria) {
                    student.setAdquiereMatricula(true);
                } else if (columnas.idxMatriculaActiva >= 0) {
                    student.setAdquiereMatricula(f.matriculaActiva);
                }
                if (columnas.seguroObligatoria) {
                    student.setAdquiereSeguro(true);
                } else if (columnas.idxSeguroActiva >= 0) {
                    student.setAdquiereSeguro(f.seguroActivo);
                }
                studentRepository.save(student);

                financialService.aplicarCargosAEstudiante(student, configImport);

                if (!columnas.matriculaObligatoria && f.matriculaActiva && f.matriculaPagada) {
                    financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), "Matrícula Anual - " + f.nombreDeportista);
                }
                if (!columnas.seguroObligatoria && f.seguroActivo && f.seguroPagado) {
                    financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), "Seguro Deportivo - " + f.nombreDeportista);
                }
            }

            // Última columna: interpretación según esquema de cobro del club
            if (esquemaImport == ClubConfig.EsquemaCobro.PAQUETE) {
                if (estudianteNuevo && f.ultimaColRaw != null && !f.ultimaColRaw.isBlank()) {
                    try {
                        String numClean = f.ultimaColRaw.replaceAll("[^0-9.-]", "");
                        if (!numClean.isBlank() && !numClean.equals("-") && !numClean.equals(".")) {
                            student.setClasesDisponibles((int) Math.round(Double.parseDouble(numClean)));
                            studentRepository.save(student);
                        }
                    } catch (Exception ignored) {
                        // Valor no numérico: se ignora, el deportista queda en 0 por defecto.
                    }
                }
            } else if (esquemaImport == ClubConfig.EsquemaCobro.MENSUALIDAD) {
                if (esValorAfirmativo(f.ultimaColRaw) && parent != null
                        && !attendanceRepository.existsByStudentIdAndYearAndMonth(
                                student.getId(), LocalDate.now().getYear(), LocalDate.now().getMonthValue())) {
                    Long targetSedeId = f.sede != null ? f.sede.getId() : null;
                    BigDecimal precioMensualidad = configImport != null
                            ? monthlyBillingService.calculateMonthlyPrice(configImport, targetSedeId, LocalDateTime.now())
                            : BigDecimal.ZERO;

                    Attendance pendiente = new Attendance();
                    pendiente.setStudent(student);
                    pendiente.setFecha(LocalDateTime.now());
                    pendiente.setPrecioCobrado(precioMensualidad);
                    pendiente.setClasePaga(precioMensualidad.compareTo(BigDecimal.ZERO) == 0);
                    pendiente.setNivel(f.nivelFormatted.isBlank() ? null : f.nivelFormatted);
                    pendiente.setNombreEstudianteHistorico(f.nombreDeportista);
                    pendiente.setTipoClase("MENSUALIDAD");
                    pendiente.setClubId(importClubId);
                    if (f.sede != null) pendiente.setSede(f.sede);
                    attendanceRepository.save(pendiente);
                    log.info("Mensualidad pendiente importada para deportista {} (${})", f.nombreDeportista, precioMensualidad);
                }
            } else {
                BigDecimal debeMonto = BigDecimal.ZERO;
                if (f.ultimaColRaw != null && !f.ultimaColRaw.isBlank()) {
                    try {
                        String numClean = f.ultimaColRaw.replaceAll("[^0-9.-]", "");
                        if (!numClean.isBlank()) debeMonto = new BigDecimal(numClean);
                    } catch (Exception ignored) {
                        debeMonto = BigDecimal.ZERO;
                    }
                }
                if (debeMonto.compareTo(BigDecimal.ZERO) > 0 && parent != null) {
                    String conceptoDebe = "Deuda importada - " + f.nombreDeportista;
                    boolean yaExisteCargo = !financialLogRepository
                            .findByParentIdAndTipoMovimientoAndConceptoContaining(
                                    parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, conceptoDebe)
                            .isEmpty();
                    if (!yaExisteCargo) {
                        FinancialLog cargo = new FinancialLog();
                        cargo.setParent(parent);
                        cargo.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
                        cargo.setMonto(debeMonto);
                        cargo.setConcepto(conceptoDebe);
                        cargo.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
                        cargo.setFecha(LocalDateTime.now());
                        if (importClubId != null) cargo.setClubId(importClubId);
                        financialLogRepository.save(cargo);
                        batchLog.getCreatedFinancialLogIds().add(cargo.getId());
                        log.info("Cargo extra importado: ${} para parent {}", debeMonto, parent.getId());
                    }
                }
            }
        }

        importBatchLogRepository.save(batchLog);

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("batchId", batchId);
        resultado.put("totalProcesados", filas.size());
        resultado.put("deportistasCreados", deportistasCreados);
        resultado.put("padresCreados", padresCreados);
        resultado.put("matriculasCreadas", matriculasCreadas);
        resultado.put("errores", List.of());

        return resultado;
    }

    /**
     * Deshace una importación revirtiendo 100% de las matrículas, estudiantes, padres, sedes y ediciones realizadas a acudientes preexistentes.
     */
    @Transactional
    public Map<String, Object> deshacerImportacion(String batchId) {
        Long currentClubId = SecurityUtils.getClubId();
        ImportBatchLog batchLog = null;
        if (batchId != null && !batchId.isBlank() && !"ultimo".equalsIgnoreCase(batchId)) {
            batchLog = importBatchLogRepository.findById(batchId).orElse(null);
        }

        if (batchLog == null || Boolean.TRUE.equals(batchLog.getReverted())) {
            if (currentClubId != null) {
                batchLog = importBatchLogRepository.findTopByClubIdAndRevertedFalseOrderByTimestampDesc(currentClubId).orElse(null);
            }
            if (batchLog == null) {
                batchLog = importBatchLogRepository.findTopByRevertedFalseOrderByTimestampDesc().orElse(null);
            }
        }

        if (batchLog == null || Boolean.TRUE.equals(batchLog.getReverted())) {
            throw new IllegalArgumentException("El lote ya fue revertido o expiró por reinicio del servidor.");
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

        // 4. Eliminar cargos extras / abonos creados en la importación (ANTES de eliminar a los padres)
        if (batchLog.getCreatedFinancialLogIds() != null && !batchLog.getCreatedFinancialLogIds().isEmpty()) {
            for (Long fId : batchLog.getCreatedFinancialLogIds()) {
                financialLogRepository.findById(fId).ifPresent(f -> {
                    financialLogRepository.delete(f);
                    financialLogRepository.flush();
                });
            }
        }

        // Purgar cualquier movimiento financiero restante en los padres creados en este lote
        for (Long parentId : batchLog.getCreatedParentIds()) {
            List<FinancialLog> logsPadre = financialLogRepository.findByParentIdOrderByFechaDesc(parentId);
            if (!logsPadre.isEmpty()) {
                financialLogRepository.deleteAll(logsPadre);
                financialLogRepository.flush();
            }
        }

        // 5. Eliminar padres creados en la importación
        for (Long parentId : batchLog.getCreatedParentIds()) {
            parentRepository.findById(parentId).ifPresent(parent -> {
                if (parent.getStudents() == null || parent.getStudents().isEmpty()) {
                    parentRepository.delete(parent);
                    parentRepository.flush();
                    padresEliminados[0]++;
                }
            });
        }

        // 6. Eliminar sedes creadas en la importación
        for (Long sedeId : batchLog.getCreatedSedeIds()) {
            sedeRepository.findById(sedeId).ifPresent(sede -> {
                // Verificar si la sede está en uso por asistencias
                boolean hasAttendances = !attendanceRepository.findBySedeId(sedeId).isEmpty();
                // Verificar si está en uso por matrículas restantes
                List<Enrollment> remainingEnrollments = enrollmentRepository.findBySedeId(sedeId);
                
                if (!hasAttendances && remainingEnrollments.isEmpty()) {
                    // A. Desvincular de usuarios / empleados autorizados
                    List<AppUser> usuarios = appUserRepository.findAll();
                    for (AppUser u : usuarios) {
                        if (u.getSedesAutorizadas() != null && u.getSedesAutorizadas().contains(sede)) {
                            u.getSedesAutorizadas().remove(sede);
                            appUserRepository.save(u);
                        }
                    }
                    appUserRepository.flush();

                    // B. Limpiar grupos de la sede (ElementCollection)
                    if (sede.getGrupos() != null) {
                        sede.getGrupos().clear();
                        sedeRepository.saveAndFlush(sede);
                    }

                    // C. Eliminar sede físicamente de la base de datos
                    sedeRepository.delete(sede);
                    sedeRepository.flush();
                    sedesEliminadas[0]++;
                } else {
                    log.warn("Sede ID {} en uso (asistencias o matrículas), se conserva al deshacer importación.", sedeId);
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
     * Exporta todos los deportistas (con sus acudientes) a un archivo Excel .xlsx con formato nativo
     * de Fecha (Date) y Moneda (Currency COP sin decimales). Aplica exactamente el mismo formato a
     * "Deportistas Activos" y "Deportistas Inactivos". Si quien exporta es un EMPLEADO (entrenador),
     * el contenido se filtra a únicamente sus sedes autorizadas — un ADMIN ve todo el club.
     */
    public byte[] exportarClientesExcel() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle lockedHeaderStyle = crearEstiloEncabezadoBloqueado(workbook);
            CellStyle lockedCellStyle = crearEstiloCeldaBloqueada(workbook);
            CellStyle dateStyle = crearEstiloFecha(workbook);
            CellStyle currencyStyle = crearEstiloMoneda(workbook);

            Long clubId = SecurityUtils.getClubId();
            ClubConfig.EsquemaCobro esquema = resolverEsquemaActual();
            ClubConfig config = clubId != null ? clubConfigRepository.findByAdminId(clubId).orElse(null) : null;
            ColumnasPlantilla columnas = construirColumnas(config, esquema, true);

            // Igual que en la plantilla: se agregan las hojas de referencia con las sedes/grupos y
            // planes REALES del club, para que el archivo exportado quede tan completo como la plantilla.
            List<Sede> sedes = clubId != null ? sedeRepository.findAllActivasWithGrupos(clubId) : List.of();
            List<PlanMensualidad> planes = clubId != null
                    ? planMensualidadRepository.findByClubIdWithCupos(clubId).stream()
                        .filter(p -> Boolean.TRUE.equals(p.getActivo()))
                        .toList()
                    : List.of();
            if (SecurityUtils.isEmpleado()) {
                Set<Long> sedesPermitidasRef = new HashSet<>(SecurityUtils.getSedesAutorizadas());
                sedes = sedes.stream().filter(s -> sedesPermitidasRef.contains(s.getId())).toList();
                planes = planes.stream().filter(p -> p.getSede() != null && sedesPermitidasRef.contains(p.getSede().getId())).toList();
            }
            crearHojaSedesGrupos(workbook, sedes, lockedHeaderStyle, lockedCellStyle);
            crearHojaPlanes(workbook, planes, config, lockedHeaderStyle, lockedCellStyle, currencyStyle);

            // SEC: exportar únicamente los acudientes del club del usuario autenticado
            List<Parent> todosLosPadres = parentRepository.findByClubId(clubId);

            List<Parent> activos = todosLosPadres.stream()
                    .filter(p -> p.getEstado() == null || !p.getEstado().equalsIgnoreCase("INACTIVO"))
                    .toList();

            List<Parent> inactivos = todosLosPadres.stream()
                    .filter(p -> p.getEstado() != null && p.getEstado().equalsIgnoreCase("INACTIVO"))
                    .toList();

            // Un EMPLEADO (entrenador) solo debe ver deportistas matriculados en sus sedes autorizadas.
            Set<Long> sedesPermitidas = SecurityUtils.isEmpleado()
                    ? new HashSet<>(SecurityUtils.getSedesAutorizadas())
                    : null;

            // Hoja 1: Deportistas Activos
            Sheet sheetActivos = workbook.createSheet("Deportistas Activos");
            construirHojaClientes(sheetActivos, activos, config, columnas, headerStyle, dateStyle, currencyStyle, sedesPermitidas);

            // Hoja 2: Deportistas Inactivos
            Sheet sheetInactivos = workbook.createSheet("Deportistas Inactivos");
            construirHojaClientes(sheetInactivos, inactivos, config, columnas, headerStyle, dateStyle, currencyStyle, sedesPermitidas);

            workbook.setActiveSheet(workbook.getSheetIndex(sheetActivos));

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error al exportar clientes a Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al exportar clientes a Excel: " + e.getMessage());
        }
    }

    /**
     * Genera la Plantilla oficial .xlsx del club autenticado (multi-tenant): trae sus sedes,
     * grupos y planes REALES ya configurados en el ERP (nada de datos inventados). El admin
     * solo escribe deportistas — Sede, Nivel/Grupo y Plan se eligen de listas desplegables
     * armadas con lo que el club ya tiene creado; ver hojas "Sedes y Grupos" y "Planes".
     */
    public byte[] generarPlantillaExcel() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle lockedHeaderStyle = crearEstiloEncabezadoBloqueado(workbook);
            CellStyle lockedCellStyle = crearEstiloCeldaBloqueada(workbook);
            CellStyle currencyStyle = crearEstiloMoneda(workbook);
            CellStyle noteStyle = crearEstiloNota(workbook);

            Long clubId = SecurityUtils.getClubId();
            ClubConfig.EsquemaCobro esquema = resolverEsquemaActual();
            ClubConfig config = clubId != null ? clubConfigRepository.findByAdminId(clubId).orElse(null) : null;

            List<Sede> sedes = clubId != null ? sedeRepository.findAllActivasWithGrupos(clubId) : List.of();
            List<PlanMensualidad> planes = clubId != null
                    ? planMensualidadRepository.findByClubIdWithCupos(clubId).stream()
                        .filter(p -> Boolean.TRUE.equals(p.getActivo()))
                        .toList()
                    : List.of();

            // Un EMPLEADO (entrenador) solo debe ver e importar en sus sedes autorizadas.
            if (SecurityUtils.isEmpleado()) {
                Set<Long> sedesPermitidas = new HashSet<>(SecurityUtils.getSedesAutorizadas());
                sedes = sedes.stream().filter(s -> sedesPermitidas.contains(s.getId())).toList();
                planes = planes.stream().filter(p -> p.getSede() != null && sedesPermitidas.contains(p.getSede().getId())).toList();
            }

            crearHojaInicio(workbook, sedes.size(), planes.size(), config, noteStyle);
            crearHojaSedesGrupos(workbook, sedes, lockedHeaderStyle, lockedCellStyle);
            crearHojaPlanes(workbook, planes, config, lockedHeaderStyle, lockedCellStyle, currencyStyle);

            String[] sedeNombres = sedes.stream().map(Sede::getNombre).toArray(String[]::new);
            ColumnasPlantilla columnas = construirColumnas(config, esquema);

            // Hoja de trabajo: Deportistas Activos (vacía, sin filas inventadas — solo encabezados y listas desplegables)
            Sheet sheetActivos = workbook.createSheet("Deportistas Activos");
            crearEncabezados(sheetActivos, headerStyle, columnas);
            aplicarValidacionesPlantilla(sheetActivos, columnas, sedeNombres);
            aplicarFormatoMonedaColumnas(sheetActivos, columnas, currencyStyle);
            autoajustarColumnas(sheetActivos, columnas.headers.size());

            // Hoja de trabajo: Deportistas Inactivos
            Sheet sheetInactivos = workbook.createSheet("Deportistas Inactivos");
            crearEncabezados(sheetInactivos, headerStyle, columnas);
            aplicarValidacionesPlantilla(sheetInactivos, columnas, sedeNombres);
            aplicarFormatoMonedaColumnas(sheetInactivos, columnas, currencyStyle);
            autoajustarColumnas(sheetInactivos, columnas.headers.size());

            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error generando plantilla Excel: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar la plantilla de Excel: " + e.getMessage());
        }
    }

    /** Hoja de instrucciones en lenguaje simple, con los datos reales del club que descarga la plantilla. */
    private void crearHojaInicio(Workbook workbook, int totalSedes, int totalPlanes,
                                  ClubConfig config, CellStyle noteStyle) {
        Sheet sheet = workbook.createSheet("Inicio");
        sheet.setColumnWidth(0, 100 * 256);

        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        CellStyle titleStyle = workbook.createCellStyle();
        titleStyle.setFont(titleFont);

        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle boldStyle = workbook.createCellStyle();
        boldStyle.setFont(boldFont);

        int r = 0;
        sheet.createRow(r).createCell(0).setCellValue("Importar Deportistas");
        sheet.getRow(r).getCell(0).setCellStyle(titleStyle);
        r++;

        r++;
        Row rEstado = sheet.createRow(r++);
        Cell cEstado = rEstado.createCell(0);
        if (totalSedes == 0) {
            cEstado.setCellValue("⚠ Este club todavía no tiene sedes creadas en el ERP. Ve a 'Gestión de Sedes' y crea al menos una antes de importar.");
        } else {
            cEstado.setCellValue("Esta plantilla ya trae " + totalSedes + " sede(s) y " + totalPlanes
                    + " plan(es) reales de tu club, tal como están configurados hoy en el ERP.");
        }
        cEstado.setCellStyle(noteStyle);
        r++;

        boolean matriculaOpcional = config != null && Boolean.TRUE.equals(config.getCobraMatricula()) && !Boolean.TRUE.equals(config.getMatriculaObligatoria());
        boolean matriculaObligatoria = config != null && Boolean.TRUE.equals(config.getCobraMatricula()) && Boolean.TRUE.equals(config.getMatriculaObligatoria());
        boolean seguroOpcional = config != null && Boolean.TRUE.equals(config.getCobraSeguro()) && !Boolean.TRUE.equals(config.getSeguroObligatorio());
        boolean seguroObligatorio = config != null && Boolean.TRUE.equals(config.getCobraSeguro()) && Boolean.TRUE.equals(config.getSeguroObligatorio());

        List<String[]> lineasList = new ArrayList<>(List.of(new String[][]{
                {"¿Para qué sirve?", "bold"},
                {"Para agregar deportistas en lote, más rápido que uno por uno. Las sedes, grupos y planes ya vienen cargados — no se editan aquí; cualquier cambio se hace en el ERP.", "text"},
                {"", ""},
                {"Cómo llenarla (lo básico)", "bold"},
                {"1. Ve a 'Deportistas Activos' (o 'Deportistas Inactivos' si ya no asisten) y agrega una fila por cada deportista.", "text"},
                {"2. Elige la Sede y el Nivel/Grupo — revisa la hoja 'Sedes y Grupos' si tienes dudas de los nombres exactos.", "text"},
                {"3. En 'Plan' escribe el nombre de uno de los planes de esa sede (hoja 'Planes'). Es obligatorio: un deportista sin plan no queda cobrando nada.", "text"},
                {"4. Marca 'Sede Principal' con 'Sí' en la fila donde se le debe cobrar la mensualidad a ese deportista.", "text"},
        }));

        if (matriculaOpcional) {
            lineasList.add(new String[]{"Matrícula (opcional en tu club)", "bold"});
            lineasList.add(new String[]{"Marca 'Matrícula' = Sí si ese deportista debe pagarla. Si además ya la pagó, marca 'Matrícula Pagada' = Sí — así no le queda una deuda pendiente en el sistema.", "text"});
        }
        if (seguroOpcional) {
            lineasList.add(new String[]{"Seguro Deportivo (opcional en tu club)", "bold"});
            lineasList.add(new String[]{"Marca 'Seguro Deportivo' = Sí si ese deportista debe tenerlo. Si ya está pagado, marca 'Seguro Pagado' = Sí.", "text"});
        }
        if (matriculaObligatoria || seguroObligatorio) {
            String cuales = matriculaObligatoria && seguroObligatorio ? "la matrícula y el seguro deportivo"
                    : matriculaObligatoria ? "la matrícula" : "el seguro deportivo";
            lineasList.add(new String[]{"Cobros obligatorios de tu club", "bold"});
            lineasList.add(new String[]{"Tu club cobra " + cuales + " a TODOS los deportistas — no necesitas marcarlo en el Excel, se le asigna y cobra automáticamente al importarlo.", "text"});
        }

        lineasList.addAll(List.of(new String[][]{
                {"", ""},
                {"Un deportista con varias sedes: NO repitas sus datos", "bold"},
                {"Escribe al deportista (nombre, acudiente, teléfono, fecha de nacimiento, abono) UNA sola vez, en su primera fila. Para cada sede adicional, agrega una fila nueva justo debajo dejando en blanco el Nombre del Deportista y el Acudiente — el sistema entiende que esa fila de abajo es del mismo deportista de arriba. En esa fila de abajo solo llenas Sede, Nivel/Grupo y Plan (los de esa sede adicional).", "text"},
                {"Mira el ejemplo de abajo ⬇", "text"},
        }));

        String[][] lineas = lineasList.toArray(new String[0][]);

        for (String[] linea : lineas) {
            Row row = sheet.createRow(r++);
            Cell cell = row.createCell(0);
            cell.setCellValue(linea[0]);
            if ("bold".equals(linea[1])) {
                cell.setCellStyle(boldStyle);
            }
        }

        r = escribirEjemploFilasHeredadas(sheet, r + 1, boldStyle, noteStyle);

        String[][] lineasFinales = {
                {"", ""},
                {"¿No marcaste ninguna Sede Principal?", "bold"},
                {"Si un deportista solo tiene una fila, el sistema la toma como principal automáticamente. Si tiene varias, marca 'Sí' solo en una.", "text"},
                {"", ""},
                {"¿Dos deportistas se llaman igual?", "bold"},
                {"Agrégale una inicial del acudiente para diferenciarlos en el nombre, ej: \"Juan Pérez (mamá Ana)\".", "text"},
        };
        for (String[] linea : lineasFinales) {
            Row row = sheet.createRow(r++);
            Cell cell = row.createCell(0);
            cell.setCellValue(linea[0]);
            if ("bold".equals(linea[1])) {
                cell.setCellStyle(boldStyle);
            }
        }
    }

    /** Mini-tabla de ejemplo (ilustrativa, no son datos reales) mostrando cómo se ve una fila que hereda el deportista de arriba. */
    private int escribirEjemploFilasHeredadas(Sheet sheet, int startRow, CellStyle boldStyle, CellStyle noteStyle) {
        String[] cabecera = {"Nombre Deportista", "Acudiente", "Sede", "Nivel/Grupo", "Sede Principal"};
        Row rowCabecera = sheet.createRow(startRow);
        for (int i = 0; i < cabecera.length; i++) {
            Cell c = rowCabecera.createCell(i + 1);
            c.setCellValue(cabecera[i]);
            c.setCellStyle(boldStyle);
        }

        Row row1 = sheet.createRow(startRow + 1);
        String[] fila1 = {"Juan Pérez", "Ana Pérez", "Cedritos", "Avanzado", "Sí"};
        for (int i = 0; i < fila1.length; i++) row1.createCell(i + 1).setCellValue(fila1[i]);

        Row row2 = sheet.createRow(startRow + 2);
        String[] fila2 = {"(en blanco)", "(en blanco)", "Gaitana", "Intermedio", "No"};
        for (int i = 0; i < fila2.length; i++) {
            Cell c = row2.createCell(i + 1);
            c.setCellValue(fila2[i]);
            if (i < 2) c.setCellStyle(noteStyle);
        }

        Row rowNota = sheet.createRow(startRow + 3);
        Cell cNota = rowNota.createCell(1);
        cNota.setCellValue("↑ La segunda fila es de Juan Pérez también — solo agrega la sede Gaitana. No hace falta repetir su nombre ni acudiente.");
        cNota.setCellStyle(noteStyle);

        for (int i = 1; i <= 5; i++) sheet.autoSizeColumn(i);
        return startRow + 5;
    }

    /** Hoja de solo lectura con las sedes/grupos/escenarios reales del club (se administran desde el ERP). */
    private void crearHojaSedesGrupos(Workbook workbook, List<Sede> sedes, CellStyle headerStyle, CellStyle cellStyle) {
        Sheet sheet = workbook.createSheet("Sedes y Grupos");
        String[] headers = {"Sede", "Escenario", "Grupos/Niveles", "Estado"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }

        int r = 1;
        for (Sede sede : sedes) {
            Row row = sheet.createRow(r++);
            for (int i = 0; i < 4; i++) row.createCell(i).setCellStyle(cellStyle);
            row.getCell(0).setCellValue(sede.getNombre() != null ? sede.getNombre() : "");
            row.getCell(1).setCellValue(sede.getEscenario() != null && sede.getEscenario().getNombre() != null
                    ? sede.getEscenario().getNombre() : "—");
            String gruposStr = sede.getGrupos() == null ? "" : sede.getGrupos().stream()
                    .map(g -> (g.getEmoji() != null && !g.getEmoji().isBlank() ? g.getEmoji() + " " : "") + g.getNombre())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            row.getCell(2).setCellValue(gruposStr);
            row.getCell(3).setCellValue(Boolean.TRUE.equals(sede.getActiva()) ? "Activa" : "Inactiva");
        }
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
        sheet.createFreezePane(0, 1);
        sheet.protectSheet("");
    }

    /**
     * Mora efectiva de un plan: null si la mora está desactivada (no se cobra), sea porque el club
     * no la tiene configurada globalmente (falta día de corte) o porque ni el plan ni el club tienen
     * un monto definido. Misma condición que usa MonthlyBillingService para decidir si aplica mora.
     */
    private Double moraEfectivaSiActiva(PlanMensualidad plan, ClubConfig config) {
        if (config == null || config.getDiaCorteMora() == null || config.getDiaCorteMora() <= 0) return null;
        BigDecimal montoMora = (plan != null && plan.getMontoMora() != null) ? plan.getMontoMora() : config.getMontoMora();
        if (montoMora == null) return null;
        return montoMora.doubleValue();
    }

    /** Hoja de solo lectura con los planes de mensualidad reales por sede (se administran desde el ERP). */
    private void crearHojaPlanes(Workbook workbook, List<PlanMensualidad> planes, ClubConfig config, CellStyle headerStyle,
                                  CellStyle cellStyle, CellStyle currencyStyle) {
        Sheet sheet = workbook.createSheet("Planes");

        // La columna de Mora solo aparece si al menos un plan realmente la cobra — si el club la
        // tiene desactivada (sin día de corte configurado), no se muestra en absoluto: no aparece
        // lo que el admin no activó.
        boolean mostrarMora = planes.stream().anyMatch(p -> moraEfectivaSiActiva(p, config) != null);

        List<String> headersList = new ArrayList<>(List.of("Sede", "Plan", "Tarifa Preferencial ($)", "Tarifa Estándar ($)"));
        if (mostrarMora) headersList.add("Mora ($)");
        String[] headers = headersList.toArray(new String[0]);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }

        int r = 1;
        for (PlanMensualidad plan : planes) {
            Row row = sheet.createRow(r++);
            Cell cSede = row.createCell(0);
            cSede.setCellValue(plan.getSede() != null && plan.getSede().getNombre() != null ? plan.getSede().getNombre() : "");
            cSede.setCellStyle(cellStyle);
            Cell cPlan = row.createCell(1);
            cPlan.setCellValue(plan.getNombre() != null ? plan.getNombre() : "");
            cPlan.setCellStyle(cellStyle);

            Cell cPref = row.createCell(2);
            cPref.setCellValue(plan.getMontoPreferencial() != null ? plan.getMontoPreferencial().doubleValue() : 0.0);
            cPref.setCellStyle(currencyStyle);
            Cell cEst = row.createCell(3);
            cEst.setCellValue(plan.getMontoEstandar() != null ? plan.getMontoEstandar().doubleValue() : 0.0);
            cEst.setCellStyle(currencyStyle);

            if (mostrarMora) {
                Double mora = moraEfectivaSiActiva(plan, config);
                Cell cMora = row.createCell(4);
                if (mora != null) {
                    cMora.setCellValue(mora);
                    cMora.setCellStyle(currencyStyle);
                } else {
                    cMora.setCellValue("— no aplica");
                    cMora.setCellStyle(cellStyle);
                }
            }
        }
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
        sheet.createFreezePane(0, 1);
        sheet.protectSheet("");
    }

    /** Agrega una validación Sí/No a una columna, si existe (idx &gt;= 0). */
    private void agregarValidacionSiNo(Sheet sheet, DataValidationHelper helper, int idx, int maxFilas) {
        if (idx < 0) return;
        DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{"Sí", "No"});
        CellRangeAddressList range = new CellRangeAddressList(1, maxFilas, idx, idx);
        DataValidation val = helper.createValidation(constraint, range);
        val.setShowErrorBox(true);
        sheet.addValidationData(val);
    }

    /** Listas desplegables de la hoja de importación: Sede (real), Sede Principal, Matrícula/Seguro (si aplica) y última columna si es Sí/No. */
    private void aplicarValidacionesPlantilla(Sheet sheet, ColumnasPlantilla columnas, String[] sedeNombres) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        int maxFilas = 500;

        if (sedeNombres.length > 0) {
            DataValidationConstraint sedeConstraint = helper.createExplicitListConstraint(sedeNombres);
            CellRangeAddressList sedeRange = new CellRangeAddressList(1, maxFilas, columnas.idxSede, columnas.idxSede);
            DataValidation sedeVal = helper.createValidation(sedeConstraint, sedeRange);
            sedeVal.setShowErrorBox(true);
            sheet.addValidationData(sedeVal);
        }

        agregarValidacionSiNo(sheet, helper, columnas.idxSedePrincipal, maxFilas);
        agregarValidacionSiNo(sheet, helper, columnas.idxMatriculaActiva, maxFilas);
        agregarValidacionSiNo(sheet, helper, columnas.idxMatriculaPagada, maxFilas);
        agregarValidacionSiNo(sheet, helper, columnas.idxSeguroActiva, maxFilas);
        agregarValidacionSiNo(sheet, helper, columnas.idxSeguroPagada, maxFilas);

        if (columnas.esquema == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            agregarValidacionSiNo(sheet, helper, columnas.idxUltima, maxFilas);
        }
    }

    /**
     * Aplica formato de moneda por defecto a las columnas de dinero de la hoja de trabajo
     * (Abono siempre; la última columna solo si el esquema es POR_CLASE, ya que en MENSUALIDAD
     * es texto Sí/No y en PAQUETE es un conteo de clases, no dinero). Se aplica como estilo por
     * defecto de columna: la celda se ve con formato moneda apenas el admin escribe un número,
     * sin tener que crear cientos de filas vacías de antemano.
     */
    private void aplicarFormatoMonedaColumnas(Sheet sheet, ColumnasPlantilla columnas, CellStyle currencyStyle) {
        sheet.setDefaultColumnStyle(columnas.idxAbono, currencyStyle);
        if (columnas.esquema == ClubConfig.EsquemaCobro.POR_CLASE) {
            sheet.setDefaultColumnStyle(columnas.idxUltima, currencyStyle);
        }
    }

    /**
     * Escribe la última columna de una fila según el esquema de cobro:
     * MENSUALIDAD -> texto (Sí/No), PAQUETE -> número plano (admite negativos, sin formato moneda),
     * POR_CLASE -> moneda (comportamiento histórico).
     */
    private void escribirCeldaUltimaColumna(Cell cell, ClubConfig.EsquemaCobro esquema, Object valor, CellStyle currencyStyle) {
        if (esquema == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            cell.setCellValue(valor != null ? valor.toString() : "No");
        } else if (esquema == ClubConfig.EsquemaCobro.PAQUETE) {
            double num = (valor instanceof Number n) ? n.doubleValue() : 0.0;
            cell.setCellValue(num);
        } else {
            double num = (valor instanceof Number n) ? n.doubleValue() : 0.0;
            cell.setCellValue(num);
            cell.setCellStyle(currencyStyle);
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

    /** Encabezado gris para hojas de solo lectura (Sedes y Grupos, Planes) — distingue "no editable" del azul oscuro de las hojas de trabajo. */
    private CellStyle crearEstiloEncabezadoBloqueado(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /** Celda gris clara para el contenido de las hojas de solo lectura. */
    private CellStyle crearEstiloCeldaBloqueada(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Texto gris/cursiva para notas e instrucciones. */
    private CellStyle crearEstiloNota(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setWrapText(true);
        return style;
    }

    private void crearEncabezados(Sheet sheet, CellStyle headerStyle, ColumnasPlantilla columnas) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columnas.headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columnas.headers.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    private void construirHojaClientes(Sheet sheet, List<Parent> padres, ClubConfig config, ColumnasPlantilla columnas,
                                        CellStyle headerStyle, CellStyle dateStyle, CellStyle currencyStyle, Set<Long> sedesPermitidas) {
        ClubConfig.EsquemaCobro esquema = columnas.esquema;
        crearEncabezados(sheet, headerStyle, columnas);
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
            // MENSUALIDAD se resume como Sí/No; PAQUETE usa clasesDisponibles por deportista (ver más abajo).
            Object valorMensualidad = (debeTotal.compareTo(BigDecimal.ZERO) > 0) ? "Sí" : "No";

            if (parent.getStudents() != null && !parent.getStudents().isEmpty()) {
                for (Student s : parent.getStudents()) {
                    String nombreDep = s.getNombreCompleto() != null ? s.getNombreCompleto() : "";
                    LocalDate fechaNac = s.getFechaNacimiento();
                    Object valorFinal = valorUltimaColumnaExport(esquema, debeTotal, valorMensualidad, s);

                    // Un EMPLEADO (entrenador) solo ve las matrículas de sus sedes autorizadas; si el
                    // deportista no tiene ninguna matrícula en esas sedes, se omite por completo.
                    List<Enrollment> matriculasVisibles = s.getMatriculas() == null ? List.of()
                            : s.getMatriculas().stream()
                                .filter(m -> sedesPermitidas == null || (m.getSede() != null && sedesPermitidas.contains(m.getSede().getId())))
                                .toList();
                    if (sedesPermitidas != null && matriculasVisibles.isEmpty()) {
                        continue;
                    }

                    if (!matriculasVisibles.isEmpty()) {
                        for (Enrollment m : matriculasVisibles) {
                            String nomSede = (m.getSede() != null) ? m.getSede().getNombre() : "";
                            String nomNivel = (m.getNivel() != null) ? m.getNivel() : "";
                            String nomPlan = (m.getPlanMensualidad() != null && m.getPlanMensualidad().getNombre() != null)
                                    ? m.getPlanMensualidad().getNombre() : "";
                            String esPrincipalTxt = Boolean.TRUE.equals(m.getEsPrincipal()) ? "Sí" : "No";

                            Row row = sheet.createRow(r++);
                            row.createCell(columnas.idxAcudiente).setCellValue(nombreParent);
                            row.createCell(columnas.idxTelefono).setCellValue(telParent);
                            row.createCell(columnas.idxDeportista).setCellValue(nombreDep);

                            Cell cFecha = row.createCell(columnas.idxFecha);
                            if (fechaNac != null) {
                                cFecha.setCellValue(java.sql.Date.valueOf(fechaNac));
                            } else {
                                cFecha.setCellValue("");
                            }
                            cFecha.setCellStyle(dateStyle);

                            row.createCell(columnas.idxSede).setCellValue(nomSede);
                            row.createCell(columnas.idxGrupo).setCellValue(nomNivel);
                            row.createCell(columnas.idxPlan).setCellValue(nomPlan);
                            row.createCell(columnas.idxSedePrincipal).setCellValue(esPrincipalTxt);

                            escribirColumnasMatriculaSeguro(row, columnas, s, parent);
                            escribirColumnaComplementos(row, columnas, s, parent);

                            Cell cSaldo = row.createCell(columnas.idxAbono);
                            cSaldo.setCellValue(saldo.doubleValue());
                            cSaldo.setCellStyle(currencyStyle);

                            escribirCeldaUltimaColumna(row.createCell(columnas.idxUltima), esquema, valorFinal, currencyStyle);
                        }
                    } else {
                        Row row = sheet.createRow(r++);
                        row.createCell(columnas.idxAcudiente).setCellValue(nombreParent);
                        row.createCell(columnas.idxTelefono).setCellValue(telParent);
                        row.createCell(columnas.idxDeportista).setCellValue(nombreDep);

                        Cell cFecha = row.createCell(columnas.idxFecha);
                        if (fechaNac != null) {
                            cFecha.setCellValue(java.sql.Date.valueOf(fechaNac));
                        } else {
                            cFecha.setCellValue("");
                        }
                        cFecha.setCellStyle(dateStyle);

                        row.createCell(columnas.idxSede).setCellValue("");
                        row.createCell(columnas.idxGrupo).setCellValue("");
                        row.createCell(columnas.idxPlan).setCellValue("");
                        row.createCell(columnas.idxSedePrincipal).setCellValue("");

                        escribirColumnasMatriculaSeguro(row, columnas, s, parent);
                        escribirColumnaComplementos(row, columnas, s, parent);

                        Cell cSaldo = row.createCell(columnas.idxAbono);
                        cSaldo.setCellValue(saldo.doubleValue());
                        cSaldo.setCellStyle(currencyStyle);

                        escribirCeldaUltimaColumna(row.createCell(columnas.idxUltima), esquema, valorFinal, currencyStyle);
                    }
                }
            } else if (sedesPermitidas == null) {
                // Acudiente sin deportistas: solo tiene sentido mostrarlo en la vista completa del ADMIN.
                Row row = sheet.createRow(r++);
                row.createCell(columnas.idxAcudiente).setCellValue(nombreParent);
                row.createCell(columnas.idxTelefono).setCellValue(telParent);
                row.createCell(columnas.idxDeportista).setCellValue("");

                Cell cFecha = row.createCell(columnas.idxFecha);
                cFecha.setCellValue("");
                cFecha.setCellStyle(dateStyle);

                row.createCell(columnas.idxSede).setCellValue("");
                row.createCell(columnas.idxGrupo).setCellValue("");
                row.createCell(columnas.idxPlan).setCellValue("");
                row.createCell(columnas.idxSedePrincipal).setCellValue("");

                Cell cSaldo = row.createCell(columnas.idxAbono);
                cSaldo.setCellValue(saldo.doubleValue());
                cSaldo.setCellStyle(currencyStyle);

                Object valorFinal = valorUltimaColumnaExport(esquema, debeTotal, valorMensualidad, null);
                escribirCeldaUltimaColumna(row.createCell(columnas.idxUltima), esquema, valorFinal, currencyStyle);
            }
        }

        autoajustarColumnas(sheet, columnas.headers.size());
    }

    /**
     * Escribe un resumen de los Complementos activos del deportista (Gym Virtual, Pista
     * Adicional, etc.): nombre, precio mensual y si el cargo del mes actual sigue pendiente o
     * ya se pagó — mismo criterio que Matrícula/Seguro (el cargo CARGO_EXTRA del mes en curso
     * sigue existiendo mientras no se pague). Ejemplo de celda:
     * "Gym Virtual: $20.000 (Pagado); Pista Adicional: $15.000 (Debe $15.000)".
     */
    private void escribirColumnaComplementos(Row row, ColumnasPlantilla columnas, Student s, Parent parent) {
        if (columnas.idxComplementos < 0) return;

        List<com.asistencia.erp.entity.StudentComplemento> asignaciones =
            studentComplementoRepository.findActivosByStudentId(s.getId());

        String resumen;
        if (asignaciones.isEmpty()) {
            resumen = "";
        } else {
            String mesAnio = monthlyBillingService.mesActualEnEspanol() + " " + LocalDateTime.now().getYear();
            resumen = asignaciones.stream().map(asignacion -> {
                String nombre = asignacion.getComplemento().getNombre();
                BigDecimal precio = monthlyBillingService.resolverPrecioComplemento(asignacion);
                String concepto = nombre + " - " + mesAnio + " - " + s.getNombreCompleto();
                boolean debe = !financialLogRepository
                    .findByParentIdAndTipoMovimientoAndConceptoContaining(parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, concepto)
                    .isEmpty();
                String precioTxt = precio != null ? "$" + precio.toBigInteger().toString() : "$0";
                String estado = precio == null || precio.compareTo(BigDecimal.ZERO) <= 0
                    ? "Sin costo" : (debe ? "Debe " + precioTxt : "Pagado");
                return nombre + ": " + precioTxt + " (" + estado + ")";
            }).collect(java.util.stream.Collectors.joining("; "));
        }
        row.createCell(columnas.idxComplementos).setCellValue(resumen);
    }

    /** Escribe Matrícula/Seguro (Activo y Pagado) en un deportista exportado, solo si el club los tiene como opcionales. */
    private void escribirColumnasMatriculaSeguro(Row row, ColumnasPlantilla columnas, Student s, Parent parent) {
        if (columnas.idxMatriculaActiva >= 0) {
            boolean activa = Boolean.TRUE.equals(s.getAdquiereMatricula());
            row.createCell(columnas.idxMatriculaActiva).setCellValue(activa ? "Sí" : "No");
            boolean pagada = activa && financialLogRepository.findByParentIdAndTipoMovimientoAndConceptoContaining(
                    parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, "Matrícula Anual - " + s.getNombreCompleto()).isEmpty();
            row.createCell(columnas.idxMatriculaPagada).setCellValue(activa ? (pagada ? "Sí" : "No") : "");
        }
        if (columnas.idxSeguroActiva >= 0) {
            boolean activo = Boolean.TRUE.equals(s.getAdquiereSeguro());
            row.createCell(columnas.idxSeguroActiva).setCellValue(activo ? "Sí" : "No");
            boolean pagado = activo && financialLogRepository.findByParentIdAndTipoMovimientoAndConceptoContaining(
                    parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, "Seguro Deportivo - " + s.getNombreCompleto()).isEmpty();
            row.createCell(columnas.idxSeguroPagada).setCellValue(activo ? (pagado ? "Sí" : "No") : "");
        }
    }

    /** Determina el valor a exportar en la última columna según esquema y (si aplica) el deportista. */
    private Object valorUltimaColumnaExport(ClubConfig.EsquemaCobro esquema, BigDecimal debeTotal, Object valorMensualidad, Student student) {
        if (esquema == ClubConfig.EsquemaCobro.PAQUETE) {
            int clases = (student != null && student.getClasesDisponibles() != null) ? student.getClasesDisponibles() : 0;
            return (double) clases;
        }
        if (esquema == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            return valorMensualidad;
        }
        return debeTotal.doubleValue();
    }

    private void autoajustarColumnas(Sheet sheet, int totalColumnas) {
        for (int i = 0; i < totalColumnas; i++) {
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
                } else if (val.contains("principal")) {
                    // "Sede Principal (Sí/No)" — debe evaluarse ANTES que el chequeo genérico de "sede".
                    map.put("sedeprincipal", i);
                } else if (val.contains("sede")) {
                    map.put("sede", i);
                } else if (val.contains("nivel") || val.contains("grupo") || val.contains("categoría")) {
                    map.put("nivel", i);
                } else if ((val.contains("matrícula") || val.contains("matricula")) && val.contains("pagad")) {
                    // "Matrícula Pagada" — debe evaluarse ANTES que el chequeo genérico de matrícula.
                    map.put("matriculapagada", i);
                } else if (val.contains("matrícula") || val.contains("matricula")) {
                    map.put("matriculaactiva", i);
                } else if (val.contains("seguro") && val.contains("pagad")) {
                    // "Seguro Pagado" — debe evaluarse ANTES que el chequeo genérico de seguro.
                    map.put("seguropagado", i);
                } else if (val.contains("seguro")) {
                    map.put("seguroactivo", i);
                } else if (val.contains("plan")) {
                    map.put("plan", i);
                } else if (val.contains("saldo") || val.contains("abono")) {
                    map.put("saldo", i);
                } else if (val.contains("mensualidad") && val.contains("pendiente")) {
                    map.put("mensualidadPendiente", i);
                } else if (val.contains("clases") && val.contains("disponible")) {
                    map.put("clasesDisponibles", i);
                } else if (val.contains("debe") || val.contains("deuda")) {
                    map.put("debe", i);
                }
            }
        }
        return map;
    }

    /**
     * Busca una sede YA EXISTENTE del club a partir del texto libre de la celda — nunca crea una
     * nueva. Tolera el formato legado "Sede 10 (Sede Principal Iniciación)" probando primero el
     * contenido entre paréntesis y luego lo de afuera. Si nada coincide, devuelve null (fila inválida).
     */
    private Sede buscarSedePorTexto(String rawSedeText) {
        if (rawSedeText == null || rawSedeText.isBlank()) return null;

        String cleanText = rawSedeText.trim();
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
        if (match != null) return match;

        String norm = cleanText.replaceAll("(?i)^sede\\s*\\d*\\s*", "").trim();
        if (!norm.isBlank()) {
            Sede matchNorm = buscarSedeExistente(norm);
            if (matchNorm != null) return matchNorm;
        }
        return null;
    }

    /**
     * Busca, dentro de los grupos YA EXISTENTES de una sede, el que coincide con el texto libre de
     * la celda — nunca crea uno nuevo. Insensible a emoji ("🏋️Avanzado" == "avanzado"), tildes y
     * mayúsculas/minúsculas.
     */
    private GrupoSede buscarGrupoEnSede(Sede sede, String rawNivelText) {
        if (sede == null || sede.getGrupos() == null || rawNivelText == null || rawNivelText.isBlank()) return null;
        String nivelSinEmoji = toTitleCase(extraerTextoSinEmoji(rawNivelText.trim()));
        String targetNorm = normalizarTexto(nivelSinEmoji);
        return sede.getGrupos().stream()
                .filter(g -> g != null && g.getNombre() != null &&
                        (normalizarTexto(g.getNombre()).equals(targetNorm) ||
                         normalizarTexto(extraerTextoSinEmoji(g.getNombre())).equals(targetNorm)))
                .findFirst().orElse(null);
    }

    private Sede buscarSedeExistente(String text) {
        if (text == null || text.isBlank()) return null;
        String targetNorm = normalizarTexto(text);
        Long currentClubId = SecurityUtils.getClubId();
        List<Sede> sedesDelClub = (currentClubId != null) 
                ? sedeRepository.findByClubId(currentClubId) 
                : sedeRepository.findAll();

        for (Sede s : sedesDelClub) {
            if (s.getNombre() == null) continue;
            String sNorm = normalizarTexto(s.getNombre());

            if (sNorm.equals(targetNorm)) {
                return s;
            }

            String sClean = sNorm.replaceAll("(?i)^sede\\s*", "").trim();
            String tClean = targetNorm.replaceAll("(?i)^sede\\s*", "").trim();
            if (!sClean.isBlank() && !tClean.isBlank() && sClean.equals(tClean)) {
                return s;
            }
        }
        return null;
    }

    /** Busca, dentro del catálogo de planes de una sede, el que coincide por nombre (insensible a tildes/mayúsculas). */
    private PlanMensualidad buscarPlanEnSede(Sede sede, String nombrePlanRaw) {
        if (sede == null || nombrePlanRaw == null || nombrePlanRaw.isBlank()) return null;
        String targetNorm = normalizarTexto(nombrePlanRaw);
        return planMensualidadRepository.findBySedeId(sede.getId()).stream()
                .filter(p -> p.getNombre() != null && normalizarTexto(p.getNombre()).equals(targetNorm))
                .findFirst().orElse(null);
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
