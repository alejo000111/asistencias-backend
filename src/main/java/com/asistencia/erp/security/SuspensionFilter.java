package com.asistencia.erp.security;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Filtro de bloqueo granular por mora (FASE 2 — SUSPENDIDO_POR_MORA).
 *
 * Se ejecuta después de JwtAuthFilter, por lo que el SecurityContextHolder
 * ya tiene el principal autenticado.
 *
 * Comportamiento cuando el club está SUSPENDIDO_POR_MORA:
 *   ✅ PERMITE → POST /api/finanzas/asistencia  (registro en cancha)
 *   ✅ PERMITE → GET  /**                        (consultas de solo lectura)
 *   ✅ PERMITE → /api/auth/**                    (login/logout)
 *   ✅ PERMITE → /api/public/**                  (portal padres)
 *   ❌ BLOQUEA → POST/PUT/DELETE /api/finanzas/abono
 *   ❌ BLOQUEA → POST/PUT/DELETE /api/clientes/**
 *   ❌ BLOQUEA → POST/PUT/DELETE /api/registro/**
 *   ❌ BLOQUEA → POST/PUT/DELETE /api/sedes/**
 *   ❌ BLOQUEA → POST/PUT/DELETE /api/empleados/**
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuspensionFilter extends OncePerRequestFilter {

    private final AppUserRepository appUserRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        JwtUserPrincipal principal = SecurityUtils.getCurrentUser();

        // Solo aplica a ADMIN o EMPLEADO de un club (no a SUPERADMIN ni usuarios no autenticados)
        if (principal == null ||
            "SUPERADMIN".equals(principal.getRole()) ||
            "SUPERADMIN".equalsIgnoreCase(principal.getRole())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Verificar si el usuario tiene un club suspendido
        if (!esMutacionBloqueada(request)) {
            // Las operaciones GET y rutas exentas pasan siempre
            filterChain.doFilter(request, response);
            return;
        }

        // Es una mutación potencialmente bloqueada. Verificar estado del club.
        AppUser user = appUserRepository.findById(principal.getUserId()).orElse(null);
        if (user == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Si el rol es EMPLEADO, buscar el admin dueño del club (por sedes compartidas)
        AppUser clubAdmin = resolverAdminDelClub(user, principal);

        // Chequeo en caliente: si la fechaCorte ya venció y el club sigue marcado ACTIVO,
        // se suspende inmediatamente aquí mismo (sin esperar al barrido programado de
        // SuperAdminService), para que el bloqueo sea efectivo desde el primer request
        // del día en que se cumple el plazo. Los clubs exentos (sin fechaCorte) nunca entran aquí.
        if (clubAdmin != null &&
            clubAdmin.getClubEstado() == AppUser.ClubEstado.ACTIVO &&
            !Boolean.TRUE.equals(clubAdmin.getExentoTarifa()) &&
            clubAdmin.getFechaCorte() != null &&
            clubAdmin.getFechaCorte().isBefore(LocalDate.now())) {
            clubAdmin.setClubEstado(AppUser.ClubEstado.SUSPENDIDO_POR_MORA);
            clubAdmin = appUserRepository.save(clubAdmin);
            log.warn("Club auto-suspendido en caliente por fechaCorte vencida — adminId={}, fechaCorte={}",
                    clubAdmin.getId(), clubAdmin.getFechaCorte());
        }

        if (clubAdmin != null &&
            AppUser.ClubEstado.SUSPENDIDO_POR_MORA.equals(clubAdmin.getClubEstado())) {

            // Registrar asistencia es la ÚNICA operación permitida en estado suspendido
            if (esRegistroDeAsistencia(request)) {
                filterChain.doFilter(request, response);
                return;
            }

            log.warn("Operación bloqueada por mora — user={}, path={} {}",
                    principal.getUsername(), request.getMethod(), request.getRequestURI());

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":\"Plazo vencido\",\"mensaje\":" +
                "\"Tu club está suspendido por plazo vencido. Por favor contacta a Alejandro para regularizar el pago y reactivar tu cuenta. Puedes seguir registrando asistencias y consultando la información normalmente.\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Determina si la petición es una mutación que podría bloquearse.
     * Las peticiones GET y las rutas públicas/auth siempre pasan.
     */
    private boolean esMutacionBloqueada(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // Solo bloqueamos mutaciones (POST, PUT, DELETE)
        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }

        // Rutas que siempre pasan sin importar el estado de mora
        if (path.startsWith("/api/auth/") ||
            path.startsWith("/api/public/") ||
            path.startsWith("/api/config/") ||
            path.startsWith("/api/superadmin/")) {
            return false;
        }

        // Mutaciones financieras/administrativas restringidas estrictamente en mora
        // (ver cabecera de la clase para el listado completo de rutas bloqueadas).
        // El registro de asistencias/cortesías se excluye explícitamente más abajo vía esRegistroDeAsistencia().
        return path.startsWith("/api/finanzas/") ||
               path.startsWith("/api/clientes/") ||
               path.startsWith("/api/registro/") ||
               path.startsWith("/api/sedes/") ||
               path.startsWith("/api/empleados/");
    }

    /**
     * Identifica si la petición es específicamente un registro de asistencia o cortesía.
     * Ambas operaciones están PERMITIDAS incluso en estado suspendido (operativa de campo).
     */
    private boolean esRegistroDeAsistencia(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if (!"POST".equalsIgnoreCase(method)) return false;
        // Asistencia regular: POST /api/finanzas/asistencia
        // Clase de cortesía: POST /api/finanzas/cortesia (NO /cortesia/{id}/convertir)
        return uri.startsWith("/api/finanzas/asistencia")
                || (uri.startsWith("/api/finanzas/cortesia") && !uri.contains("/convertir"));
    }

    /**
     * Resuelve el AppUser ADMIN del club al que pertenece el usuario autenticado.
     * - Si el usuario es ADMIN, se retorna a sí mismo.
     * - Si el usuario es EMPLEADO, busca el ADMIN que comparte sus sedes.
     */
    private AppUser resolverAdminDelClub(AppUser user, JwtUserPrincipal principal) {
        if (user.getRole() == AppUser.Role.ADMIN) {
            return user;
        }

        // Para EMPLEADO: encontrar el ADMIN que tenga las mismas sedes autorizadas
        if (principal.getSedesAutorizadas() == null || principal.getSedesAutorizadas().isEmpty()) {
            return null;
        }

        // Buscar todos los ADMIN y filtrar el que comparte sedes con el empleado
        return appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.ADMIN)
                .stream()
                .filter(admin -> admin.getSedesAutorizadas() != null &&
                        admin.getSedesAutorizadas().stream()
                                .anyMatch(s -> principal.getSedesAutorizadas().contains(s.getId())))
                .findFirst()
                .orElse(null);
    }
}
