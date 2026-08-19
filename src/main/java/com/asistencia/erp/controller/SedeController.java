package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sedes")
@RequiredArgsConstructor
@Slf4j
public class SedeController {

    private final SedeRepository sedeRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final AttendanceRepository attendanceRepository;
    private final AppUserRepository appUserRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Sede> listarSedes() {
        try {
            if (SecurityUtils.isAdmin()) {
                return sedeRepository.findAll();
            }
            List<Long> idsPermitidos = SecurityUtils.getSedesAutorizadas();
            if (idsPermitidos == null || idsPermitidos.isEmpty()) {
                return sedeRepository.findAll();
            }
            return sedeRepository.findAllById(idsPermitidos);
        } catch (Exception e) {
            log.error("Error al listar sedes: {}", e.getMessage(), e);
            return sedeRepository.findAll();
        }
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

    @Transactional
    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarSede(@PathVariable Long id, @RequestBody Sede sede) {
        return sedeRepository.findById(id)
                .map(existing -> {
                    List<com.asistencia.erp.entity.GrupoSede> oldGrupos = new java.util.ArrayList<>(existing.getGrupos() != null ? existing.getGrupos() : List.of());

                    existing.setNombre(sede.getNombre());
                    existing.setActiva(sede.getActiva() != null ? sede.getActiva() : existing.getActiva());
                    existing.setGrupos(sede.getGrupos() != null ? sede.getGrupos() : new java.util.ArrayList<>());

                    Sede saved = sedeRepository.save(existing);

                    // Sincronizar las matrículas de deportistas si cambiaron los nombres o emojis de los grupos
                    List<com.asistencia.erp.entity.GrupoSede> newGrupos = saved.getGrupos();
                    List<Enrollment> enrollments = enrollmentRepository.findBySedeId(id);

                    if (!enrollments.isEmpty() && !newGrupos.isEmpty()) {
                        boolean modified = false;
                        for (int i = 0; i < Math.min(oldGrupos.size(), newGrupos.size()); i++) {
                            com.asistencia.erp.entity.GrupoSede oldG = oldGrupos.get(i);
                            com.asistencia.erp.entity.GrupoSede newG = newGrupos.get(i);

                            String oldNameOnly = oldG.getNombre() != null ? oldG.getNombre().trim() : "";
                            String oldFormatted = (oldG.getEmoji() != null && !oldG.getEmoji().isBlank() ? oldG.getEmoji().trim() + " " : "") + oldNameOnly;

                            String newNameOnly = newG.getNombre() != null ? newG.getNombre().trim() : "";
                            String newFormatted = (newG.getEmoji() != null && !newG.getEmoji().isBlank() ? newG.getEmoji().trim() + " " : "") + newNameOnly;

                            for (Enrollment e : enrollments) {
                                if (e.getNivel() != null) {
                                    String currentNivel = e.getNivel().trim();
                                    // Coincide con nombre solo, con formato previo o por sufijo de grupo
                                    if (currentNivel.equalsIgnoreCase(oldNameOnly)
                                            || currentNivel.equalsIgnoreCase(oldFormatted)
                                            || currentNivel.equalsIgnoreCase(newNameOnly)
                                            || currentNivel.endsWith(oldNameOnly)
                                            || currentNivel.endsWith(newNameOnly)) {
                                        if (!currentNivel.equals(newFormatted)) {
                                            e.setNivel(newFormatted);
                                            modified = true;
                                        }
                                    }
                                }
                            }
                        }
                        if (modified) {
                            enrollmentRepository.saveAll(enrollments);
                        }
                    }

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarSede(@PathVariable Long id) {
        try {
            return sedeRepository.findById(id)
                    .map(sede -> {
                        if (Boolean.TRUE.equals(sede.getActiva())) {
                            // 1. Si la sede está activa: DESACTIVAR (Preserva las matrículas en la BD para cuando se reactive)
                            sede.setActiva(false);
                            sedeRepository.save(sede);
                            return ResponseEntity.ok("Sede desactivada correctamente. Las vinculaciones se conservarán para cuando sea reactivada.");
                        } else {
                            // 2. Si la sede está INACTIVA: ELIMINAR DEFINITIVAMENTE de la Base de Datos

                            // A. Eliminar matrículas asociadas
                            List<Enrollment> enrollments = enrollmentRepository.findBySedeId(id);
                            for (Enrollment e : enrollments) {
                                Student student = e.getStudent();
                                if (student != null) {
                                    student.getMatriculas().remove(e);
                                    studentRepository.save(student);
                                }
                            }
                            enrollmentRepository.deleteAll(enrollments);
                            enrollmentRepository.flush();

                            // A. Desvincular asistencias que apuntaban a esta sede
                            List<Attendance> asistencias = attendanceRepository.findBySedeId(id);
                            for (Attendance a : asistencias) {
                                a.setSede(null);
                                attendanceRepository.save(a);
                            }
                            attendanceRepository.flush();

                            // B. Desvincular de usuarios / empleados autorizados en la tabla join app_user_sedes
                            List<AppUser> usuarios = appUserRepository.findAll();
                            for (AppUser u : usuarios) {
                                if (u.getSedesAutorizadas() != null && u.getSedesAutorizadas().contains(sede)) {
                                    u.getSedesAutorizadas().remove(sede);
                                    appUserRepository.save(u);
                                }
                            }
                            appUserRepository.flush();

                            // C. Limpiar la colección de grupos para purgar la tabla de elementos sede_grupos
                            if (sede.getGrupos() != null) {
                                sede.getGrupos().clear();
                                sedeRepository.saveAndFlush(sede);
                            }

                            // D. Eliminar la entidad Sede físicamente de la base de datos
                            sedeRepository.delete(sede);
                            sedeRepository.flush();
                            return ResponseEntity.ok("Sede eliminada permanentemente de la base de datos.");
                        }
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error al eliminar la sede ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body("No se pudo eliminar la sede: " + e.getMessage());
        }
    }
}
