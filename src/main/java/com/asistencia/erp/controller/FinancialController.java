package com.asistencia.erp.controller;

import com.asistencia.erp.dto.AttendanceDTO;
import com.asistencia.erp.dto.FinancialLogDTO;
import com.asistencia.erp.dto.ParentSummaryDTO;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import com.asistencia.erp.service.FinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static com.asistencia.erp.security.SecurityUtils.*;

@RestController
@RequestMapping("/api/finanzas")
@RequiredArgsConstructor
public class FinancialController {

    private final FinancialService financialService;
    private final ParentRepository parentRepository;
    private final FinancialLogRepository financialLogRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;

    private ResponseEntity<?> verificarAccesoEmpleadoAPadre(Long parentId) {
        if (!isEmpleado()) return null;
        List<Long> sedes = getSedesAutorizadas();
        if (sedes.isEmpty()) return ResponseEntity.status(403).body("Acceso denegado");

        // PERF-N1-03: UNA consulta SQL (COUNT con JOIN) en lugar de cargar todo el grafo en memoria
        boolean tieneAcceso = parentRepository.existsParentWithAccess(parentId, sedes);

        if (!tieneAcceso) {
            return ResponseEntity.status(403).body("Acceso denegado a los datos de este padre");
        }
        return null;
    }

    //*************************************
    //PUERTA 1: URL para registrar asistencias
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/asistencia
    //*************************************
    @PostMapping("/asistencia")
    public ResponseEntity<?> registrarAsistencia(
            @RequestParam Long studentId,
            @RequestParam String tipoClase,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String fecha,
            @RequestParam(required = false) BigDecimal precioPersonalizado,
            @RequestParam(required = false) Long sedeId) {

        // Si es EMPLEADO: validar que la sede esté en sus autorizadas y esté activa
        if (SecurityUtils.isEmpleado()) {
            if (sedeId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Debe especificar una sede autorizada");
            }
            List<Long> sedesAutorizadas = SecurityUtils.getSedesAutorizadas();
            if (!sedesAutorizadas.contains(sedeId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No tiene permisos para registrar asistencias en esta sede");
            }
            Sede sede = sedeRepository.findById(sedeId).orElse(null);
            if (sede == null || Boolean.FALSE.equals(sede.getActiva())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("La sede actual está inactiva");
            }
        }

        financialService.registrarAsistencia(
                studentId,
                FinancialService.TipoClase.valueOf(tipoClase),
                nivel,
                fecha,
                precioPersonalizado,
                sedeId
        );
        return ResponseEntity.ok("Asistencia registrada");
    }

    //*************************************
    //PUERTA 2: URL para registrar abono de dinero
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/abono
    //*************************************
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/abono")
    public ResponseEntity<?> registrarAbono(
            @RequestParam Long parentId,
            @RequestParam BigDecimal monto,
            @RequestParam String metodoPago,
            @RequestParam(required = false) String fecha) {

        java.time.LocalDate fechaAbono = (fecha != null && !fecha.isEmpty())
                ? java.time.LocalDate.parse(fecha)
                : java.time.LocalDate.now();

        financialService.registrarAbono(parentId, monto, FinancialLog.PaymentMethod.valueOf(metodoPago), fechaAbono);
        return ResponseEntity.ok("Abono registrado con éxito");
    }

    @GetMapping("/historial")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> obtenerHistorialCaja() {
        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<FinancialLogDTO> dtos = financialLogRepository.findAll().stream()
                .map(FinancialLogDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/historial/{parentId}")
    public ResponseEntity<?> obtenerHistorialPorPadre(@PathVariable Long parentId) {
        // EMPLEADO: validar que el padre pertenezca a sus sedes autorizadas
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<FinancialLogDTO> historialPadre = financialLogRepository
                .findByParentIdOrderByFechaDesc(parentId)
                .stream()
                .limit(10)
                .map(FinancialLogDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(historialPadre);
    }

    //*************************************
    //PUERTA 3: URL para ver todos los padres y sus saldos
    //METODO: GET
    //Ruta final: http://localhost:8080/api/finanzas/padres
    @GetMapping("/padres")
    public List<ParentSummaryDTO> obtenerPadres() {
        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<Parent> todos = parentRepository.findAll();
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todos.stream()
                    .filter(p -> parentRepository.existsParentWithAccess(p.getId(), sedes))
                    .map(ParentSummaryDTO::fromEntity)
                    .collect(Collectors.toList());
        }
        return todos.stream()
                .map(ParentSummaryDTO::fromEntity)
                .collect(Collectors.toList());
    }

    //*************************************
    //PUERTA 4: URL para ver el historial completo de asistencias
    //METODO: GET
    //*************************************
    @Transactional(readOnly = true)
    @GetMapping("/historial-asistencias")
    public ResponseEntity<?> obtenerTodasLasAsistencias() {
        List<Attendance> asistencias;
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            if (sedes.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            asistencias = attendanceRepository.findBySedeIdIn(sedes);
        } else {
            asistencias = attendanceRepository.findAllWithFetch();
        }
        List<AttendanceDTO> dtos = asistencias.stream()
                .map(AttendanceDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    //*************************************
    //PUERTA 5: URL para eliminar una asistencia
    //METODO: DELETE
    //*************************************
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/asistencia/{id}")
    public ResponseEntity<?> eliminarAsistencia(@PathVariable Long id) {
        // Verificar que la asistencia existe antes de eliminar (SEC-BOLA-01)
        if (!attendanceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        attendanceRepository.deleteById(id);
        return ResponseEntity.ok("Asistencia eliminada");
    }

    //*************************************
    //PUERTA 6: URL para ver las deudas de un papá específico
    //METODO: GET
    //*************************************
    @GetMapping("/deudas/{parentId}")
    public ResponseEntity<?> obtenerDeudasPadre(@PathVariable Long parentId) {
        // EMPLEADO: validar que el padre pertenezca a sus sedes autorizadas
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<AttendanceDTO> deudas = attendanceRepository
                .findUnpaidByParentIdOrderByFechaDesc(parentId)
                .stream()
                .map(AttendanceDTO::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(deudas);
    }

    //*************************************
    //PUERTA 7: URL para eliminar un abono/ingreso equivocado
    //METODO: DELETE
    //Regla: Resta el monto del saldoAbono del Parent y re-ejecuta FIFO
    //*************************************
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/abono/{id}")
    public ResponseEntity<?> eliminarAbono(@PathVariable Long id) {
        try {
            financialService.eliminarAbono(id);
            return ResponseEntity.ok("Abono eliminado correctamente");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @DeleteMapping("/deportista/{id}")
    public ResponseEntity<?> eliminarDeportista(@PathVariable Long id) {
        try {
            Student student = studentRepository.findById(id).orElse(null);
            if (student == null) return ResponseEntity.notFound().build();

            List<Attendance> asistencias = attendanceRepository.findByStudentId(id);
            for (Attendance a : asistencias) {
                a.setNombreEstudianteHistorico(student.getNombreCompleto());
                a.setStudent(null);
            }
            attendanceRepository.saveAll(asistencias);

            studentRepository.delete(student);
            return ResponseEntity.ok("Eliminado");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
