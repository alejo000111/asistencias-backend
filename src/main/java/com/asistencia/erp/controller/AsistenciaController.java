package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Attendance;
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
        Long clubId = SecurityUtils.getClubId();
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // PERF-N1-01: JOIN FETCH incluido en findBySedeIdIn
            return attendanceRepository.findBySedeIdIn(sedes, clubId);
        }
        // PERF-N1-01: findAllWithFetch con JOIN FETCH para sede y student
        return attendanceRepository.findAllWithFetch(clubId);
    }

    @GetMapping("/estudiante/{studentId}")
    public ResponseEntity<?> listarAsistenciasPorEstudiante(@PathVariable Long studentId) {
        Long clubId = SecurityUtils.getClubId();
        return studentRepository.findById(studentId)
                .map(student -> {
                    if (!clubId.equals(student.getClubId())) {
                         return ResponseEntity.status(403).body("Acceso denegado a este estudiante");
                    }
                    if (isEmpleado()) {
                        List<Long> sedes = getSedesAutorizadas();
                        if (!SecurityUtils.estudianteEnSede(student, sedes)) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(attendanceRepository.findByStudentId(studentId));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
