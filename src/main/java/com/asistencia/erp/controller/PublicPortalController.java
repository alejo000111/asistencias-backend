package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.GrupoSede;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
    private final SedeRepository sedeRepository;

    @GetMapping("/portal/{secretToken}")
    @Transactional(readOnly = true)
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

        // Últimas clases asistidas (pagas o no). Sin límite para que el fallback de niveles
        // pueda cubrir a todos los estudiantes del padre. El frontend ya hace .slice(0, 3) para mostrar.
        List<Attendance> ultimasClases = attendanceRepository.findRecentByParentId(parent.getId());

        // Construir el DTO del padre explícitamente como Map para:
        // 1) Evitar problemas de lazy loading / serialización de proxies JPA
        // 2) Excluir información sensible de sedes
        // 3) Preservar los niveles activos de cada deportista
        Map<String, Object> parentDto = new HashMap<>();
        parentDto.put("id", parent.getId());
        parentDto.put("nombreCompleto", parent.getNombreCompleto());
        parentDto.put("telefono", parent.getTelefono());
        parentDto.put("estado", parent.getEstado());
        parentDto.put("saldoAbono", parent.getSaldoAbono());
        parentDto.put("secretToken", parent.getSecretToken());

        // Construir un mapa de respaldo: para cada estudiante, extraer el nivel de la
        // asistencia o deuda más reciente (cuando no hay matrículas activas en la BD)
        Map<Long, String> nivelFallbackPorEstudiante = new HashMap<>();
        for (Attendance att : ultimasClases) {
            if (att.getStudent() != null && att.getNivel() != null) {
                nivelFallbackPorEstudiante.putIfAbsent(att.getStudent().getId(), att.getNivel());
            }
        }
        for (Attendance att : deudas) {
            if (att.getStudent() != null && att.getNivel() != null) {
                nivelFallbackPorEstudiante.putIfAbsent(att.getStudent().getId(), att.getNivel());
            }
        }

        // Construir la lista de estudiantes con sus niveles (sin información de sedes)
        List<Map<String, Object>> estudiantesDto = new ArrayList<>();
        if (parent.getStudents() != null) {
            for (Student s : parent.getStudents()) {
                Map<String, Object> estDto = new HashMap<>();
                estDto.put("id", s.getId());
                estDto.put("nombreCompleto", s.getNombreCompleto());
                estDto.put("edad", s.getEdad());
                estDto.put("fechaNacimiento", s.getFechaNacimiento());
                estDto.put("estado", s.getEstado());

                // Construir matrículas con nivel intacto pero sede oculta
                List<Map<String, Object>> matriculasDto = new ArrayList<>();
                if (s.getMatriculas() != null) {
                    for (Enrollment m : s.getMatriculas()) {
                        Map<String, Object> matDto = new HashMap<>();
                        matDto.put("id", m.getId());
                        matDto.put("nivel", m.getNivel());
                        matDto.put("sede", null); // No exponer datos de sede
                        matriculasDto.add(matDto);
                    }
                }

                // Fallback: si el estudiante no tiene matrículas activas, tomar el nivel
                // de su clase más reciente (disponible en deudas o últimas asistencias)
                if (matriculasDto.isEmpty()) {
                    String nivelDesdeAsistencia = nivelFallbackPorEstudiante.get(s.getId());
                    if (nivelDesdeAsistencia != null) {
                        Map<String, Object> matDto = new HashMap<>();
                        matDto.put("id", 0);
                        matDto.put("nivel", nivelDesdeAsistencia);
                        matDto.put("sede", null);
                        matriculasDto.add(matDto);
                    }
                }

                estDto.put("matriculas", matriculasDto);
                estudiantesDto.add(estDto);
            }
        }
        parentDto.put("students", estudiantesDto);

        // Construir diccionario de estilos de grupos desde todas las sedes activas
        // (nombre -> {emoji, colorHex}) para que el frontend pueda aplicar colores y emojis
        // sin depender del objeto sede (que se limpia por seguridad)
        // PERF-N1-02: findAllActivasWithGrupos() con LEFT JOIN FETCH elimina N+1 residual
        List<Sede> sedes = sedeRepository.findAllActivasWithGrupos();
        Map<String, Map<String, String>> estilosGrupos = new HashMap<>();
        for (Sede sede : sedes) {
            if (Boolean.TRUE.equals(sede.getActiva()) && sede.getGrupos() != null) {
                for (GrupoSede grupo : sede.getGrupos()) {
                    if (grupo.getNombre() != null && !estilosGrupos.containsKey(grupo.getNombre())) {
                        Map<String, String> estilo = new HashMap<>();
                        estilo.put("emoji", grupo.getEmoji());
                        estilo.put("colorHex", grupo.getColorHex());
                        estilosGrupos.put(grupo.getNombre(), estilo);
                    }
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("parent", parentDto);
        response.put("deudaTotal", deudaTotal);
        response.put("financialLogs", logs);
        response.put("deudas", deudas);
        response.put("ultimasClases", ultimasClases);
        response.put("estilosGrupos", estilosGrupos);

        return ResponseEntity.ok(response);
    }
}
