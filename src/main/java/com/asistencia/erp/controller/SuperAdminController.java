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

    @GetMapping("/clubes/{id}")
    public ResponseEntity<Map<String, Object>> obtenerClub(@PathVariable Long id) {
        AppUser admin = superAdminService.listarClubes().stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Club no encontrado: " + id));
        return ResponseEntity.ok(mapClubSeguro(admin));
    }

    /**
     * Métricas de privacidad del club (Acción "Más Información").
     */
    @GetMapping("/clubes/{id}/metricas")
    public ResponseEntity<?> obtenerMetricasClub(@PathVariable Long id) {
        Map<String, Object> metricas = superAdminService.obtenerMetricasClub(id);
        return ResponseEntity.ok(metricas);
    }

    @PostMapping("/clubes")
    public ResponseEntity<?> crearClub(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String clubNombre = body.get("clubNombre");
        String nombreAdministrador = body.get("nombreAdministrador");
        String clubNit = body.get("clubNit");
        String fechaCorteStr = body.get("fechaCorte");
        String planInicialStr = body.get("planActual");
        Boolean exentoTarifa = Boolean.parseBoolean(body.get("exentoTarifa"));

        if (username == null || password == null || clubNombre == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username, password y clubNombre son obligatorios"));
        }

        LocalDate fechaCorte = (fechaCorteStr != null && !fechaCorteStr.isBlank())
                ? LocalDate.parse(fechaCorteStr)
                : LocalDate.now().plusMonths(1);

        AppUser.PlanActual planInicial = parsePlanActualSafely(planInicialStr);

        try {
            AppUser nuevo = superAdminService.crearAdminClub(username, password, clubNombre, nombreAdministrador, clubNit, fechaCorte, planInicial, exentoTarifa);
            return ResponseEntity.ok(mapClubSeguro(nuevo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/clubes/{id}")
    public ResponseEntity<?> editarClub(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String clubNombre = body.get("clubNombre");
        String nombreAdministrador = body.get("nombreAdministrador");
        String clubNit = body.get("clubNit");
        String fechaCorteStr = body.get("fechaCorte");
        String planActualStr = body.get("planActual");
        String username = body.get("username");
        String password = body.get("password");
        String exentoTarifaStr = body.get("exentoTarifa");
        Boolean exentoTarifa = exentoTarifaStr != null ? Boolean.parseBoolean(exentoTarifaStr) : null;

        LocalDate fechaCorte = (fechaCorteStr != null && !fechaCorteStr.isBlank())
                ? LocalDate.parse(fechaCorteStr)
                : null;

        AppUser.PlanActual planActual = (planActualStr != null && !planActualStr.isBlank())
                ? parsePlanActualSafely(planActualStr)
                : null;

        try {
            AppUser updated = superAdminService.editarClub(id, clubNombre, nombreAdministrador, clubNit, fechaCorte, planActual, username, password, exentoTarifa);
            return ResponseEntity.ok(mapClubSeguro(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/clubes/{id}/suspender")
    public ResponseEntity<?> suspenderClub(@PathVariable Long id) {
        AppUser updated = superAdminService.suspenderClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Club suspendido por mora exitosamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    @PutMapping("/clubes/{id}/activar")
    public ResponseEntity<?> activarClub(@PathVariable Long id) {
        AppUser updated = superAdminService.activarClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Club reactivado exitosamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    @DeleteMapping("/clubes/{id}")
    public ResponseEntity<?> eliminarClub(@PathVariable Long id) {
        try {
            superAdminService.eliminarClub(id);
            return ResponseEntity.ok(Map.of("mensaje", "Club eliminado permanentemente."));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/clubes/{id}/recalcular-plan")
    public ResponseEntity<?> recalcularPlan(@PathVariable Long id) {
        AppUser updated = superAdminService.recalcularPlanClub(id);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Plan recalculado correctamente.",
                "club", mapClubSeguro(updated)
        ));
    }

    // ─────────────────────────────────────────────
    // Gestión Dinámica de Tramos SaaS
    // ─────────────────────────────────────────────

    @GetMapping("/planes")
    public ResponseEntity<List<SaasPlan>> listarPlanes() {
        return ResponseEntity.ok(superAdminService.listarPlanes());
    }

    @PostMapping("/planes")
    public ResponseEntity<?> crearPlan(@RequestBody Map<String, Object> body) {
        String nombre = (String) body.get("nombre");
        Integer limiteInferior = body.get("limiteInferior") != null ? Integer.parseInt(body.get("limiteInferior").toString()) : 1;
        Integer limiteSuperior = body.get("limiteSuperior") != null && !body.get("limiteSuperior").toString().isBlank()
                ? Integer.parseInt(body.get("limiteSuperior").toString()) : null;
        BigDecimal precioCopMensual = new BigDecimal(body.get("precioCopMensual").toString());

        SaasPlan creado = superAdminService.crearPlan(nombre, limiteInferior, limiteSuperior, precioCopMensual);
        return ResponseEntity.ok(creado);
    }

    @PutMapping("/planes/{id}")
    public ResponseEntity<?> editarPlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String nombre = (String) body.get("nombre");
        Integer limiteInferior = body.get("limiteInferior") != null ? Integer.parseInt(body.get("limiteInferior").toString()) : null;
        Integer limiteSuperior = body.get("limiteSuperior") != null && !body.get("limiteSuperior").toString().isBlank()
                ? Integer.parseInt(body.get("limiteSuperior").toString()) : null;
        BigDecimal precioCopMensual = body.get("precioCopMensual") != null ? new BigDecimal(body.get("precioCopMensual").toString()) : null;

        SaasPlan updated = superAdminService.editarPlanFull(id, nombre, limiteInferior, limiteSuperior, precioCopMensual);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/planes/{id}")
    public ResponseEntity<?> eliminarPlan(@PathVariable Long id) {
        superAdminService.eliminarPlan(id);
        return ResponseEntity.ok(Map.of("mensaje", "Plan eliminado exitosamente"));
    }

    // ─────────────────────────────────────────────
    // Gestión Global de Usuarios (Sección 4)
    // ─────────────────────────────────────────────

    @GetMapping("/usuarios")
    public ResponseEntity<List<Map<String, Object>>> listarTodosUsuarios() {
        List<AppUser> usuarios = superAdminService.listarTodosUsuarios();
        List<Map<String, Object>> response = usuarios.stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("nombreCompleto", u.getNombreCompleto() != null ? u.getNombreCompleto() : u.getUsername());
            m.put("username", u.getUsername());
            m.put("role", u.getRole() != null ? u.getRole().name() : "EMPLEADO");
            m.put("clubNombre", u.getClubNombre());
            m.put("clubId", u.getRole() == AppUser.Role.ADMIN ? u.getId() : u.getClubId());
            m.put("nombreAdministrador", u.getNombreAdministrador());
            m.put("clubEstado", u.getClubEstado() != null ? u.getClubEstado().name() : "ACTIVO");
            m.put("totalSedes", u.getSedesAutorizadas() != null ? u.getSedesAutorizadas().size() : 0);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/usuarios")
    public ResponseEntity<?> crearUsuarioGlobal(@RequestBody Map<String, Object> body) {
        String nombreCompleto = (String) body.get("nombreCompleto");
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String roleStr = (String) body.get("role");
        String clubNombre = (String) body.get("clubNombre");
        Object clubIdObj = body.get("clubId");
        Long clubId = (clubIdObj != null && !clubIdObj.toString().isBlank()) ? Long.valueOf(clubIdObj.toString()) : null;

        if (username == null || password == null || roleStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username, password y role son obligatorios"));
        }

        String lowerUser = username.trim().toLowerCase();
        java.util.Set<String> reservados = java.util.Set.of("superadmin", "root", "admin", "system", "null", "undefined");
        if (reservados.contains(lowerUser)) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre de usuario '" + username + "' es una palabra reservada del sistema."));
        }

        AppUser.Role role = AppUser.Role.valueOf(roleStr);
        try {
            AppUser nuevo = superAdminService.crearUsuarioGlobal(nombreCompleto, username, password, role, clubNombre, clubId);
            return ResponseEntity.ok(Map.of("id", nuevo.getId(), "nombreCompleto", nuevo.getNombreCompleto() != null ? nuevo.getNombreCompleto() : nuevo.getUsername(), "username", nuevo.getUsername(), "role", nuevo.getRole().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/usuarios/{id}")
    public ResponseEntity<?> editarUsuarioGlobal(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String nombreCompleto = (String) body.get("nombreCompleto");
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String roleStr = (String) body.get("role");
        Object clubIdObj = body.get("clubId");

        AppUser.Role role = (roleStr != null && !roleStr.isBlank()) ? AppUser.Role.valueOf(roleStr) : null;
        Long clubId = (clubIdObj != null && !clubIdObj.toString().isBlank()) ? Long.valueOf(clubIdObj.toString()) : null;

        try {
            AppUser updated = superAdminService.editarUsuarioGlobal(id, nombreCompleto, username, password, role, clubId);
            Map<String, Object> m = new HashMap<>();
            m.put("id", updated.getId());
            m.put("nombreCompleto", updated.getNombreCompleto() != null ? updated.getNombreCompleto() : updated.getUsername());
            m.put("username", updated.getUsername());
            m.put("role", updated.getRole().name());
            m.put("clubNombre", updated.getClubNombre());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/usuarios/{id}")
    public ResponseEntity<?> eliminarUsuarioGlobal(@PathVariable Long id) {
        try {
            superAdminService.eliminarUsuarioGlobal(id);
            return ResponseEntity.ok(Map.of("mensaje", "Usuario eliminado exitosamente."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/usuarios/{id}/rol")
    public ResponseEntity<?> cambiarRolUsuario(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String nuevoRolStr = body.get("role");
        if (nuevoRolStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "role es obligatorio"));
        }
        AppUser.Role nuevoRol = AppUser.Role.valueOf(nuevoRolStr);
        AppUser updated = superAdminService.cambiarRolUsuario(id, nuevoRol);
        return ResponseEntity.ok(Map.of("id", updated.getId(), "username", updated.getUsername(), "role", updated.getRole().name()));
    }

    // ─────────────────────────────────────────────
    // Helper: Mapeo seguro de club (sin datos sensibles)
    // ─────────────────────────────────────────────

    private Map<String, Object> mapClubSeguro(AppUser admin) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", admin.getId());
        map.put("username", admin.getUsername());
        map.put("clubNombre", admin.getClubNombre());
        map.put("nombreAdministrador", admin.getNombreAdministrador());
        map.put("clubNit", admin.getClubNit());
        map.put("clubEstado", admin.getClubEstado() != null ? admin.getClubEstado().name() : "ACTIVO");
        map.put("planActual", admin.getPlanActual() != null ? admin.getPlanActual().name() : null);
        map.put("fechaCorte", admin.getFechaCorte());
        map.put("exentoTarifa", Boolean.TRUE.equals(admin.getExentoTarifa()));
        map.put("totalSedes", admin.getSedesAutorizadas() != null ? admin.getSedesAutorizadas().size() : 0);
        return map;
    }

    private AppUser.PlanActual parsePlanActualSafely(String planStr) {
        if (planStr == null || planStr.isBlank()) return AppUser.PlanActual.TRAMO_1;
        try {
            return AppUser.PlanActual.valueOf(planStr.trim());
        } catch (Exception ignored) {
            String lower = planStr.toLowerCase();
            if (lower.contains("tramo_2") || lower.contains("pro") || lower.contains("intermedio")) return AppUser.PlanActual.TRAMO_2;
            if (lower.contains("tramo_3") || lower.contains("master") || lower.contains("elite") || lower.contains("avanzado")) return AppUser.PlanActual.TRAMO_3;
            return AppUser.PlanActual.TRAMO_1;
        }
    }
}
