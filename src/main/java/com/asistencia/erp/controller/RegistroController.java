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

import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/registro")
@RequiredArgsConstructor
public class RegistroController {

    private final FinancialService financialService;
    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final AttendanceRepository attendanceRepository;
    private final FinancialLogRepository financialLogRepository;

    @PostMapping("/padre")
    public String registrarPadre(@RequestParam String nombre, @RequestParam String apellido, @RequestParam String telefono) {
        Parent padre = new Parent();
        padre.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        padre.setTelefono(telefono);
        padre.setSaldoAbono(BigDecimal.ZERO);
        parentRepository.save(padre);
        return "Padre registrado con éxito";
    }

    @PostMapping("/deportista")
    public String registrarDeportista(
            @RequestParam Long parentId,
            @RequestParam String nombre,
            @RequestParam String apellido,
            @RequestParam Integer edad,
            @RequestParam String fechaNacimiento,
            @RequestParam String nivel) {

        Parent padre = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + parentId));
        Student deportista = new Student();
        deportista.setParent(padre);
        deportista.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        deportista.setEdad(edad);
        deportista.setFechaNacimiento(java.time.LocalDate.parse(fechaNacimiento));
        deportista.setNivel(nivel);
        studentRepository.save(deportista);
        return "¡Deportista registrado con éxito!";
    }

    @PutMapping("/deportista/{id}")
    public String actualizarDeportista(
            @PathVariable Long id,
            @RequestParam String nombreCompleto,
            @RequestParam Integer edad,
            @RequestParam String fechaNacimiento,
            @RequestParam String estado,
            @RequestParam String nivel) {

        Student deportista = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Deportista no encontrado con ID: " + id));
        deportista.setNombreCompleto(nombreCompleto.trim());
        deportista.setEdad(edad);
        deportista.setFechaNacimiento(java.time.LocalDate.parse(fechaNacimiento));
        deportista.setEstado(Student.StudentStatus.valueOf(estado));
        deportista.setNivel(nivel);
        studentRepository.save(deportista);
        return "Deportista actualizado";
    }

    @PutMapping("/padre/{id}")
    public String actualizarPadre(
            @PathVariable Long id,
            @RequestParam String nombreCompleto,
            @RequestParam String telefono,
            @RequestParam String estado) {
        Parent padre = parentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + id));
        padre.setNombreCompleto(nombreCompleto.trim());
        padre.setTelefono(telefono.trim());
        padre.setEstado(estado);
        parentRepository.save(padre);
        return "Padre actualizado";
    }

    //*************************************
    // OPCIÓN 1: ELIMINAR (hard-delete)
    // Borra al padre y estudiantes de la BD, pero conserva el historial
    // orfanando las referencias en asistencias y financial_logs.
    //*************************************
    @DeleteMapping("/padre/{id}")
    public ResponseEntity<?> eliminarPadre(@PathVariable Long id) {
        try {
            financialService.eliminarFamilia(id);
        return ResponseEntity.ok("Familia eliminada completamente. El historial se conserva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    //*************************************
    // OPCIÓN 2: INACTIVAR (soft-delete)
    // Marca al padre como INACTIVO y a los hijos como RETIRADO.
    // Siguen existiendo en BD pero no aparecen en clientes activos.
    //*************************************
    @PostMapping("/padre/{id}/inactivar")
    public ResponseEntity<?> inactivarPadre(@PathVariable Long id) {
        try {
            Parent parent = parentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            parent.setEstado("INACTIVO");
            parentRepository.save(parent);

            if (parent.getStudents() != null) {
                for (Student student : parent.getStudents()) {
                    student.setEstado(Student.StudentStatus.RETIRADO);
                    studentRepository.save(student);
                }
            }

            return ResponseEntity.ok("Familia marcada como inactiva. Puedes reactivarla desde Clientes Inactivos.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    //*************************************
    // OPCIÓN 1: ELIMINAR deportista (hard-delete)
    //*************************************
    @DeleteMapping("/deportista/{id}")
    public ResponseEntity<?> eliminarDeportista(@PathVariable Long id) {
        try {
            Student student = studentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));

            // Orfanar asistencias
            for (Attendance a : attendanceRepository.findByStudentId(id)) {
                a.setNombreEstudianteHistorico(student.getNombreCompleto());
                a.setStudent(null);
                attendanceRepository.save(a);
            }

            studentRepository.delete(student);
            return ResponseEntity.ok("Deportista eliminado. El historial de asistencias se conserva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    //*************************************
    // OPCIÓN 2: RETIRAR deportista (soft-delete)
    //*************************************
    @PostMapping("/deportista/{id}/retirar")
    public ResponseEntity<?> retirarDeportista(@PathVariable Long id) {
        try {
            Student student = studentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));
            student.setEstado(Student.StudentStatus.RETIRADO);
            studentRepository.save(student);
            return ResponseEntity.ok("Deportista marcado como retirado. Se puede reactivar editándolo.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}