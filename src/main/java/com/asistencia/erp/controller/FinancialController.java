package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.service.FinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

//Se le avisa a Spring Boot que esta clase recibirá peticiones web
@RestController
//URL predefinida para el controlador
@RequestMapping("/api/finanzas")
//Permite que el frontend (Vue 3) se conecte sin bloqueos de seguridad
@CrossOrigin("*")
@RequiredArgsConstructor
public class FinancialController {
    //Se invoca el servicio
    @Autowired
    private final FinancialService financialService;
    @Autowired
    private final ParentRepository parentRepository;
    @Autowired
    private final FinancialLogRepository financialLogRepository;
    @Autowired
    private final AttendanceRepository attendanceRepository;
    @Autowired
    private final StudentRepository studentRepository;
    //*************************************
    //PUERTA 1: URL para registrar asistencias
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/asistencia
    //*************************************
    @PostMapping("/asistencia")
    public String registrarAsistencia(
            @RequestParam Long studentId,
            @RequestParam String tipoClase,
            @RequestParam boolean esMediaClase,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String fecha) {

        financialService.registrarAsistencia(
                studentId,
                FinancialService.TipoClase.valueOf(tipoClase),
                esMediaClase,
                nivel,
                fecha
        );
        return "Asistencia registrada";
    }

    //*************************************
    //PUERTA 2: URL para registrar abono de dinero
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/abono
    //*************************************
    @PostMapping("/abono")
    public String registrarAbono(
            @RequestParam Long parentId,
            @RequestParam BigDecimal monto,
            @RequestParam String metodoPago,
            @RequestParam(required = false) String fecha) {

        // Si no mandan fecha, usamos la de hoy
        java.time.LocalDate fechaAbono = (fecha != null && !fecha.isEmpty())
                ? java.time.LocalDate.parse(fecha)
                : java.time.LocalDate.now();

        financialService.registrarAbono(parentId, monto, FinancialLog.PaymentMethod.valueOf(metodoPago), fechaAbono);
        return "Abono registrado con éxito";
    }

    @GetMapping("/historial")
    public org.springframework.http.ResponseEntity<?> obtenerHistorialCaja() {
        // Asumiendo que tienes un financialLogRepository inyectado en este controlador
        // Si no lo tienes, puedes agregarlo o llamarlo a través del financialService
        return org.springframework.http.ResponseEntity.ok(financialLogRepository.findAll());
    }

    @GetMapping("/historial/{parentId}")
    public org.springframework.http.ResponseEntity<?> obtenerHistorialPorPadre(@PathVariable Long parentId) {
        // Traemos todos, filtramos por el padre, ordenamos del más nuevo al más viejo, y cortamos en 10
        java.util.List<com.asistencia.erp.entity.FinancialLog> historialPadre = financialLogRepository.findAll().stream()
                .filter(log -> log.getParent() != null && log.getParent().getId().equals(parentId))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .limit(10)
                .collect(java.util.stream.Collectors.toList());

        return org.springframework.http.ResponseEntity.ok(historialPadre);
    }

    //*************************************
    //PUERTA 3: URL para ver todos los padres y sus saldos
    //METODO: GET
    //Ruta final: http://localhost:8080/api/finanzas/padres
    @GetMapping("/padres")
    public List<Parent> obtenerPadres() {
        return parentRepository.findAll();
    }

    //*************************************
    //PUERTA 4: URL para ver el historial completo de asistencias
    //METODO: GET
    //*************************************
    @GetMapping("/historial-asistencias")
    public org.springframework.http.ResponseEntity<?> obtenerTodasLasAsistencias() {
        return org.springframework.http.ResponseEntity.ok(attendanceRepository.findAll());
    }

    //*************************************
    //PUERTA 5: URL para eliminar una asistencia
    //METODO: DELETE
    //*************************************
    @DeleteMapping("/asistencia/{id}")
    public org.springframework.http.ResponseEntity<?> eliminarAsistencia(@PathVariable Long id) {
        attendanceRepository.deleteById(id);
        return org.springframework.http.ResponseEntity.ok("Asistencia eliminada");
    }

    //*************************************
    //PUERTA 6: URL para ver las deudas de un papá específico
    //METODO: GET
    //*************************************
    @GetMapping("/deudas/{parentId}")
    public org.springframework.http.ResponseEntity<?> obtenerDeudasPadre(@PathVariable Long parentId) {
        java.util.List<com.asistencia.erp.entity.Attendance> deudas = attendanceRepository.findAll().stream()
                .filter(a -> a.getStudent() != null && a.getStudent().getParent() != null)
                .filter(a -> a.getStudent().getParent().getId().equals(parentId))
                .filter(a -> !a.getClasePaga()) // Filtramos solo las NO pagadas
                .sorted((a, b) -> b.getFecha().compareTo(a.getFecha())) // Más recientes primero
                .collect(java.util.stream.Collectors.toList());

        return org.springframework.http.ResponseEntity.ok(deudas);
    }

    @DeleteMapping("/deportista/{id}")
    public ResponseEntity<?> eliminarDeportista(@PathVariable Long id) {
        try {
            Student student = studentRepository.findById(id).orElse(null);
            if (student == null) return ResponseEntity.notFound().build();

            // 1. Buscamos sus asistencias
            List<Attendance> asistencias = attendanceRepository.findByStudentId(id);

            for (Attendance a : asistencias) {
                // ASIGNAMOS EL NOMBRE REAL ANTES DE BORRAR
                a.setNombreEstudianteHistorico(student.getNombreCompleto());
                a.setStudent(null); // Soltamos el ID
                attendanceRepository.save(a);
            }

            studentRepository.delete(student);
            return ResponseEntity.ok("Eliminado");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
