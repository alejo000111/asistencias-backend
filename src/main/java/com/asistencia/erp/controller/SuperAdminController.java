package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.SaasPlan;
import com.asistencia.erp.service.SuperAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controlador exclusivo del SUPERADMIN (dueño de la plataforma SaaS).
 *
 * Todas las rutas bajo /api/superadmin/** están protegidas con:
 *   - SecurityConfig: .requestMatchers("/api/superadmin/**").hasRole("SUPERADMIN")
 *   - @PreAuthorize en cada método como segunda capa de defensa
 *
 * PRIVACIDAD ESTRICTA: Este controlador solo expone datos del club como entidad
 * (Nombre, NIT, Dueño, Estado, Plan, Fecha de Corte).
 * NO expone listas de deportistas, teléfonos ni transacciones financieras.
 */
@RestController
@RequestMapping("/api/superadmin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
public class SuperAdminController {

    private final SuperAdminService superAdminService;

    // ─────────────────────────────────────────────
    // Gestión de Clubs
    // ─────────────────────────────────────────────

    /**
     * Lista todos los clubs (ADMIN_CLUB) con sus metadatos.
     * Estrictamente sin datos de deportistas ni transacciones.
     */
    @GetMapping("/clubes")
    public ResponseEntity<List<Map<String, Object>>> listarClubes() {
        List<AppUser> clubes = superAdminService.listarClubes();
        List<Map<String, Object>> response = clubes.stream()
                .map(this::mapClubSeguro)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene los datos de un club específico por ID del admin.
     */
    @GetMapping("/clubes/{id}")
    public ResponseEntity<Map<String, Object>> obtenerClub(@PathVariable Long id) {
        // Buscamos en la lista y filtramos; el servicio ya garantiza rol ADMIN
        AppUser admin = superAdminService.listarClubes().stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Club no encontrado: " + id));
        return ResponseEntity.ok(mapClubSeguro(admin));
    }

    /**
     * Crea un nuevo administrador de club con contraseña temporal.
     * Body esperado: { "username", "password", "clubNombre", "clubNit", "fechaCorte" }
     */
    @PostMapping("/clubes")
    public ResponseEntity<?> crearClub(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String clubNombre = body.get("clubNombre");
        String clubNit = body.get("clubNit");
        String fechaCorteStr = body.get("fechaCorte");

        if (username == null || password == null || clubNombre == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username, password y clubNombre son obligatorios"));
        }

        LocalDate fechaCorte = (fechaCorteStr != null && !fechaCorteStr.isBlank())
                ? LocalDate.parse(fechaCorteStr)
                : LocalDate.now().plusMonths(1);

        AppUser nuevo = superAdminService.crearAdminClub(username, password, clubNombre, clubNit, fechaCorte);
        return ResponseEntity.ok(mapClubSeguro(nuevo));
    }

    /**
     * Actualiza los datos del club (nombre, NIT, fechaCorte).
     * Body esperado: { "clubNombre"?, "clubNit"?, "fechaCorte"? }
     */
    @PutMapping("/clubes/{id}")
    public ResponseEntity<?> editarClub(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String clubNombre = body.get("clubNombre");
        String clubNit = body.get("clubNit");
        String fechaCorteStr = body.get("fechaCorte");
        LocalDate fechaCorte = (fechaCorteStr != null && !fechaCorteStr.isBlank())
                ? LocalDate.parse(fechaCorteStr)
                : null;

        AppUser updated = superAdminService.editarClub(id, clubNombre, clubNit, fechaCorte);
        return ResponseEntity.ok(mapClubSeguro(updated));
    }

    /**
     * Suspende un club por mora.
     * Sus usuarios conservarán acceso únicamente para registrar asistencias.
     */
    @PutMapping("/clubes/{id}/suspender")
    public ResponseEntity<?> suspenderClub(@PathVariable Long id) {
        AppUser updated = superAdminService.suspenderClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Club suspendido por mora exitosamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    /**
     * Reactiva un club suspendido (pago regularizado).
     */
    @PutMapping("/clubes/{id}/activar")
    public ResponseEntity<?> activarClub(@PathVariable Long id) {
        AppUser updated = superAdminService.activarClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Club reactivado exitosamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    /**
     * Recalcula el plan/tramo SaaS de un club según deportistas activos actuales.
     */
    @PutMapping("/clubes/{id}/recalcular-plan")
    public ResponseEntity<?> recalcularPlan(@PathVariable Long id) {
        AppUser updated = superAdminService.recalcularPlanClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Plan recalculado correctamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    // ─────────────────────────────────────────────
    // Gestión de Tramos SaaS
    // ─────────────────────────────────────────────

    /**
     * Lista todos los tramos de precio SaaS.
     */
    @GetMapping("/planes")
    public ResponseEntity<List<SaasPlan>> listarPlanes() {
        return ResponseEntity.ok(superAdminService.listarPlanes());
    }

    /**
     * Actualiza el precio mensual de un tramo SaaS.
     * Body esperado: { "precioCopMensual": 200000 }
     */
    @PutMapping("/planes/{id}")
    public ResponseEntity<?> editarPlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object precioObj = body.get("precioCopMensual");
        if (precioObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "precioCopMensual es obligatorio"));
        }
        BigDecimal precio = new BigDecimal(precioObj.toString());
        SaasPlan updated = superAdminService.editarPrecioplan(id, precio);
        return ResponseEntity.ok(updated);
    }

    // ─────────────────────────────────────────────
    // Helper: Mapeo seguro de club (sin datos sensibles)
    // ─────────────────────────────────────────────

    /**
     * Convierte un AppUser a un mapa con solo los datos del CLUB como entidad.
     * NUNCA incluye: teléfonos, deportistas, transacciones, datos de padres.
     */
    private Map<String, Object> mapClubSeguro(AppUser admin) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", admin.getId());
        map.put("username", admin.getUsername());
        map.put("clubNombre", admin.getClubNombre());
        map.put("clubNit", admin.getClubNit());
        map.put("clubEstado", admin.getClubEstado() != null ? admin.getClubEstado().name() : "ACTIVO");
        map.put("planActual", admin.getPlanActual() != null ? admin.getPlanActual().name() : null);
        map.put("fechaCorte", admin.getFechaCorte());
        map.put("totalSedes", admin.getSedesAutorizadas() != null ? admin.getSedesAutorizadas().size() : 0);
        return map;
    }
}
