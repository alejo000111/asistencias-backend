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
                "{\"error\":\"Club suspendido por mora\",\"mensaje\":" +
                "\"Tu club está suspendido por falta de pago. Solo puedes registrar asistencias. " +
                "Contacta a soporte para regularizar el pago.\"}"
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

        // Rutas que siempre pasan sin importar el estado (auth, portal público)
        if (path.startsWith("/api/auth/") || path.startsWith("/api/public/")) {
            return false;
        }

        // Rutas de mutación financiera o de datos
        return path.startsWith("/api/finanzas/") ||
               path.startsWith("/api/clientes/") ||
               path.startsWith("/api/registro/") ||
               path.startsWith("/api/sedes/") ||
               path.startsWith("/api/empleados/");
    }

    /**
     * Identifica si la petición es específicamente un registro de asistencia.
     * Esta operación está PERMITIDA incluso en estado suspendido.
     */
    private boolean esRegistroDeAsistencia(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) &&
               request.getRequestURI().equals("/api/finanzas/asistencia");
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
