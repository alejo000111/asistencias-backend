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

import static com.asistencia.erp.security.SecurityUtils.*;

@RestController
@RequestMapping("/api/clientes")
@RequiredArgsConstructor
public class ClienteController {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;

    @GetMapping
    public List<Parent> listarClientes() {
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // Consulta JPQL optimizada con JOIN, filtra a nivel BD
            return parentRepository.findParentsBySedes(sedes);
        }
        return parentRepository.findAll();
    }

    @GetMapping("/estudiantes")
    public List<Student> listarEstudiantes() {
        // PERF-N1-01: findAllWithFetch() con JOIN FETCH evita N+1 en serialización
        List<Student> todos = studentRepository.findAllWithFetch();
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            return todos.stream().filter(s -> estudianteEnSede(s, sedes))
                    .collect(Collectors.toList());
        }
        return todos;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerCliente(@PathVariable Long id) {
        return parentRepository.findById(id)
                .map(parent -> {
                    if (isEmpleado()) {
                        List<Long> sedes = getSedesAutorizadas();
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
