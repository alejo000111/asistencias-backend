package com.asistencia.erp.controller;

import com.asistencia.erp.dto.ClaseNominaDTO;
import com.asistencia.erp.dto.NominaDTO;
import com.asistencia.erp.security.SecurityUtils;
import com.asistencia.erp.service.NominaExportService;
import com.asistencia.erp.service.NominaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * FASE 4 — Nómina de entrenadores/empleados. Solo accesible por el ADMIN del club.
 */
@RestController
@RequestMapping("/api/nomina")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class NominaController {

    private final NominaService nominaService;
    private final NominaExportService nominaExportService;

    @GetMapping("/resumen")
    public ResponseEntity<List<NominaDTO>> obtenerResumen(
            @RequestParam(required = false) String fechaDesde,
            @RequestParam(required = false) String fechaHasta,
            @RequestParam(required = false) Long empleadoId,
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String estadoPago) {
        return ResponseEntity.ok(nominaService.calcularNomina(
                SecurityUtils.getClubId(), fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago));
    }

    /** FASE 4.2 — Lista de clases individuales para marcarlas una por una como pagadas. */
    @GetMapping("/clases")
    public ResponseEntity<List<ClaseNominaDTO>> listarClases(
            @RequestParam(required = false) String fechaDesde,
            @RequestParam(required = false) String fechaHasta,
            @RequestParam(required = false) Long empleadoId,
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String estadoPago) {
        return ResponseEntity.ok(nominaService.listarClases(
                SecurityUtils.getClubId(), fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago));
    }

    /**
     * FASE 4.3 — Marca (o desmarca) como pagada una sesión completa (puede ser más de una
     * asistencia si el empleado dictó varios grupos el mismo día en la misma sede).
     */
    @PatchMapping("/clases/pago")
    public ResponseEntity<?> marcarPagoSesion(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> idsRaw = (List<Object>) body.get("attendanceIds");
        if (idsRaw == null || idsRaw.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "attendanceIds es requerido"));
        }
        List<Long> attendanceIds = idsRaw.stream().map(o -> Long.valueOf(o.toString())).toList();
        Object pagadoObj = body.get("pagado");
        boolean pagado = pagadoObj == null || Boolean.parseBoolean(pagadoObj.toString());

        LocalDate fechaPago = null;
        Object fechaPagoObj = body.get("fechaPago");
        if (pagado && fechaPagoObj != null && !fechaPagoObj.toString().isBlank()) {
            fechaPago = LocalDate.parse(fechaPagoObj.toString());
        } else if (pagado) {
            fechaPago = LocalDate.now();
        }
        Object metodoPagoObj = body.get("metodoPago");
        String metodoPago = pagado ? (metodoPagoObj != null && !metodoPagoObj.toString().isBlank() ? metodoPagoObj.toString() : "EFECTIVO") : null;

        int actualizadas = nominaService.marcarPagoSesion(SecurityUtils.getClubId(), attendanceIds, pagado, fechaPago, metodoPago);
        if (actualizadas == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No se encontraron las clases indicadas"));
        }
        return ResponseEntity.ok(Map.of("pagadoNomina", pagado, "clasesActualizadas", actualizadas));
    }

    @GetMapping("/exportar")
    public ResponseEntity<byte[]> exportarNomina(
            @RequestParam(required = false) String fechaDesde,
            @RequestParam(required = false) String fechaHasta,
            @RequestParam(required = false) Long empleadoId,
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String estadoPago) {
        try {
            byte[] bytes = nominaExportService.exportarNomina(
                    SecurityUtils.getClubId(), fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", "reporte_nomina.xlsx");
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
