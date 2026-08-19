package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Escenario;
import com.asistencia.erp.repository.EscenarioRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Escenarios del club (Cancha, Pista, Gimnasio, Sintética...): el admin los crea
 * con el nombre que quiera. No existe ningún catálogo predefinido.
 */
@RestController
@RequestMapping("/api/escenarios")
@RequiredArgsConstructor
public class EscenarioController {

    private final EscenarioRepository escenarioRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Escenario> listarEscenarios() {
        return escenarioRepository.findByClubId(SecurityUtils.getClubId());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Transactional
    public ResponseEntity<?> crearEscenario(@RequestBody Map<String, Object> body) {
        String nombre = body.get("nombre") != null ? body.get("nombre").toString().trim() : "";
        if (nombre.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre del escenario es obligatorio"));
        }
        Long clubId = SecurityUtils.getClubId();
        if (escenarioRepository.findByNombreAndClubId(nombre, clubId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ya existe un escenario llamado '" + nombre + "' en este club."));
        }

        Escenario escenario = new Escenario();
        escenario.setClubId(clubId);
        escenario.setActivo(true);
        escenario.setPeriodo(Escenario.Periodo.SEMANAL);
        aplicarCampos(escenario, body);

        return ResponseEntity.ok(escenarioRepository.save(escenario));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> actualizarEscenario(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Escenario escenario = escenarioRepository.findById(id).orElse(null);
        if (escenario == null || !escenario.getClubId().equals(SecurityUtils.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este escenario"));
        }

        if (body.containsKey("nombre") && body.get("nombre") != null) {
            String nuevoNombre = body.get("nombre").toString().trim();
            if (nuevoNombre.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "El nombre del escenario es obligatorio"));
            }
            if (!nuevoNombre.equalsIgnoreCase(escenario.getNombre())
                    && escenarioRepository.findByNombreAndClubId(nuevoNombre, escenario.getClubId()).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Ya existe un escenario llamado '" + nuevoNombre + "' en este club."));
            }
        }

        aplicarCampos(escenario, body);
        return ResponseEntity.ok(escenarioRepository.save(escenario));
    }

    /**
     * Desactiva el escenario. Se rechaza si todavía hay sedes o cupos de plan
     * apuntando a él, para no dejar cuotas evaluándose contra algo desactivado.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> desactivarEscenario(@PathVariable Long id) {
        Escenario escenario = escenarioRepository.findById(id).orElse(null);
        if (escenario == null || !escenario.getClubId().equals(SecurityUtils.getClubId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este escenario"));
        }

        long sedes = escenarioRepository.contarSedesQueLoUsan(id);
        long cupos = escenarioRepository.contarCuposQueLoUsan(id);
        if (sedes > 0 || cupos > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No se puede desactivar '" + escenario.getNombre() + "': lo usan " + sedes
                       + " sede(s) y " + cupos + " cupo(s) de plan. Reasígnalos primero."
            ));
        }

        escenario.setActivo(false);
        escenarioRepository.save(escenario);
        return ResponseEntity.ok(Map.of("mensaje", "Escenario desactivado"));
    }

    private void aplicarCampos(Escenario escenario, Map<String, Object> body) {
        if (body.containsKey("nombre") && body.get("nombre") != null)
            escenario.setNombre(body.get("nombre").toString().trim());
        if (body.containsKey("emoji"))
            escenario.setEmoji(body.get("emoji") != null ? body.get("emoji").toString() : null);
        if (body.containsKey("periodo") && body.get("periodo") != null) {
            try {
                escenario.setPeriodo(Escenario.Periodo.valueOf(body.get("periodo").toString()));
            } catch (IllegalArgumentException ignored) {
                // valor inválido: se conserva el periodo actual
            }
        }
        if (body.containsKey("activo") && body.get("activo") != null)
            escenario.setActivo(Boolean.valueOf(body.get("activo").toString()));
        if (body.containsKey("orden") && body.get("orden") != null)
            escenario.setOrden(Integer.parseInt(body.get("orden").toString()));
    }
}
