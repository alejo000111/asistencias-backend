package com.asistencia.erp.controller;

import com.asistencia.erp.dto.ActualizarDeportistaRequest;
import com.asistencia.erp.dto.ActualizarDeportistaRequest.MatriculaDTO;


import java.math.BigDecimal;
import com.asistencia.erp.entity.*;
import com.asistencia.erp.repository.*;
import com.asistencia.erp.service.FinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/registro")
@RequiredArgsConstructor
public class RegistroController {

    private final FinancialService financialService;
    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final AttendanceRepository attendanceRepository;
    private final FinancialLogRepository financialLogRepository;

    @PostMapping("/padre")
    public String registrarPadre(@RequestParam String nombre, @RequestParam String apellido, @RequestParam String telefono) {
        Parent padre = new Parent();
        padre.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        padre.setTelefono(telefono);
        padre.setSaldoAbono(BigDecimal.ZERO);
        parentRepository.save(padre);
        return "Padre registrado con exito";
    }

    private List<Enrollment> crearMatriculas(Student student, List<MatriculaDTO> matriculaDTOs) {
        List<Enrollment> matriculas = new ArrayList<>();
        if (matriculaDTOs != null) {
            for (MatriculaDTO dto : matriculaDTOs) {
                Sede sede = sedeRepository.findById(dto.getSedeId())
                        .orElseThrow(() -> new RuntimeException("Sede no encontrada: " + dto.getSedeId()));
                Enrollment e = new Enrollment();
                e.setStudent(student);
                e.setSede(sede);
                e.setNivel(dto.getNivel());
                matriculas.add(e);
            }
        }
        return matriculas;
    }

    @Transactional
    @PostMapping("/deportista")
    public ResponseEntity<?> registrarDeportista(@RequestBody com.asistencia.erp.dto.RegistrarDeportistaRequest req) {
        try {
            Parent padre = parentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));
            Student deportista = new Student();
            deportista.setParent(padre);
            deportista.setNombreCompleto((req.getNombre() + " " + req.getApellido()).trim());
            deportista.setEdad(req.getEdad());
            // Blindaje: solo parsear si la fecha NO es nula ni vacía
            if (req.getFechaNacimiento() != null && !req.getFechaNacimiento().trim().isEmpty()) {
                deportista.setFechaNacimiento(java.time.LocalDate.parse(req.getFechaNacimiento()));
            }
            deportista.setMatriculas(crearMatriculas(deportista, req.getMatriculas()));
            studentRepository.save(deportista);
            return ResponseEntity.ok("Deportista registrado con exito");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @PutMapping("/deportista/{id}")
    public ResponseEntity<?> actualizarDeportista(
            @PathVariable Long id,
            @RequestBody ActualizarDeportistaRequest req) {
        try {
            Student deportista = studentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Deportista no encontrado con ID: " + id));

            deportista.setNombreCompleto(req.getNombreCompleto().trim());
            deportista.setEdad(req.getEdad());
            // Blindaje: solo parsear si la fecha NO es nula ni vacía
            if (req.getFechaNacimiento() != null && !req.getFechaNacimiento().trim().isEmpty()) {
                deportista.setFechaNacimiento(java.time.LocalDate.parse(req.getFechaNacimiento()));
            }
            deportista.setEstado(Student.StudentStatus.valueOf(req.getEstado()));

            // Reemplazar matriculas
            deportista.getMatriculas().clear();
            deportista.getMatriculas().addAll(crearMatriculas(deportista, req.getMatriculas()));

            studentRepository.save(deportista);
            return ResponseEntity.ok("Deportista actualizado");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error al actualizar deportista: " + e.getMessage());
        }
    }

    @PutMapping("/padre/{id}")
    public ResponseEntity<?> actualizarPadre(
            @PathVariable Long id,
            @RequestParam String nombreCompleto,
            @RequestParam String telefono,
            @RequestParam String estado) {
        try {
            Parent padre = parentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + id));
            padre.setNombreCompleto(nombreCompleto.trim());
            padre.setTelefono(telefono.trim());
            padre.setEstado(estado);
            parentRepository.save(padre);
            return ResponseEntity.ok("Padre actualizado");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error al actualizar padre: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/padre/{id}")
    public ResponseEntity<?> eliminarPadre(@PathVariable Long id) {
        try {
            financialService.eliminarFamilia(id);
            return ResponseEntity.ok("Familia eliminada completamente. El historial se conserva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
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
            return ResponseEntity.ok("Familia marcada como inactiva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/deportista/{id}")
    public ResponseEntity<?> eliminarDeportista(@PathVariable Long id) {
        try {
            financialService.eliminarDeportista(id);
            return ResponseEntity.ok("Deportista eliminado. El historial de asistencias se conserva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/deportista/{id}/retirar")
    public ResponseEntity<?> retirarDeportista(@PathVariable Long id) {
        try {
            Student student = studentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));
            student.setEstado(Student.StudentStatus.RETIRADO);
            studentRepository.save(student);
            return ResponseEntity.ok("Deportista marcado como retirado.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}
