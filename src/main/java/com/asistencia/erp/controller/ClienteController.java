package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/clientes")
@RequiredArgsConstructor
public class ClienteController {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;

    private boolean estudianteEnSede(Student s, List<Long> sedesIds) {
        // Coincide por matrícula activa
        if (s.getMatriculas() != null &&
                s.getMatriculas().stream().anyMatch(m -> m.getSede() != null && sedesIds.contains(m.getSede().getId()))) {
            return true;
        }
        // O por asistencias históricas (estudiante desmatriculado con historial)
        return s.getAttendances() != null &&
                s.getAttendances().stream().anyMatch(a -> a.getSede() != null && sedesIds.contains(a.getSede().getId()));
    }

    @GetMapping
    public List<Parent> listarClientes() {
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // Consulta JPQL optimizada con JOIN, filtra a nivel BD
            return parentRepository.findParentsBySedes(sedes);
        }
        return parentRepository.findAll();
    }

    @GetMapping("/estudiantes")
    public List<Student> listarEstudiantes() {
        List<Student> todos = studentRepository.findAll();
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todos.stream().filter(s -> estudianteEnSede(s, sedes))
                    .collect(Collectors.toList());
        }
        return todos;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerCliente(@PathVariable Long id) {
        return parentRepository.findById(id)
                .map(parent -> {
                    if (SecurityUtils.isEmpleado()) {
                        List<Long> sedes = SecurityUtils.getSedesAutorizadas();
                        boolean tieneAcceso = parent.getStudents() != null &&
                                parent.getStudents().stream().anyMatch(s -> estudianteEnSede(s, sedes));
                        if (!tieneAcceso) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(parent);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
