package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Complemento;
import com.asistencia.erp.entity.ComplementoSedePrecio;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.entity.StudentComplemento;
import com.asistencia.erp.repository.ComplementoRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentComplementoRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de complementos opcionales a la mensualidad (Gym Virtual, Gym Presencial,
 * Pista Adicional, Valor x Clase, etc.), a nivel club. Cada complemento decide si su
 * precio es único para todo el club o diferenciado por sede de origen.
 */
@RestController
@RequestMapping("/api/complementos")
@RequiredArgsConstructor
public class ComplementoController {

    private final ComplementoRepository complementoRepository;
    private final SedeRepository sedeRepository;
    private final com.asistencia.erp.repository.EscenarioRepository escenarioRepository;
    private final StudentRepository studentRepository;
    private final StudentComplementoRepository studentComplementoRepository;
    private final com.asistencia.erp.service.FinancialService financialService;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Complemento> listarComplementos() {
        return complementoRepository.findByClubIdWithPrecios(SecurityUtils.getClubId());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Transactional
    public ResponseEntity<?> crearComplemento(@RequestBody Map<String, Object> body) {
        if (body.get("nombre") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre es obligatorio"));
        }
        ResponseEntity<?> errorGrupo = validarGrupoRequiereSede(body);
        if (errorGrupo != null) return errorGrupo;
        Complemento complemento = new Complemento();
        complemento.setClubId(SecurityUtils.getClubId());
        complemento.setActivo(true);
        complemento.setPreciosDiferenciadosPorSede(false);
        aplicarCamposComplemento(complemento, body);
        return ResponseEntity.ok(complementoRepository.save(complemento));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> actualizarComplemento(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Complemento complemento = complementoRepository.findById(id).orElse(null);
        if (complemento == null || !validarClub(complemento.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este complemento"));
        }
        ResponseEntity<?> errorGrupo = validarGrupoRequiereSede(body);
        if (errorGrupo != null) return errorGrupo;
        aplicarCamposComplemento(complemento, body);
        return ResponseEntity.ok(complementoRepository.save(complemento));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> eliminarComplemento(@PathVariable Long id) {
        Complemento complemento = complementoRepository.findById(id).orElse(null);
        if (complemento == null || !validarClub(complemento.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este complemento"));
        }
        complemento.setActivo(false);
        complementoRepository.save(complemento);
        return ResponseEntity.ok(Map.of("mensaje", "Complemento desactivado"));
    }

    @GetMapping("/estudiante/{studentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> listarComplementosDeEstudiante(@PathVariable Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null || !validarClub(student.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este estudiante"));
        }
        return ResponseEntity.ok(studentComplementoRepository.findActivosByStudentId(studentId));
    }

    /**
     * Asigna un complemento a un estudiante. Pensado para ser llamado tanto por el
     * admin manualmente como, en el futuro, por la pasarela de pagos tras un pago exitoso.
     * Body: { "complementoId": 1, "sedeOrigenId": 2 }
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/estudiante/{studentId}")
    @Transactional
    public ResponseEntity<?> asignarComplementoAEstudiante(@PathVariable Long studentId, @RequestBody Map<String, Object> body) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null || !validarClub(student.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este estudiante"));
        }
        if (body.get("complementoId") == null || body.get("sedeOrigenId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "complementoId y sedeOrigenId son obligatorios"));
        }
        Complemento complemento = complementoRepository.findById(Long.parseLong(body.get("complementoId").toString())).orElse(null);
        if (complemento == null || !validarClub(complemento.getClubId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Complemento inválido"));
        }
        Sede sedeOrigen = sedeRepository.findById(Long.parseLong(body.get("sedeOrigenId").toString())).orElse(null);
        if (sedeOrigen == null || !validarClub(sedeOrigen.getClubId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Sede de origen inválida"));
        }

        StudentComplemento asignacion = StudentComplemento.builder()
                .student(student)
                .complemento(complemento)
                .sedeOrigen(sedeOrigen)
                .activo(true)
                .fechaAsignacion(LocalDateTime.now())
                .build();

        StudentComplemento guardada = studentComplementoRepository.save(asignacion);

        // Generar el cargo del mes inmediatamente, igual que matrícula/seguro al
        // registrar un deportista — sin esto, el cargo quedaba pendiente hasta que
        // algo más disparara el recálculo (ej. editar el estudiante).
        financialService.procesarCargosObligatoriosYExtras(student);

        return ResponseEntity.ok(guardada);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/estudiante/{studentId}/{asignacionId}")
    @Transactional
    public ResponseEntity<?> quitarComplementoDeEstudiante(@PathVariable Long studentId, @PathVariable Long asignacionId) {
        StudentComplemento asignacion = studentComplementoRepository.findById(asignacionId).orElse(null);
        if (asignacion == null || !asignacion.getStudent().getId().equals(studentId) || !validarClub(asignacion.getStudent().getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a esta asignación"));
        }
        asignacion.setActivo(false);
        studentComplementoRepository.save(asignacion);
        return ResponseEntity.ok(Map.of("mensaje", "Complemento desactivado para el estudiante"));
    }

    // ─────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────

    private boolean validarClub(Long clubId) {
        Long clubIdActual = SecurityUtils.getClubId();
        return clubId != null && clubId.equals(clubIdActual);
    }

    /** Un complemento con grupo asignado necesita un escenario al cual pertenece ese grupo. */
    private ResponseEntity<?> validarGrupoRequiereSede(Map<String, Object> body) {
        Object grupoNombre = body.get("grupoNombre");
        Object escenarioId = body.get("escenarioId");
        boolean tieneGrupo = grupoNombre != null && !grupoNombre.toString().isBlank();
        boolean tieneEscenario = escenarioId != null && !escenarioId.toString().isBlank();
        if (tieneGrupo && !tieneEscenario) {
            return ResponseEntity.badRequest().body(Map.of("error", "Debes seleccionar un escenario para asociar un grupo"));
        }
        return null;
    }

    private void aplicarCamposComplemento(Complemento complemento, Map<String, Object> body) {
        if (body.containsKey("nombre") && body.get("nombre") != null)
            complemento.setNombre(body.get("nombre").toString());
        if (body.containsKey("vecesPorPeriodo"))
            complemento.setVecesPorPeriodo(body.get("vecesPorPeriodo") != null ? Integer.parseInt(body.get("vecesPorPeriodo").toString()) : null);
        // El periodo (semanal/mensual) ya no se define aquí: lo aporta el escenario,
        // para que plan y complementos del mismo espacio compartan ventana de conteo.
        if (body.containsKey("escenarioId")) {
            if (body.get("escenarioId") == null || body.get("escenarioId").toString().isBlank()) {
                complemento.setEscenario(null);
            } else {
                var escenario = escenarioRepository.findById(Long.parseLong(body.get("escenarioId").toString())).orElse(null);
                if (escenario != null && escenario.getClubId().equals(SecurityUtils.getClubId())) {
                    complemento.setEscenario(escenario);
                }
            }
        }
        if (body.containsKey("grupoNombre"))
            complemento.setGrupoNombre(body.get("grupoNombre") != null && !body.get("grupoNombre").toString().isBlank()
                    ? body.get("grupoNombre").toString() : null);
        if (body.containsKey("precioBase") && body.get("precioBase") != null)
            complemento.setPrecioBase(new BigDecimal(body.get("precioBase").toString()));
        if (body.containsKey("activo") && body.get("activo") != null)
            complemento.setActivo(Boolean.valueOf(body.get("activo").toString()));
        if (body.containsKey("preciosDiferenciadosPorSede") && body.get("preciosDiferenciadosPorSede") != null)
            complemento.setPreciosDiferenciadosPorSede(Boolean.valueOf(body.get("preciosDiferenciadosPorSede").toString()));

        if (body.containsKey("preciosPorSede") && body.get("preciosPorSede") != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> preciosBody = (List<Map<String, Object>>) body.get("preciosPorSede");

            Map<Long, ComplementoSedePrecio> existentes = new HashMap<>();
            for (ComplementoSedePrecio p : complemento.getPreciosPorSede()) {
                existentes.put(p.getSedeOrigen().getId(), p);
            }

            List<ComplementoSedePrecio> actualizados = new ArrayList<>();
            for (Map<String, Object> item : preciosBody) {
                if (item.get("sedeId") == null || item.get("precio") == null) continue;
                Long sedeId = Long.parseLong(item.get("sedeId").toString());
                Sede sede = sedeRepository.findById(sedeId).orElse(null);
                if (sede == null) continue;

                ComplementoSedePrecio p = existentes.getOrDefault(sedeId, new ComplementoSedePrecio());
                p.setComplemento(complemento);
                p.setSedeOrigen(sede);
                p.setPrecio(new BigDecimal(item.get("precio").toString()));
                actualizados.add(p);
            }

            complemento.getPreciosPorSede().clear();
            complemento.getPreciosPorSede().addAll(actualizados);
        }
    }
}
