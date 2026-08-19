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
import com.asistencia.erp.security.SecurityUtils;
import static com.asistencia.erp.security.SecurityUtils.*;
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
    private final com.asistencia.erp.service.SuperAdminService superAdminService;
    private final com.asistencia.erp.service.CortesiaService cortesiaService;
    private final PlanMensualidadRepository planMensualidadRepository;

    @PostMapping("/padre")
    public String registrarPadre(@RequestParam String nombre, @RequestParam String apellido, @RequestParam String telefono) {
        Parent padre = new Parent();
        padre.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        padre.setTelefono(telefono);
        padre.setSaldoAbono(BigDecimal.ZERO);
        padre.setClubId(SecurityUtils.getClubId());
        parentRepository.save(padre);
        return "Padre registrado con exito";
    }

    private List<Enrollment> crearMatriculas(Student student, List<MatriculaDTO> matriculaDTOs) {
        validarMatriculas(matriculaDTOs);

        List<Enrollment> matriculas = new ArrayList<>();
        for (MatriculaDTO dto : matriculaDTOs) {
            Sede sede = sedeRepository.findById(dto.getSedeId())
                    .orElseThrow(() -> new RuntimeException("Sede no encontrada: " + dto.getSedeId()));
            Enrollment e = new Enrollment();
            e.setStudent(student);
            e.setSede(sede);
            e.setNivel(dto.getNivel());
            e.setEsPrincipal(Boolean.TRUE.equals(dto.getEsPrincipal()));
            if (dto.getPlanMensualidadId() != null) {
                PlanMensualidad plan = planMensualidadRepository.findById(dto.getPlanMensualidadId())
                        .orElseThrow(() -> new IllegalArgumentException("El plan seleccionado ya no existe. Vuelve a elegirlo."));
                if (!plan.getSede().getId().equals(sede.getId())) {
                    throw new IllegalArgumentException("El plan '" + plan.getNombre() + "' no pertenece a la sede '" + sede.getNombre() + "'.");
                }
                e.setPlanMensualidad(plan);
            }
            matriculas.add(e);
        }
        normalizarSedePrincipal(matriculas);
        return matriculas;
    }

    /**
     * Ningún deportista puede quedar "flotando" sin cobro asignado: debe tener al menos una
     * sede, cada matrícula debe traer su grupo/nivel, y la sede PRINCIPAL (la primera marcada
     * como tal, o la primera de la lista si ninguna lo está — misma regla de
     * {@link #normalizarSedePrincipal}) debe traer un Plan. Las sedes adicionales no requieren
     * plan porque ahí no se le cobra, solo queda registrado en esa sede.
     */
    private void validarMatriculas(List<MatriculaDTO> matriculaDTOs) {
        if (matriculaDTOs == null || matriculaDTOs.isEmpty()) {
            throw new IllegalArgumentException("El deportista debe tener al menos una Sede, con su Grupo y Plan asignados.");
        }
        for (MatriculaDTO dto : matriculaDTOs) {
            if (dto.getSedeId() == null) {
                throw new IllegalArgumentException("Todas las sedes del deportista deben estar seleccionadas.");
            }
            if (dto.getNivel() == null || dto.getNivel().isBlank()) {
                throw new IllegalArgumentException("Falta seleccionar el Grupo/Nivel en una de las sedes del deportista.");
            }
        }
        boolean algunaMarcada = matriculaDTOs.stream().anyMatch(d -> Boolean.TRUE.equals(d.getEsPrincipal()));
        MatriculaDTO principal = algunaMarcada
                ? matriculaDTOs.stream().filter(d -> Boolean.TRUE.equals(d.getEsPrincipal())).findFirst().orElseThrow()
                : matriculaDTOs.get(0);
        if (principal.getPlanMensualidadId() == null) {
            throw new IllegalArgumentException("Falta seleccionar el Plan de la sede principal — es obligatorio, ahí es donde se le cobra la mensualidad.");
        }
    }

    /**
     * Garantiza que exactamente una matrícula quede marcada como sede principal (la que
     * define desde dónde se cobra la mensualidad de un deportista con varias sedes): si
     * ninguna vino marcada, se toma la primera; si vino más de una, solo se conserva la
     * primera marcada y se desmarcan las demás.
     */
    private void normalizarSedePrincipal(List<Enrollment> matriculas) {
        if (matriculas.isEmpty()) {
            return;
        }
        Enrollment principal = matriculas.stream()
            .filter(e -> Boolean.TRUE.equals(e.getEsPrincipal()))
            .findFirst()
            .orElse(matriculas.get(0));
        for (Enrollment e : matriculas) {
            e.setEsPrincipal(e == principal);
        }
    }

    private final AppUserRepository appUserRepository;

    private void validarLimitePlan() {
        var principal = getCurrentUser();
        if (principal == null || "SUPERADMIN".equalsIgnoreCase(principal.getRole())) return;

        // El "club efectivo" SIEMPRE se resuelve con SecurityUtils.getClubId() (mismo criterio
        // usado en todo el resto del sistema: para ADMIN es su propio id, para EMPLEADO es el
        // id del ADMIN al que pertenece, tomado del JWT). El lookup anterior intentaba adivinar
        // el club de un EMPLEADO cruzando sedesAutorizadas contra TODOS los ADMIN del sistema —
        // como AppUser.sedesAutorizadas de un ADMIN solo se llena cuando crea sedes por la UI
        // normal (SedeController), esa búsqueda fallaba en la práctica para casi cualquier
        // EMPLEADO, dejando el límite de deportistas SIN VERIFICAR cuando quien registraba era
        // un entrenador en vez del ADMIN — el bloqueo por plan SaaS se podía saltear así.
        Long clubId = SecurityUtils.getClubId();
        if (clubId == null) return;

        AppUser admin = appUserRepository.findById(clubId).orElse(null);
        if (admin == null) return;

        // Conteo directo por club_id (no por sedes autorizadas): correcto sin importar cómo se
        // creó cada sede, incluidas las auto-creadas por la importación de Excel.
        long activos = studentRepository.countByClubIdAndEstado(clubId, Student.StudentStatus.ACTIVO);

        Integer maxPermitido = superAdminService.obtenerLimiteDeportistas(admin.getPlanActual());

        if (maxPermitido != null && activos >= maxPermitido) {
            throw new IllegalStateException("LIMITE_DEPORTISTAS_EXCEDIDO");
        }
    }

    @Transactional
    @PostMapping("/deportista")
    public ResponseEntity<?> registrarDeportista(@RequestBody com.asistencia.erp.dto.RegistrarDeportistaRequest req) {
        try {
            validarLimitePlan();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "error", "LIMITE_DEPORTISTAS_EXCEDIDO",
                "mensaje", "Ups, has alcanzado el número máximo de deportistas permitido en tu plan. Por favor, contacta al administrador Alejandro para actualizar tu membresía."
            ));
        }

        try {
            Parent padre = parentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));
            Long clubActual = SecurityUtils.getClubId();
            if (padre.getClubId() == null || !padre.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado: el acudiente no pertenece a su club");
            }
            Student deportista = new Student();
            deportista.setParent(padre);
            deportista.setNombreCompleto((req.getNombre() + " " + req.getApellido()).trim());
            deportista.setEdad(req.getEdad());
            if (req.getFechaNacimiento() != null && !req.getFechaNacimiento().trim().isEmpty()) {
                deportista.setFechaNacimiento(java.time.LocalDate.parse(req.getFechaNacimiento()));
            }
            if (req.getAdquiereMatricula() != null) deportista.setAdquiereMatricula(req.getAdquiereMatricula());
            if (req.getAdquiereSeguro() != null) deportista.setAdquiereSeguro(req.getAdquiereSeguro());
            deportista.setClubId(SecurityUtils.getClubId());
            deportista.setMatriculas(crearMatriculas(deportista, req.getMatriculas()));
            studentRepository.save(deportista);
            financialService.procesarCargosObligatoriosYExtras(deportista);
            return ResponseEntity.ok("Deportista registrado con exito");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Datos incompletos", "mensaje", e.getMessage()));
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
            Long clubActual = SecurityUtils.getClubId();
            if (deportista.getClubId() == null || !deportista.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este deportista");
            }

            deportista.setNombreCompleto(req.getNombreCompleto().trim());
            deportista.setEdad(req.getEdad());
            // Blindaje: solo parsear si la fecha NO es nula ni vacía
            if (req.getFechaNacimiento() != null && !req.getFechaNacimiento().trim().isEmpty()) {
                deportista.setFechaNacimiento(java.time.LocalDate.parse(req.getFechaNacimiento()));
            }
            deportista.setEstado(Student.StudentStatus.valueOf(req.getEstado()));

            // Capturar el plan y la sede de la matrícula PRINCIPAL antes de reemplazarla, para poder
            // reconciliar la diferencia de dinero si el admin cambia de plan y/o de sede a mitad de mes
            // (ver FinancialService.reconciliarCambioDePlanMensualidad).
            Enrollment matriculaPrincipalAnterior = (deportista.getMatriculas() == null ? List.<Enrollment>of() : deportista.getMatriculas()).stream()
                    .filter(m -> Boolean.TRUE.equals(m.getEsPrincipal()))
                    .findFirst().orElse(null);
            PlanMensualidad planPrincipalAnterior = matriculaPrincipalAnterior != null ? matriculaPrincipalAnterior.getPlanMensualidad() : null;
            Sede sedePrincipalAnterior = matriculaPrincipalAnterior != null ? matriculaPrincipalAnterior.getSede() : null;

            // Reemplazar matriculas
            deportista.getMatriculas().clear();
            deportista.getMatriculas().addAll(crearMatriculas(deportista, req.getMatriculas()));

            if (req.getAdquiereMatricula() != null) deportista.setAdquiereMatricula(req.getAdquiereMatricula());
            if (req.getAdquiereSeguro() != null) deportista.setAdquiereSeguro(req.getAdquiereSeguro());

            studentRepository.save(deportista);
            financialService.procesarCargosObligatoriosYExtras(deportista);

            Enrollment nuevoPrincipal = deportista.getMatriculas().stream()
                    .filter(m -> Boolean.TRUE.equals(m.getEsPrincipal()))
                    .findFirst().orElse(null);
            boolean reconciliacionAplicada = nuevoPrincipal != null
                    && financialService.reconciliarCambioDePlanMensualidad(nuevoPrincipal, planPrincipalAnterior, sedePrincipalAnterior);

            return ResponseEntity.ok(java.util.Map.of(
                    "mensaje", "Deportista actualizado",
                    "reconciliacionAplicada", reconciliacionAplicada
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Datos incompletos", "mensaje", e.getMessage()));
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
            Long clubActual = SecurityUtils.getClubId();
            if (padre.getClubId() == null || !padre.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este padre");
            }
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
            Parent padre = parentRepository.findById(id).orElse(null);
            Long clubActual = SecurityUtils.getClubId();
            if (padre == null || padre.getClubId() == null || !padre.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este padre");
            }
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
            Long clubActual = SecurityUtils.getClubId();
            if (parent.getClubId() == null || !parent.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este padre");
            }
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
            Student deportista = studentRepository.findById(id).orElse(null);
            Long clubActual = SecurityUtils.getClubId();
            if (deportista == null || deportista.getClubId() == null || !deportista.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este deportista");
            }
            financialService.eliminarDeportista(id);
            return ResponseEntity.ok("Deportista eliminado. El historial de asistencias se conserva.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/cortesia/{id}")
    public ResponseEntity<?> eliminarCortesia(@PathVariable Long id) {
        try {
            financialService.eliminarCortesia(id);
            return ResponseEntity.ok("Cortesía eliminada");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/cortesia/{id}/matricular")
    public ResponseEntity<?> matricularCortesia(@PathVariable Long id) {
        try {
            cortesiaService.matricularCortesia(id);
            return ResponseEntity.ok("Deportista matriculado");
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
            Long clubActual = SecurityUtils.getClubId();
            if (student.getClubId() == null || !student.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).body("Acceso denegado a este deportista");
            }
            student.setEstado(Student.StudentStatus.RETIRADO);
            studentRepository.save(student);
            return ResponseEntity.ok("Deportista marcado como retirado.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}
