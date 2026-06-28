package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sedes")
@RequiredArgsConstructor
public class SedeController {

    private final SedeRepository sedeRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;

    @GetMapping
    public List<Sede> listarSedes() {
        // ADMIN ve todas; EMPLEADO solo las que tiene autorizadas
        if (SecurityUtils.isAdmin()) {
            return sedeRepository.findAll();
        }
        List<Long> idsPermitidos = SecurityUtils.getSedesAutorizadas();
        if (idsPermitidos.isEmpty()) {
            return List.of();
        }
        return sedeRepository.findAllById(idsPermitidos);
    }

    @PostMapping
    public ResponseEntity<?> crearSede(@RequestBody Sede sede) {
        if (sede.getNombre() == null || sede.getNombre().isBlank()) {
            return ResponseEntity.badRequest().body("El nombre de la sede es obligatorio");
        }
        if (sede.getGrupos() == null) {
            sede.setGrupos(new java.util.ArrayList<>());
        }
        if (sede.getActiva() == null) {
            sede.setActiva(true);
        }
        Sede saved = sedeRepository.save(sede);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarSede(@PathVariable Long id, @RequestBody Sede sede) {
        return sedeRepository.findById(id)
                .map(existing -> {
                    existing.setNombre(sede.getNombre());
                    existing.setActiva(sede.getActiva() != null ? sede.getActiva() : existing.getActiva());
                    existing.setGrupos(sede.getGrupos() != null ? sede.getGrupos() : new java.util.ArrayList<>());
                    return ResponseEntity.ok(sedeRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarSede(@PathVariable Long id) {
        return sedeRepository.findById(id)
                .map(sede -> {
                    // Soft delete: marcar como inactiva en vez de borrar
                    sede.setActiva(false);

                    // Desmatricular todos los estudiantes de esta sede
                    List<Enrollment> enrollments = enrollmentRepository.findBySedeId(id);
                    for (Enrollment e : enrollments) {
                        Student student = e.getStudent();
                        if (student != null) {
                            student.getMatriculas().remove(e);
                            studentRepository.save(student);
                        }
                    }
                    // Eliminar los registros de enrollment
                    enrollmentRepository.deleteAll(enrollments);

                    sedeRepository.save(sede);
                    return ResponseEntity.ok("Sede archivada y estudiantes desmatriculados.");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
