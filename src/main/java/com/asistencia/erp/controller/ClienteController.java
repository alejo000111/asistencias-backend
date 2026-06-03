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

    @GetMapping
    public List<Parent> listarClientes() {
        List<Parent> todos = parentRepository.findAll();

        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            // Filtrar padres que tengan hijos en las sedes autorizadas del empleado
            return todos.stream()
                    .filter(p -> p.getStudents() != null &&
                            p.getStudents().stream()
                                    .anyMatch(s -> s.getSede() != null &&
                                            sedes.contains(s.getSede().getId())))
                    .collect(Collectors.toList());
        }

        return todos;
    }

    @GetMapping("/estudiantes")
    public List<Student> listarEstudiantes() {
        List<Student> todos = studentRepository.findAll();

        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todos.stream()
                    .filter(s -> s.getSede() != null && sedes.contains(s.getSede().getId()))
                    .collect(Collectors.toList());
        }

        return todos;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerCliente(@PathVariable Long id) {
        return parentRepository.findById(id)
                .map(parent -> {
                    // Verificar acceso por sede si es empleado
                    if (SecurityUtils.isEmpleado()) {
                        List<Long> sedes = SecurityUtils.getSedesAutorizadas();
                        boolean tieneAcceso = parent.getStudents() != null &&
                                parent.getStudents().stream()
                                        .anyMatch(s -> s.getSede() != null &&
                                                sedes.contains(s.getSede().getId()));
                        if (!tieneAcceso) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(parent);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
