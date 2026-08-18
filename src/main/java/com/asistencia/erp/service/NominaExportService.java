package com.asistencia.erp.service;

import com.asistencia.erp.dto.NominaDTO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * FASE 4 — Exportación del reporte de nómina de entrenadores en Excel.
 */
@Service
@RequiredArgsConstructor
public class NominaExportService {

    private final NominaService nominaService;

    public byte[] exportarNomina(Long clubId, String fechaDesde, String fechaHasta) {
        return exportarNomina(clubId, fechaDesde, fechaHasta, null, null, null);
    }

    public byte[] exportarNomina(Long clubId, String fechaDesde, String fechaHasta,
                                  Long empleadoId, Long sedeId, String estadoPago) {
        List<NominaDTO> nomina = nominaService.calcularNomina(clubId, fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Nomina");

            CellStyle headerStyle = buildHeaderStyle(workbook);
            CellStyle dataStyle = buildDataStyle(workbook);
            CellStyle titleStyle = buildTitleStyle(workbook);

            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Reporte de Nómina — ERP Asistencias");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

            Row filtrosRow = sheet.createRow(1);
            Cell filtroCell = filtrosRow.createCell(0);
            filtroCell.setCellValue("Periodo: " + (fechaDesde != null ? fechaDesde : "Inicio de mes") + " a " + (fechaHasta != null ? fechaHasta : "Hoy"));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 4));

            Row headerRow = sheet.createRow(3);
            String[] headers = {"Empleado", "Tipo Tarifa", "Tarifa ($)", "Clases Dictadas", "Total a Pagar ($)"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 4;
            BigDecimal granTotal = BigDecimal.ZERO;
            for (NominaDTO n : nomina) {
                Row row = sheet.createRow(rowNum++);

                Cell cNombre = row.createCell(0);
                cNombre.setCellValue(n.getNombreEmpleado());
                cNombre.setCellStyle(dataStyle);

                Cell cTipo = row.createCell(1);
                cTipo.setCellValue(n.getTipoTarifa().equals("POR_HORA") ? "Por Hora" : "Por Clase");
                cTipo.setCellStyle(dataStyle);

                Cell cTarifa = row.createCell(2);
                cTarifa.setCellValue(n.getTarifa() != null ? n.getTarifa().doubleValue() : 0.0);
                cTarifa.setCellStyle(dataStyle);

                Cell cClases = row.createCell(3);
                cClases.setCellValue(n.getCantidadClases());
                cClases.setCellStyle(dataStyle);

                Cell cTotal = row.createCell(4);
                cTotal.setCellValue(n.getTotalPagar() != null ? n.getTotalPagar().doubleValue() : 0.0);
                cTotal.setCellStyle(dataStyle);

                granTotal = granTotal.add(n.getTotalPagar() != null ? n.getTotalPagar() : BigDecimal.ZERO);
            }

            int[] colWidths = {7000, 3500, 3500, 4000, 4500};
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i]);
            }

            Row totalRow = sheet.createRow(rowNum + 1);
            Cell totalLabelCell = totalRow.createCell(0);
            totalLabelCell.setCellValue("Total general a pagar:");
            totalLabelCell.setCellStyle(headerStyle);
            Cell totalValueCell = totalRow.createCell(4);
            totalValueCell.setCellValue(granTotal.doubleValue());
            totalValueCell.setCellStyle(headerStyle);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error generando el archivo Excel de nómina: " + e.getMessage(), e);
        }
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

    private CellStyle buildDataStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.HAIR);
        s.setBorderTop(BorderStyle.HAIR);
        s.setBorderLeft(BorderStyle.HAIR);
        s.setBorderRight(BorderStyle.HAIR);
        return s;
    }
}
