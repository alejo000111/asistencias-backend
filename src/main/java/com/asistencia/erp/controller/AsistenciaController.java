package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/asistencias")
@RequiredArgsConstructor
public class AsistenciaController {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;

    private boolean estudianteEnSede(Student s, List<Long> sedesIds) {
        return s.getMatriculas() != null &&
                s.getMatriculas().stream()
                        .anyMatch(m -> m.getSede() != null && sedesIds.contains(m.getSede().getId()));
    }

    @GetMapping
    public List<Attendance> listarAsistencias() {
        List<Attendance> todas = attendanceRepository.findAll();
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todas.stream()
                    .filter(a -> a.getStudent() != null && estudianteEnSede(a.getStudent(), sedes))
                    .collect(Collectors.toList());
        }
        return todas;
    }

    @GetMapping("/estudiante/{studentId}")
    public ResponseEntity<?> listarAsistenciasPorEstudiante(@PathVariable Long studentId) {
        return studentRepository.findById(studentId)
                .map(student -> {
                    if (SecurityUtils.isEmpleado()) {
                        List<Long> sedes = SecurityUtils.getSedesAutorizadas();
                        if (!estudianteEnSede(student, sedes)) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(attendanceRepository.findByStudentId(studentId));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
