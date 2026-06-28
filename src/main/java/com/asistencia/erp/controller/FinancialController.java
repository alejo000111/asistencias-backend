package com.asistencia.erp.controller;

import com.asistencia.erp.dto.AttendanceDTO;
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

        boolean tieneAcceso = parentRepository.findById(parentId)
                .map(parent -> parent.getStudents() != null
                        && parent.getStudents().stream()
                                .flatMap(s -> s.getMatriculas().stream())
                                .anyMatch(m -> m.getSede() != null && sedes.contains(m.getSede().getId())))
                .orElse(false);

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

        // Seguridad: si es EMPLEADO y la sede está inactiva, bloquear
        if (SecurityUtils.isEmpleado() && sedeId != null) {
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
    public ResponseEntity<?> obtenerHistorialCaja() {
        return ResponseEntity.ok(financialLogRepository.findAll());
    }

    @GetMapping("/historial/{parentId}")
    public ResponseEntity<?> obtenerHistorialPorPadre(@PathVariable Long parentId) {
        // EMPLEADO: validar que el padre pertenezca a sus sedes autorizadas
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        List<FinancialLog> historialPadre = financialLogRepository
                .findByParentIdOrderByFechaDesc(parentId)
                .stream()
                .limit(10)
                .toList();

        return ResponseEntity.ok(historialPadre);
    }

    //*************************************
    //PUERTA 3: URL para ver todos los padres y sus saldos
    //METODO: GET
    //Ruta final: http://localhost:8080/api/finanzas/padres
    @GetMapping("/padres")
    public List<Parent> obtenerPadres() {
        List<Parent> todos = parentRepository.findAll();
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todos.stream()
                    .filter(p -> p.getStudents() != null &&
                            p.getStudents().stream().anyMatch(s ->
                                    s.getMatriculas() != null &&
                                    s.getMatriculas().stream()
                                            .anyMatch(m -> m.getSede() != null && sedes.contains(m.getSede().getId()))
                            ))
                    .collect(Collectors.toList());
        }
        return todos;
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
            asistencias = attendanceRepository.findAll();
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
    @DeleteMapping("/asistencia/{id}")
    public ResponseEntity<?> eliminarAsistencia(@PathVariable Long id) {
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

        List<Attendance> deudas = attendanceRepository
                .findUnpaidByParentIdOrderByFechaDesc(parentId);

        return ResponseEntity.ok(deudas);
    }

    //*************************************
    //PUERTA 7: URL para eliminar un abono/ingreso equivocado
    //METODO: DELETE
    //Regla: Resta el monto del saldoAbono del Parent y re-ejecuta FIFO
    //*************************************
    @DeleteMapping("/abono/{id}")
    public ResponseEntity<?> eliminarAbono(@PathVariable Long id) {
        try {
            financialService.eliminarAbono(id);
            return ResponseEntity.ok("Abono eliminado correctamente");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

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
