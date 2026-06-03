package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicPortalController {

    private final ParentRepository parentRepository;
    private final FinancialLogRepository financialLogRepository;
    private final AttendanceRepository attendanceRepository;

    @GetMapping("/portal/{secretToken}")
    public ResponseEntity<Map<String, Object>> obtenerPortal(@PathVariable String secretToken) {
        Parent parent = parentRepository.findBySecretToken(secretToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token inválido"));

        List<Attendance> deudas = attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parent.getId());

        BigDecimal deudaTotal = BigDecimal.ZERO;
        if (deudas != null) {
            for (Attendance att : deudas) {
                deudaTotal = deudaTotal.add(att.getPrecioCobrado());
            }
        }

        List<FinancialLog> logs = financialLogRepository
                .findByParentIdAndTipoMovimientoOrderByFechaDesc(parent.getId(), FinancialLog.MovementType.INGRESO_ABONO)
                .stream()
                .limit(10)
                .toList();

        List<Attendance> ultimasClases = attendanceRepository.findTop3ByStudentParentIdOrderByFechaDesc(parent.getId());

        // Mapear a DTO simple para evitar ciclos JSON
        List<Map<String, Object>> ultimasClasesDTO = new ArrayList<>();
        if (ultimasClases != null) {
            for (Attendance att : ultimasClases) {
                Map<String, Object> item = new HashMap<>();
                item.put("fecha", att.getFecha());
                item.put("nivel", att.getNivel());
                item.put("nombreEstudiante",
                        att.getStudent() != null ? att.getStudent().getNombreCompleto()
                                : att.getNombreEstudianteHistorico());
                ultimasClasesDTO.add(item);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("parent", parent);
        response.put("deudaTotal", deudaTotal);
        response.put("financialLogs", logs);
        response.put("deudas", deudas);
        response.put("ultimasClases", ultimasClasesDTO);

        return ResponseEntity.ok(response);
    }
}
