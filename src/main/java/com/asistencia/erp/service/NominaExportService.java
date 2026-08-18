package com.asistencia.erp.service;

import com.asistencia.erp.dto.ClaseNominaDTO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FASE 4 — Exportación del reporte de nómina de entrenadores en Excel.
 *
 * Exporta el mismo nivel de detalle que ve el admin en la tabla de NominaView (una fila por
 * sesión/jornada pagable), respetando los filtros activos (fechas, entrenador, sede, estado de
 * pago), y agrega cuándo y con qué medio se pagó cada una, más un resumen de lo pendiente.
 */
@Service
@RequiredArgsConstructor
public class NominaExportService {

    private final NominaService nominaService;

    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] exportarNomina(Long clubId, String fechaDesde, String fechaHasta) {
        return exportarNomina(clubId, fechaDesde, fechaHasta, null, null, null);
    }

    public byte[] exportarNomina(Long clubId, String fechaDesde, String fechaHasta,
                                  Long empleadoId, Long sedeId, String estadoPago) {
        List<ClaseNominaDTO> clases = nominaService.listarClases(clubId, fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Nomina");

            CellStyle headerStyle = buildHeaderStyle(workbook);
            CellStyle dataStyle = buildDataStyle(workbook, false);
            CellStyle pendienteStyle = buildDataStyle(workbook, true);
            CellStyle titleStyle = buildTitleStyle(workbook);

            String[] headers = {"Fecha", "Sede", "Entrenador", "Nivel/Grupo", "Tipo", "Valor ($)", "Estado", "Fecha de Pago", "Medio de Pago"};
            int numCols = headers.length;

            int rowNum = 0;

            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Reporte de Nómina — ERP Asistencias");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, numCols - 1));

            Row periodoRow = sheet.createRow(rowNum++);
            Cell periodoCell = periodoRow.createCell(0);
            periodoCell.setCellValue("Periodo: " + (fechaDesde != null ? fechaDesde : "Inicio de mes") + " a " + (fechaHasta != null ? fechaHasta : "Hoy"));
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, numCols - 1));

            rowNum++; // fila en blanco

            Row headerRow = sheet.createRow(rowNum++);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            BigDecimal totalGeneral = BigDecimal.ZERO;
            BigDecimal totalPendiente = BigDecimal.ZERO;
            for (ClaseNominaDTO c : clases) {
                boolean pagada = Boolean.TRUE.equals(c.getPagadoNomina());
                CellStyle rowStyle = pagada ? dataStyle : pendienteStyle;
                Row row = sheet.createRow(rowNum++);
                int col = 0;

                setCell(row, col++, c.getFecha() != null ? c.getFecha().format(FECHA_FMT) : "—", rowStyle);
                setCell(row, col++, c.getSedeNombre() != null ? c.getSedeNombre() : "—", rowStyle);
                setCell(row, col++, c.getNombreEmpleado(), rowStyle);
                setCell(row, col++, c.getNiveles() != null && !c.getNiveles().isEmpty() ? String.join(", ", c.getNiveles()) : "—", rowStyle);
                setCell(row, col++, c.getTipoClase() != null ? c.getTipoClase() : "—", rowStyle);

                Cell cValor = row.createCell(col++);
                cValor.setCellValue(c.getTarifa() != null ? c.getTarifa().doubleValue() : 0.0);
                cValor.setCellStyle(rowStyle);

                setCell(row, col++, pagada ? "✅ Pagada" : "⏳ Pendiente", rowStyle);
                setCell(row, col++, pagada && c.getFechaPago() != null ? c.getFechaPago().format(FECHA_FMT) : "—", rowStyle);
                setCell(row, col++, pagada && c.getMetodoPago() != null ? formatearMedio(c.getMetodoPago()) : "—", rowStyle);

                BigDecimal valor = c.getTarifa() != null ? c.getTarifa() : BigDecimal.ZERO;
                totalGeneral = totalGeneral.add(valor);
                if (!pagada) totalPendiente = totalPendiente.add(valor);
            }

            if (clases.isEmpty()) {
                Row emptyRow = sheet.createRow(rowNum++);
                emptyRow.createCell(0).setCellValue("No hay clases registradas por empleados con estos filtros.");
            }

            rowNum++; // fila en blanco

            // ── Resumen: lista uno a uno de lo pendiente por pagar ──
            List<ClaseNominaDTO> pendientes = clases.stream().filter(c -> !Boolean.TRUE.equals(c.getPagadoNomina())).toList();
            if (!pendientes.isEmpty()) {
                Row pendTitleRow = sheet.createRow(rowNum++);
                Cell pendTitleCell = pendTitleRow.createCell(0);
                pendTitleCell.setCellValue("Pendientes por pagar (" + pendientes.size() + ")");
                pendTitleCell.setCellStyle(headerStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, numCols - 1));

                for (ClaseNominaDTO c : pendientes) {
                    Row row = sheet.createRow(rowNum++);
                    String linea = (c.getFecha() != null ? c.getFecha().format(FECHA_FMT) : "—") + " — " + c.getNombreEmpleado()
                            + (c.getSedeNombre() != null ? " (" + c.getSedeNombre() + ")" : "");
                    row.createCell(0).setCellValue(linea);
                    Cell cMonto = row.createCell(numCols - 2);
                    cMonto.setCellValue(c.getTarifa() != null ? c.getTarifa().doubleValue() : 0.0);
                }
            }

            rowNum++; // fila en blanco

            Row totalRow = sheet.createRow(rowNum++);
            Cell totalLabelCell = totalRow.createCell(0);
            totalLabelCell.setCellValue("Total general (pagado + pendiente):");
            totalLabelCell.setCellStyle(headerStyle);
            Cell totalValueCell = totalRow.createCell(numCols - 2);
            totalValueCell.setCellValue(totalGeneral.doubleValue());
            totalValueCell.setCellStyle(headerStyle);

            Row totalPendRow = sheet.createRow(rowNum);
            Cell totalPendLabelCell = totalPendRow.createCell(0);
            totalPendLabelCell.setCellValue("Total a pagar (pendiente):");
            totalPendLabelCell.setCellStyle(headerStyle);
            Cell totalPendValueCell = totalPendRow.createCell(numCols - 2);
            totalPendValueCell.setCellValue(totalPendiente.doubleValue());
            totalPendValueCell.setCellStyle(headerStyle);

            int[] colWidths = {3200, 5500, 6500, 5500, 3800, 3200, 3200, 3800, 4000};
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i]);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error generando el archivo Excel de nómina: " + e.getMessage(), e);
        }
    }

    private String formatearMedio(String metodoPago) {
        return switch (metodoPago) {
            case "EFECTIVO" -> "Efectivo";
            case "TRANSFERENCIA" -> "Transferencia";
            default -> metodoPago;
        };
    }

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

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

    private CellStyle buildDataStyle(Workbook wb, boolean pendiente) {
        CellStyle s = wb.createCellStyle();
        if (pendiente) {
            s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        s.setBorderBottom(BorderStyle.HAIR);
        s.setBorderTop(BorderStyle.HAIR);
        s.setBorderLeft(BorderStyle.HAIR);
        s.setBorderRight(BorderStyle.HAIR);
        return s;
    }
}
