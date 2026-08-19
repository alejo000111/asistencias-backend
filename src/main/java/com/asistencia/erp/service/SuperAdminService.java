package com.asistencia.erp.service;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.SaasPlan;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.SaasPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Servicio de lógica de negocio exclusiva del SUPERADMIN.
 *
 * Responsabilidades:
 *   - Crear / editar / suspender / activar clubs (AppUser tipo ADMIN).
 *   - Recalcular el plan (tramo SaaS) de un club según deportistas activos.
 *   - Gestionar los tramos de precio SaaS (SaasPlan).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminService {

    private final AppUserRepository appUserRepository;
    private final ClubConfigRepository clubConfigRepository;
    private final SaasPlanRepository saasPlanRepository;
    private final PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────
    // Listado de clubs
    // ─────────────────────────────────────────────

    /**
     * Retorna todos los usuarios con rol ADMIN (clubs).
     * NO expone deportistas, padres ni transacciones financieras.
     */
    public List<AppUser> listarClubes() {
        return appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.ADMIN);
    }

    // ─────────────────────────────────────────────
    // Crear Admin de Club
    // ─────────────────────────────────────────────

    /**
     * Crea un nuevo administrador de club con los datos básicos del club.
     *
     * @param username    Nombre de usuario del nuevo ADMIN_CLUB.
     * @param password    Contraseña temporal en texto plano.
     * @param clubNombre  Nombre visible del club.
     * @param clubNit     NIT o identificación fiscal.
     * @param fechaCorte  Fecha de corte de la suscripción SaaS.
     */
    @Transactional
    public AppUser crearAdminClub(String username, String password,
                                   String clubNombre, String clubNit,
                                   LocalDate fechaCorte) {
        if (appUserRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("El username '" + username + "' ya existe.");
        }

        AppUser nuevoAdmin = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(AppUser.Role.ADMIN)
                .clubNombre(clubNombre)
                .clubNit(clubNit)
                .clubEstado(AppUser.ClubEstado.ACTIVO)
                .planActual(AppUser.PlanActual.TRAMO_1)
                .fechaCorte(fechaCorte)
                .build();

        AppUser saved = appUserRepository.save(nuevoAdmin);
        log.info("Club creado: username={}, clubNombre={}", username, clubNombre);
        return saved;
    }

    // ─────────────────────────────────────────────
    // Editar datos de un club
    // ─────────────────────────────────────────────

    /**
     * Actualiza los metadatos del club (nombre, NIT, fecha de corte).
     * No modifica el estado (para eso usar suspender/activar).
     */
    @Transactional
    public AppUser editarClub(Long adminId, String clubNombre, String clubNit, LocalDate fechaCorte) {
        AppUser admin = buscarAdminPorId(adminId);
        if (clubNombre != null && !clubNombre.isBlank()) admin.setClubNombre(clubNombre);
        if (clubNit != null && !clubNit.isBlank()) admin.setClubNit(clubNit);
        if (fechaCorte != null) admin.setFechaCorte(fechaCorte);
        return appUserRepository.save(admin);
    }

    // ─────────────────────────────────────────────
    // Suspender / Activar club (mora)
    // ─────────────────────────────────────────────

    /**
     * Marca el club como SUSPENDIDO_POR_MORA.
     * El SuspensionFilter bloqueará mutaciones financieras y de datos para ese club.
     */
    @Transactional
    public AppUser suspenderClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);
        admin.setClubEstado(AppUser.ClubEstado.SUSPENDIDO_POR_MORA);
        AppUser saved = appUserRepository.save(admin);
        log.warn("Club SUSPENDIDO por mora: adminId={}, clubNombre={}", adminId, admin.getClubNombre());
        return saved;
    }

    /**
     * Reactiva un club suspendido (pago regularizado).
     */
    @Transactional
    public AppUser activarClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);
        admin.setClubEstado(AppUser.ClubEstado.ACTIVO);
        AppUser saved = appUserRepository.save(admin);
        log.info("Club REACTIVADO: adminId={}, clubNombre={}", adminId, admin.getClubNombre());
        return saved;
    }

    // ─────────────────────────────────────────────
    // Recálculo automático del plan (tramo SaaS)
    // ─────────────────────────────────────────────

    /**
     * Cuenta los deportistas ACTIVO del club y asigna el tramo de precio correspondiente.
     * Se puede llamar periódicamente o al cambiar el estado de un deportista.
     */
    @Transactional
    public AppUser recalcularPlanClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);

        // Obtener IDs de sedes autorizadas del admin
        java.util.List<Long> sedeIds = admin.getSedesAutorizadas() == null
                ? java.util.Collections.emptyList()
                : admin.getSedesAutorizadas().stream()
                        .map(com.asistencia.erp.entity.Sede::getId)
                        .collect(java.util.stream.Collectors.toList());

        long totalActivos = sedeIds.isEmpty()
                ? 0
                : appUserRepository.countDeportistasActivosBySedes(sedeIds);

        // Buscar tramo correspondiente
        saasPlanRepository.findPlanParaCantidad((int) totalActivos).ifPresent(plan -> {
            AppUser.PlanActual nuevo = parsePlanActual(plan);
            if (nuevo != null && nuevo != admin.getPlanActual()) {
                admin.setPlanActual(nuevo);
                log.info("Plan recalculado para club {}: {} deportistas → {}",
                        admin.getClubNombre(), totalActivos, nuevo);
            }
        });

        return appUserRepository.save(admin);
    }

    // ─────────────────────────────────────────────
    // Gestión de tramos SaaS
    // ─────────────────────────────────────────────

    public List<SaasPlan> listarPlanes() {
        return saasPlanRepository.findAll();
    }

    @Transactional
    public SaasPlan editarPrecioplan(Long planId, BigDecimal nuevoPrecio) {
        SaasPlan plan = saasPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan no encontrado: " + planId));
        plan.setPrecioCopMensual(nuevoPrecio);
        return saasPlanRepository.save(plan);
    }

    // ─────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────

    private AppUser buscarAdminPorId(Long adminId) {
        AppUser admin = appUserRepository.findById(adminId)
                .orElseThrow(() -> new IllegalArgumentException("Admin no encontrado: " + adminId));
        if (admin.getRole() != AppUser.Role.ADMIN) {
            throw new IllegalArgumentException("El usuario " + adminId + " no tiene rol ADMIN.");
        }
        return admin;
    }

    private AppUser.PlanActual parsePlanActual(SaasPlan plan) {
        if (plan.getLimiteInferior() <= 20) return AppUser.PlanActual.TRAMO_1;
        if (plan.getLimiteInferior() <= 40) return AppUser.PlanActual.TRAMO_2;
        return AppUser.PlanActual.TRAMO_3;
    }
}
