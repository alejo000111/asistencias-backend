package com.asistencia.erp.service;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * FASE 3 — Servicio de exportación de planillas de asistencia en Excel.
 *
 * Genera un único .xlsx con las asistencias filtradas por sede, nivel y rango de fechas.
 * Cuando el resultado abarca varias sedes (sin filtrar por una sede puntual), cada sede
 * se pone en su propia hoja dentro del MISMO archivo — más fácil de revisar que un .zip
 * con un archivo suelto por sede. Dentro de cada hoja, los registros se organizan en tablas
 * separadas por día, y las columnas mostradas dependen del esquema de cobro del club
 * (MENSUALIDAD, PAQUETE o POR_CLASE), ya que a cada uno le interesan datos distintos.
 */
@Service
@RequiredArgsConstructor
public class AsistenciaExportService {

    private final AttendanceRepository attendanceRepository;
    private final ClubConfigRepository clubConfigRepository;

    private static final DateTimeFormatter DAY_TITLE_FMT = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy", new Locale("es", "ES"));

    @Getter
    @RequiredArgsConstructor
    public static class ExportResult {
        private final byte[] data;
        private final String filename;
        private final String contentType;
    }

    /**
     * Genera la exportación de asistencias, aplicando filtros opcionales.
     * Siempre es un único .xlsx; si el resultado abarca varias sedes (sin filtrar por
     * una sede puntual), cada sede va en su propia hoja dentro de ese mismo archivo.
     *
     * @param clubId      ID del club (obligatorio, aísla el tenant)
     * @param sedeId      Filtro por sede (opcional)
     * @param nivel       Filtro por nivel/grupo exacto (opcional)
     * @param fechaDesde  Fecha de inicio del rango (opcional, formato yyyy-MM-dd)
     * @param fechaHasta  Fecha de fin del rango (opcional, formato yyyy-MM-dd)
     */
    @Transactional(readOnly = true)
    public ExportResult exportarAsistencias(Long clubId, Long sedeId, String nivel, String fechaDesde, String fechaHasta) {
        LocalDateTime desde = (fechaDesde != null && !fechaDesde.isBlank())
                ? LocalDate.parse(fechaDesde).atStartOfDay()
                : null;
        LocalDateTime hasta = (fechaHasta != null && !fechaHasta.isBlank())
                ? LocalDate.parse(fechaHasta).atTime(LocalTime.MAX)
                : null;

        String nivelParam = (nivel != null && !nivel.isBlank()) ? nivel : null;

        List<Attendance> asistencias = attendanceRepository.findForExport(clubId, sedeId, nivelParam, desde, hasta);

        ClubConfig.EsquemaCobro esquema = clubConfigRepository.findByAdminId(clubId)
                .map(ClubConfig::getEsquemaCobro)
                .orElse(ClubConfig.EsquemaCobro.MENSUALIDAD);

        // Agrupar por sede: cada sede se exporta en su propia hoja del mismo libro.
        Map<String, List<Attendance>> porSede = new LinkedHashMap<>();
        asistencias.stream()
                .sorted(Comparator.comparing(Attendance::getFecha))
                .forEach(a -> {
                    String claveSede = a.getSede() != null ? a.getSede().getNombre() : "Sin sede";
                    porSede.computeIfAbsent(claveSede, k -> new ArrayList<>()).add(a);
                });
        if (porSede.isEmpty()) {
            porSede.put("Asistencias", List.of());
        }

        byte[] bytes = buildWorkbookBytes(porSede, esquema);

        String filename;
        if (porSede.size() == 1) {
            filename = "planilla_asistencias_" + slug(porSede.keySet().iterator().next()) + ".xlsx";
        } else {
            filename = "planilla_asistencias_todas_las_sedes.xlsx";
        }

        return new ExportResult(bytes, filename, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    // ── Construcción del archivo ──────────────────────────────────────────────────────────

    /** Un único workbook con una hoja por sede (los estilos se crean una sola vez y se reutilizan entre hojas). */
    private byte[] buildWorkbookBytes(Map<String, List<Attendance>> porSede, ClubConfig.EsquemaCobro esquema) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle titleStyle = buildTitleStyle(workbook);
            CellStyle dayHeaderStyle = buildDayHeaderStyle(workbook);
            CellStyle headerStyle = buildHeaderStyle(workbook);
            CellStyle normalStyle = buildDataStyle(workbook, RowTag.NINGUNO);
            CellStyle cortesiaStyle = buildDataStyle(workbook, RowTag.CORTESIA);
            CellStyle extraStyle = buildDataStyle(workbook, RowTag.EXTRA);

            Set<String> nombresHojaUsados = new HashSet<>();
            for (Map.Entry<String, List<Attendance>> entry : porSede.entrySet()) {
                Sheet sheet = workbook.createSheet(nombreHojaSeguro(entry.getKey(), nombresHojaUsados));
                escribirHojaSede(sheet, entry.getKey(), entry.getValue(), esquema,
                        titleStyle, dayHeaderStyle, headerStyle, normalStyle, cortesiaStyle, extraStyle);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error generando el archivo Excel de asistencias: " + e.getMessage(), e);
        }
    }

    private void escribirHojaSede(Sheet sheet, String nombreSede, List<Attendance> asistencias, ClubConfig.EsquemaCobro esquema,
                                   CellStyle titleStyle, CellStyle dayHeaderStyle, CellStyle headerStyle,
                                   CellStyle normalStyle, CellStyle cortesiaStyle, CellStyle extraStyle) {
        String[] columnas = columnasPara(esquema);
        int numCols = columnas.length;

        int rowNum = 0;

        // ── Título ──
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Planilla de Asistencias — " + nombreSede);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, numCols - 1));

        rowNum++; // fila en blanco

        // ── Agrupar por día (más reciente primero) ──
        Map<LocalDate, List<Attendance>> porDia = new TreeMap<>(Comparator.reverseOrder());
        for (Attendance a : asistencias) {
            if (a.getFecha() == null) continue;
            porDia.computeIfAbsent(a.getFecha().toLocalDate(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<LocalDate, List<Attendance>> diaEntry : porDia.entrySet()) {
            List<Attendance> delDia = diaEntry.getValue();
            delDia.sort(Comparator.comparing(Attendance::getFecha));

            // Encabezado del día
            Row dayRow = sheet.createRow(rowNum++);
            Cell dayCell = dayRow.createCell(0);
            String titulo = capitalize(diaEntry.getKey().format(DAY_TITLE_FMT)) + "  (" + delDia.size() + " registro" + (delDia.size() == 1 ? "" : "s") + ")";
            dayCell.setCellValue(titulo);
            dayCell.setCellStyle(dayHeaderStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, numCols - 1));

            // Encabezados de columnas
            Row headerRow = sheet.createRow(rowNum++);
            for (int i = 0; i < columnas.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columnas[i]);
                cell.setCellStyle(headerStyle);
            }

            // Filas de datos
            for (Attendance a : delDia) {
                Row row = sheet.createRow(rowNum++);
                CellStyle rowStyle = Boolean.TRUE.equals(a.getEsCortesia()) ? cortesiaStyle
                        : Boolean.TRUE.equals(a.getFueraDePlan()) ? extraStyle
                        : normalStyle;
                escribirFila(row, a, esquema, rowStyle);
            }

            rowNum++; // fila en blanco entre tablas de días
        }

        if (porDia.isEmpty()) {
            Row emptyRow = sheet.createRow(rowNum++);
            emptyRow.createCell(0).setCellValue("No hay registros de asistencia para los filtros seleccionados.");
        }

        // ── Anchos de columna ──
        int[] colWidths = anchosPara(esquema);
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i]);
        }

        // ── Total ──
        Row totalRow = sheet.createRow(rowNum + 1);
        totalRow.createCell(0).setCellValue("Total de registros: " + asistencias.size());
        sheet.addMergedRegion(new CellRangeAddress(rowNum + 1, rowNum + 1, 0, numCols - 1));
    }

    // ── Columnas por esquema de cobro ─────────────────────────────────────────────────────
    // "Tipo" (Grupal/Personalizada) solo tiene sentido en POR_CLASE — en Mensualidad y Paquete
    // todas las clases son grupales, así que la columna sobra ahí. "Evento" (Cortesía/Clase
    // Extra) y "Registrado por" sí aplican a los tres esquemas.

    private String[] columnasPara(ClubConfig.EsquemaCobro esquema) {
        if (esquema == ClubConfig.EsquemaCobro.POR_CLASE) {
            return new String[]{"Deportista / Prospecto", "Nivel / Grupo", "Tipo", "Evento", "Precio ($)", "Pagado", "Registrado por"};
        }
        return new String[]{"Deportista / Prospecto", "Nivel / Grupo", "Evento", "Registrado por"};
    }

    private int[] anchosPara(ClubConfig.EsquemaCobro esquema) {
        if (esquema == ClubConfig.EsquemaCobro.POR_CLASE) {
            return new int[]{7500, 5500, 4200, 4200, 3200, 2800, 5500};
        }
        return new int[]{7500, 5500, 4200, 5500};
    }

    private void escribirFila(Row row, Attendance a, ClubConfig.EsquemaCobro esquema, CellStyle rowStyle) {
        int col = 0;

        String nombre = a.getStudent() != null
                ? a.getStudent().getNombreCompleto()
                : (a.getNombreEstudianteHistorico() != null ? a.getNombreEstudianteHistorico() : "(sin nombre)");
        setCell(row, col++, nombre, rowStyle);

        setCell(row, col++, a.getNivel() != null ? a.getNivel() : "—", rowStyle);

        if (esquema == ClubConfig.EsquemaCobro.POR_CLASE) {
            setCell(row, col++, a.getTipoClase() != null ? a.getTipoClase() : "GRUPAL", rowStyle);
        }

        String evento = Boolean.TRUE.equals(a.getEsCortesia()) ? "🎟 Cortesía"
                : Boolean.TRUE.equals(a.getFueraDePlan()) ? "➕ Clase Extra"
                : "—";
        setCell(row, col++, evento, rowStyle);

        if (esquema == ClubConfig.EsquemaCobro.POR_CLASE) {
            Cell cPrecio = row.createCell(col++);
            cPrecio.setCellValue(a.getPrecioCobrado() != null ? a.getPrecioCobrado().doubleValue() : 0.0);
            cPrecio.setCellStyle(rowStyle);

            setCell(row, col++, Boolean.TRUE.equals(a.getClasePaga()) ? "Sí" : "No", rowStyle);
        }

        setCell(row, col++, a.getRegistradoPorNombre() != null ? a.getRegistradoPorNombre() : "—", rowStyle);
    }

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    // ── Helpers de estilos ────────────────────────────────────────────────────────────────

    private CellStyle buildTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        s.setFont(font);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle buildDayHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        return s;
    }

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(font);
        s.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    /** Resaltado de fila: cortesía (amarillo) y clase extra/fuera de plan (naranja) se marcan igual de visibles que en el historial. */
    private enum RowTag { NINGUNO, CORTESIA, EXTRA }

    private CellStyle buildDataStyle(Workbook wb, RowTag tag) {
        CellStyle s = wb.createCellStyle();
        if (tag == RowTag.CORTESIA) {
            s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = wb.createFont();
            font.setItalic(true);
            s.setFont(font);
        } else if (tag == RowTag.EXTRA) {
            s.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = wb.createFont();
            font.setItalic(true);
            s.setFont(font);
        }
        s.setBorderBottom(BorderStyle.HAIR);
        s.setBorderTop(BorderStyle.HAIR);
        s.setBorderLeft(BorderStyle.HAIR);
        s.setBorderRight(BorderStyle.HAIR);
        return s;
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Nombre de hoja válido para Excel: máx. 31 caracteres, sin [ ] : * ? / \ , y sin repetirse dentro del libro. */
    private String nombreHojaSeguro(String nombreSede, Set<String> usados) {
        String limpio = nombreSede == null ? "Asistencias" : nombreSede.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (limpio.isEmpty()) limpio = "Asistencias";
        if (limpio.length() > 31) limpio = limpio.substring(0, 31);

        String candidato = limpio;
        int sufijo = 2;
        while (!usados.add(candidato)) {
            String base = limpio.length() > 27 ? limpio.substring(0, 27) : limpio;
            candidato = base + " (" + sufijo + ")";
            sufijo++;
        }
        return candidato;
    }

    private String slug(String s) {
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
