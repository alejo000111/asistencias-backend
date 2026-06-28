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

import static com.asistencia.erp.security.SecurityUtils.*;

@RestController
@RequestMapping("/api/asistencias")
@RequiredArgsConstructor
public class AsistenciaController {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;

    @GetMapping
    public List<Attendance> listarAsistencias() {
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // Filtro directo en BD por sede, sin cargar todo en memoria
            return attendanceRepository.findBySedeIdIn(sedes);
        }
        return attendanceRepository.findAll();
    }

    private boolean estudianteEnSede(Student s, List<Long> sedesIds) {
        if (s.getMatriculas() != null &&
                s.getMatriculas().stream().anyMatch(m -> m.getSede() != null && sedesIds.contains(m.getSede().getId()))) {
            return true;
        }
        return s.getAttendances() != null &&
                s.getAttendances().stream().anyMatch(a -> a.getSede() != null && sedesIds.contains(a.getSede().getId()));
    }

    @GetMapping("/estudiante/{studentId}")
    public ResponseEntity<?> listarAsistenciasPorEstudiante(@PathVariable Long studentId) {
        return studentRepository.findById(studentId)
                .map(student -> {
                    if (isEmpleado()) {
                        List<Long> sedes = getSedesAutorizadas();
                        if (!estudianteEnSede(student, sedes)) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(attendanceRepository.findByStudentId(studentId));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
