package com.asistencia.erp.service;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.CompraPaquete;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.SaasPlan;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.CompraPaqueteRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ImportBatchLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SaasPlanRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
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
    private final ParentRepository parentRepository;
    private final SedeRepository sedeRepository;
    private final FinancialLogRepository financialLogRepository;
    private final CompraPaqueteRepository compraPaqueteRepository;
    private final ImportBatchLogRepository importBatchLogRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;

    // ─────────────────────────────────────────────
    // Listado de clubs
    // ─────────────────────────────────────────────

    public List<AppUser> listarClubes() {
        return appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.ADMIN);
    }

    // ─────────────────────────────────────────────
    // Crear Admin de Club
    // ─────────────────────────────────────────────

    @Transactional
    public AppUser crearAdminClub(String username, String password,
                                   String clubNombre, String nombreAdministrador,
                                   String clubNit, LocalDate fechaCorte,
                                   AppUser.PlanActual planInicial) {
        return crearAdminClub(username, password, clubNombre, nombreAdministrador, clubNit, fechaCorte, planInicial, false);
    }

    @Transactional
    public AppUser crearAdminClub(String username, String password,
                                   String clubNombre, String nombreAdministrador,
                                   String clubNit, LocalDate fechaCorte,
                                   AppUser.PlanActual planInicial, Boolean exentoTarifa) {
        if (appUserRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("El username '" + username + "' ya existe.");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
        }

        boolean exento = Boolean.TRUE.equals(exentoTarifa);

        AppUser nuevoAdmin = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(AppUser.Role.ADMIN)
                .clubNombre(clubNombre)
                .nombreAdministrador(nombreAdministrador)
                // "Clubs Registrados" y "Usuarios Registrados" deben mostrar siempre el
                // mismo nombre para un ADMIN — ver §7 del backfill en SchemaInitializer.
                .nombreCompleto(nombreAdministrador)
                .clubNit(clubNit)
                .clubEstado(AppUser.ClubEstado.ACTIVO)
                .planActual(planInicial != null ? planInicial : AppUser.PlanActual.TRAMO_1)
                .exentoTarifa(exento)
                .fechaCorte(exento ? null : (fechaCorte != null ? fechaCorte : LocalDate.now().plusMonths(1)))
                .build();

        AppUser saved = appUserRepository.save(nuevoAdmin);
        log.info("Club creado: username={}, clubNombre={}, admin={}", username, clubNombre, nombreAdministrador);
        return saved;
    }

    // ─────────────────────────────────────────────
    // Editar datos de un club (incluye planActual y nombreAdministrador)
    // ─────────────────────────────────────────────

    @Transactional
    public AppUser editarClub(Long adminId, String clubNombre, String nombreAdministrador,
                              String clubNit, LocalDate fechaCorte, AppUser.PlanActual planActual) {
        return editarClub(adminId, clubNombre, nombreAdministrador, clubNit, fechaCorte, planActual,
                null, null, null);
    }

    /**
     * Edición completa de un club desde "Clubs Registrados": datos del club/plan y,
     * opcionalmente, las credenciales del ADMIN dueño (username/password) — antes solo
     * se podían cambiar desde "Usuarios Registrados", separado del resto de la ficha del club.
     */
    @Transactional
    public AppUser editarClub(Long adminId, String clubNombre, String nombreAdministrador,
                              String clubNit, LocalDate fechaCorte, AppUser.PlanActual planActual,
                              String username, String password, Boolean exentoTarifa) {
        AppUser admin = buscarAdminPorId(adminId);
        boolean nombreCambio = clubNombre != null && !clubNombre.isBlank() && !clubNombre.equals(admin.getClubNombre());
        if (clubNombre != null && !clubNombre.isBlank()) admin.setClubNombre(clubNombre);
        if (nombreAdministrador != null) {
            admin.setNombreAdministrador(nombreAdministrador);
            // Mantener sincronizado con "Usuarios Registrados" (nombre_completo) — ver
            // backfill en SchemaInitializer y nota en crearAdminClub.
            admin.setNombreCompleto(nombreAdministrador);
        }
        if (clubNit != null) admin.setClubNit(clubNit);

        if (exentoTarifa != null) {
            admin.setExentoTarifa(exentoTarifa);
            if (exentoTarifa) {
                admin.setFechaCorte(null);
            } else if (fechaCorte != null) {
                admin.setFechaCorte(fechaCorte);
            } else if (admin.getFechaCorte() == null) {
                // Se está desmarcando "exento" (que dejaba fechaCorte en null) sin indicar una
                // fecha nueva: un club NO exento no puede quedarse sin fechaCorte, o nunca se
                // suspendería por mora — se asigna un valor por defecto razonable (+1 mes) en
                // vez de dejarlo en blanco.
                admin.setFechaCorte(LocalDate.now().plusMonths(1));
            }
        } else if (fechaCorte != null) {
            admin.setFechaCorte(fechaCorte);
        }

        if (planActual != null) admin.setPlanActual(planActual);

        if (username != null && !username.isBlank()) {
            String trimmed = username.trim();
            if (!trimmed.equalsIgnoreCase(admin.getUsername())) {
                appUserRepository.findByUsername(trimmed).ifPresent(existing -> {
                    if (!existing.getId().equals(adminId)) {
                        throw new IllegalArgumentException("El username '" + trimmed + "' ya está en uso.");
                    }
                });
                admin.setUsername(trimmed);
            }
        }
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
            }
            admin.setPasswordHash(passwordEncoder.encode(password));
        }

        // Reflejar de inmediato en el estado del club el resultado de la fechaCorte/exención
        // que se acaba de guardar — así el SUPERADMIN ve el efecto al instante en su propio
        // panel, sin depender del barrido programado ni del chequeo en caliente de
        // SuspensionFilter (que no aplica a él, solo a ADMIN/EMPLEADO logueados).
        actualizarEstadoSegunFechaCorte(admin);

        AppUser saved = appUserRepository.save(admin);

        // El nombre del club se guarda de forma denormalizada en cada empleado (clubNombre).
        // Al renombrar el club hay que propagar el cambio a todos sus empleados,
        // de lo contrario quedan mostrando el nombre viejo (aparentan estar "sin club").
        if (nombreCambio) {
            List<AppUser> empleados = appUserRepository.findByClubId(admin.getId());
            for (AppUser empleado : empleados) {
                empleado.setClubNombre(saved.getClubNombre());
            }
            appUserRepository.saveAll(empleados);
        }

        return saved;
    }

    // ─────────────────────────────────────────────
    // Suspender / Activar club (mora)
    // ─────────────────────────────────────────────

    @Transactional
    public AppUser suspenderClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);
        admin.setClubEstado(AppUser.ClubEstado.SUSPENDIDO_POR_MORA);
        AppUser saved = appUserRepository.save(admin);
        log.warn("Club SUSPENDIDO por mora: adminId={}, clubNombre={}", adminId, admin.getClubNombre());
        return saved;
    }

    /**
     * Reactivación MANUAL desde el botón "Reactivar Club". Un club con fechaCorte vencida
     * (y no exento) no se puede reactivar así no más — de lo contrario quedaría "Activo" con
     * una fecha de corte en el pasado y se auto-suspendería de nuevo en el siguiente chequeo,
     * confundiendo al SUPERADMIN. Primero hay que fijar una fechaCorte futura o marcar el club
     * como exento de tarifa (ambos vía "Editar Club", que sí reactiva automáticamente).
     */
    @Transactional
    public AppUser activarClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);
        boolean exento = Boolean.TRUE.equals(admin.getExentoTarifa());
        if (!exento && (admin.getFechaCorte() == null || admin.getFechaCorte().isBefore(LocalDate.now()))) {
            throw new IllegalArgumentException(
                "No puedes activar este club: su fecha de corte ya venció. " +
                "Edita el club y define una fecha de corte válida (futura), o márcalo como exento de tarifa."
            );
        }
        admin.setClubEstado(AppUser.ClubEstado.ACTIVO);
        AppUser saved = appUserRepository.save(admin);
        log.info("Club REACTIVADO: adminId={}, clubNombre={}", adminId, admin.getClubNombre());
        return saved;
    }

    // ─────────────────────────────────────────────
    // Auto-suspensión / auto-reactivación por fecha de corte
    // ─────────────────────────────────────────────

    /**
     * Ajusta el clubEstado de un único admin según su fechaCorte/exención actuales, sin
     * persistir (el caller decide cuándo guardar). Reglas:
     *   - Exento, o fechaCorte hoy/futura, y estaba SUSPENDIDO_POR_MORA → se reactiva solo.
     *   - No exento, fechaCorte vencida, y estaba ACTIVO → se suspende solo.
     * Una suspensión/activación manual del SUPERADMIN que no tenga que ver con la fecha de
     * corte (ej. reactivar aunque siga vencida) no es posible por esta vía — ver activarClub().
     */
    private void actualizarEstadoSegunFechaCorte(AppUser admin) {
        boolean exento = Boolean.TRUE.equals(admin.getExentoTarifa());
        // Un club NO exento sin fechaCorte es un estado inconsistente (dato faltante, nunca
        // "vigente" por defecto) — de lo contrario quedaría activo para siempre sin poder
        // volver a suspenderse por mora. Solo un club exento puede legítimamente no tener fecha.
        boolean fechaVigente = exento || (admin.getFechaCorte() != null && !admin.getFechaCorte().isBefore(LocalDate.now()));

        if (admin.getClubEstado() == AppUser.ClubEstado.SUSPENDIDO_POR_MORA && fechaVigente) {
            admin.setClubEstado(AppUser.ClubEstado.ACTIVO);
            log.info("Club auto-reactivado (fechaCorte vigente o exento): adminId={}, clubNombre={}", admin.getId(), admin.getClubNombre());
        } else if (admin.getClubEstado() == AppUser.ClubEstado.ACTIVO && !fechaVigente) {
            admin.setClubEstado(AppUser.ClubEstado.SUSPENDIDO_POR_MORA);
            log.warn("Club auto-suspendido (fechaCorte vencida): adminId={}, clubNombre={}, fechaCorte={}",
                    admin.getId(), admin.getClubNombre(), admin.getFechaCorte());
        }
    }

    /**
     * Recorre todos los clubs y aplica {@link #actualizarEstadoSegunFechaCorte} a cada uno:
     * suspende a los vencidos que seguían ACTIVO y reactiva a los que ya tienen fechaCorte
     * vigente (o quedaron exentos) pero seguían SUSPENDIDO_POR_MORA. Se ejecuta:
     *   - Al arrancar el backend y luego cada 6 horas ({@link #revisarClubesVencidosScheduled()}).
     *   - En caliente, desde {@code SuspensionFilter} en cada request de un ADMIN/EMPLEADO
     *     (solo la dirección de suspensión, ver esa clase), para que sea efectiva el mismo
     *     día sin esperar al cron.
     *   - Al instante desde editarClub() para el propio panel del SUPERADMIN.
     */
    @Transactional
    public void revisarYSuspenderClubesVencidos() {
        List<AppUser> admins = appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.ADMIN);
        for (AppUser admin : admins) {
            AppUser.ClubEstado antes = admin.getClubEstado();
            actualizarEstadoSegunFechaCorte(admin);
            if (admin.getClubEstado() != antes) {
                appUserRepository.save(admin);
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            initialDelay = 60_000L, fixedRate = 6 * 60 * 60 * 1000L)
    public void revisarClubesVencidosScheduled() {
        try {
            revisarYSuspenderClubesVencidos();
        } catch (Exception e) {
            log.error("Error en revisión programada de clubes vencidos: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────
    // Eliminar club permanentemente (solo si está SUSPENDIDO_POR_MORA)
    // Mismo patrón que eliminarFamilia(): primero "inactivar" (suspender), luego borrar.
    // ─────────────────────────────────────────────

    @Transactional
    public void eliminarClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);

        if (admin.getClubEstado() != AppUser.ClubEstado.SUSPENDIDO_POR_MORA) {
            throw new IllegalStateException("Debes suspender el club por mora antes de poder eliminarlo permanentemente.");
        }

        // El club_id de las entidades del negocio siempre es el propio id del ADMIN dueño.
        Long clubId = admin.getId();

        // 0. Asistencias del club, incluidas las "huérfanas" (student_id NULL de deportistas
        // eliminados individualmente en el pasado) que aún referencian una sede — de lo
        // contrario el FK de sede_id impediría borrar las sedes en el paso 3.
        attendanceRepository.deleteAll(attendanceRepository.findAllWithFetch(clubId));

        // 1. Padres (cascada JPA: Parent -> Student -> Enrollment/Attendance, orphanRemoval=true)
        List<Parent> padres = parentRepository.findByClubId(clubId);
        List<Long> parentIds = padres.stream().map(Parent::getId).toList();

        // 1a. Compras de paquete de esos padres (FK obligatoria a Parent, sin cascada propia)
        List<CompraPaquete> compras = compraPaqueteRepository.findByParentIdIn(parentIds);
        compraPaqueteRepository.deleteAll(compras);

        // 1b. Bitácora financiera del club (incluye la de los padres eliminados)
        List<FinancialLog> logs = financialLogRepository.findByClubId(clubId);
        financialLogRepository.deleteAll(logs);

        parentRepository.deleteAll(padres);

        // 2. Configuración de cobros del club (cascada elimina TarifaSede)
        clubConfigRepository.findByAdminId(clubId).ifPresent(clubConfigRepository::delete);

        // 3. Desvincular sedes de admin/empleados antes de borrarlas (tabla join app_user_sedes)
        List<AppUser> empleados = appUserRepository.findByClubId(clubId);
        List<Sede> sedes = sedeRepository.findByClubId(clubId);
        if (admin.getSedesAutorizadas() != null) admin.getSedesAutorizadas().clear();
        appUserRepository.save(admin);
        for (AppUser empleado : empleados) {
            if (empleado.getSedesAutorizadas() != null) empleado.getSedesAutorizadas().clear();
            appUserRepository.save(empleado);
        }
        sedeRepository.deleteAll(sedes);

        // 4. Empleados del club
        appUserRepository.deleteAll(empleados);

        // 5. Bitácora de importaciones Excel (los ElementCollection asociados se borran en cascada)
        importBatchLogRepository.deleteAll(importBatchLogRepository.findByClubId(clubId));

        // 6. Finalmente, el propio ADMIN dueño del club
        appUserRepository.delete(admin);

        log.warn("Club ELIMINADO PERMANENTEMENTE: adminId={}, clubNombre={}", clubId, admin.getClubNombre());
    }

    // ─────────────────────────────────────────────
    // Métricas Privadas del Club (Acción "Más Información")
    // ─────────────────────────────────────────────

    public java.util.Map<String, Object> obtenerMetricasClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);

        List<Long> sedeIds = admin.getSedesAutorizadas() == null
                ? java.util.Collections.emptyList()
                : admin.getSedesAutorizadas().stream()
                        .map(com.asistencia.erp.entity.Sede::getId)
                        .collect(java.util.stream.Collectors.toList());

        // 1. Cantidad de admins del club
        long cantAdmins = 1; // El admin principal

        // 2. Cantidad de entrenadores (EMPLEADO) que comparten sedes con este admin
        long cantEntrenadores = appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.EMPLEADO).stream()
                .filter(emp -> emp.getSedesAutorizadas() != null &&
                        emp.getSedesAutorizadas().stream().anyMatch(s -> sedeIds.contains(s.getId())))
                .count();

        // 3. Cantidad de deportistas activos — por club_id directo, no por sedesAutorizadas
        // (que puede quedar vacío/incompleto si el club solo tiene sedes auto-creadas por
        // importación de Excel, subestimando el conteo real).
        long cantDeportistasActivos = studentRepository.countByClubIdAndEstado(admin.getId(), Student.StudentStatus.ACTIVO);

        // 4. Espacio estimado consumido en la base de datos (MB)
        // Cálculo basado en registros asociados (promedio ~1KB por deportista/asistencia)
        double estimacionBytes = (cantDeportistasActivos * 2048) + (cantEntrenadores * 4096) + 102400;
        double espacioMb = Math.round((estimacionBytes / (1024.0 * 1024.0)) * 100.0) / 100.0;
        if (espacioMb < 0.01) espacioMb = 0.05; // Mínimo representativo

        java.util.Map<String, Object> metricas = new java.util.HashMap<>();
        metricas.put("clubId", admin.getId());
        metricas.put("clubNombre", admin.getClubNombre());
        metricas.put("nombreAdministrador", admin.getNombreAdministrador());
        metricas.put("cantAdmins", cantAdmins);
        metricas.put("cantEntrenadores", cantEntrenadores);
        metricas.put("cantDeportistasActivos", cantDeportistasActivos);
        metricas.put("espacioMb", espacioMb);
        return metricas;
    }

    // ─────────────────────────────────────────────
    // Recálculo automático del plan (tramo SaaS)
    // ─────────────────────────────────────────────

    @Transactional
    public AppUser recalcularPlanClub(Long adminId) {
        AppUser admin = buscarAdminPorId(adminId);

        // Conteo por club_id directo — ver nota en obtenerMetricasClub. Recalcular el tramo de
        // cobro SaaS con un conteo incompleto (por sedesAutorizadas vacío) podía subestimar la
        // cantidad real de deportistas activos y dejar a un club facturando en un tramo inferior
        // al que realmente le corresponde.
        long totalActivos = studentRepository.countByClubIdAndEstado(admin.getId(), Student.StudentStatus.ACTIVO);

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
    // Gestión Dinámica de Tramos SaaS
    // ─────────────────────────────────────────────

    public List<SaasPlan> listarPlanes() {
        return saasPlanRepository.findAll();
    }

    @Transactional
    public SaasPlan crearPlan(String nombre, Integer limiteInferior, Integer limiteSuperior, BigDecimal precioCopMensual) {
        SaasPlan plan = SaasPlan.builder()
                .nombre(nombre)
                .limiteInferior(limiteInferior)
                .limiteSuperior(limiteSuperior)
                .precioCopMensual(precioCopMensual)
                .build();
        return saasPlanRepository.save(plan);
    }

    @Transactional
    public SaasPlan editarPlanFull(Long planId, String nombre, Integer limiteInferior, Integer limiteSuperior, BigDecimal precioCopMensual) {
        SaasPlan plan = saasPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan no encontrado: " + planId));
        if (nombre != null && !nombre.isBlank()) plan.setNombre(nombre);
        if (limiteInferior != null) plan.setLimiteInferior(limiteInferior);
        plan.setLimiteSuperior(limiteSuperior); // puede ser null
        if (precioCopMensual != null) plan.setPrecioCopMensual(precioCopMensual);
        return saasPlanRepository.save(plan);
    }

    @Transactional
    public void eliminarPlan(Long planId) {
        saasPlanRepository.deleteById(planId);
    }

    // ─────────────────────────────────────────────
    // Gestión Global de Usuarios (Sección 4)
    // ─────────────────────────────────────────────

    public List<AppUser> listarTodosUsuarios() {
        return appUserRepository.findAll().stream()
                .filter(u -> u.getRole() == AppUser.Role.ADMIN || u.getRole() == AppUser.Role.EMPLEADO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public AppUser crearUsuarioGlobal(String nombreCompleto, String username, String password, AppUser.Role role, String clubNombre, Long clubId) {
        if (appUserRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("El username '" + username + "' ya existe.");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
        }

        AppUser.AppUserBuilder builder = AppUser.builder()
                .nombreCompleto(nombreCompleto)
                // Si el nuevo usuario es ADMIN, "Clubs Registrados" lee nombreAdministrador —
                // se sincroniza desde la creación para que ambas pestañas coincidan.
                .nombreAdministrador(role == AppUser.Role.ADMIN ? nombreCompleto : null)
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .clubEstado(AppUser.ClubEstado.ACTIVO);

        if (role == AppUser.Role.EMPLEADO) {
            // Un EMPLEADO SIEMPRE debe quedar atado a un club real (clubId), nunca a un
            // texto libre — de lo contrario queda "flotando" sin club válido.
            if (clubId == null) {
                throw new IllegalArgumentException("Debes seleccionar el club al que pertenece este empleado.");
            }
            AppUser club = buscarAdminPorId(clubId);
            builder.clubId(club.getId()).clubNombre(club.getClubNombre());
        } else {
            builder.clubNombre(clubNombre);
        }

        return appUserRepository.save(builder.build());
    }

    /**
     * Edición completa de un usuario (ADMIN o EMPLEADO) desde el panel SUPERADMIN:
     * nombre, username (login), contraseña, rol y club asignado.
     * Cualquier campo null/blank se deja sin modificar.
     */
    @Transactional
    public AppUser editarUsuarioGlobal(Long userId, String nombreCompleto, String username,
                                        String password, AppUser.Role role, Long nuevoClubId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + userId));

        if (user.getRole() == AppUser.Role.SUPERADMIN) {
            throw new IllegalArgumentException("No se puede editar una cuenta SUPERADMIN desde este panel.");
        }

        if (username != null && !username.isBlank()) {
            String trimmed = username.trim();
            if (!trimmed.equalsIgnoreCase(user.getUsername())) {
                appUserRepository.findByUsername(trimmed).ifPresent(existing -> {
                    if (!existing.getId().equals(userId)) {
                        throw new IllegalArgumentException("El username '" + trimmed + "' ya está en uso.");
                    }
                });
                user.setUsername(trimmed);
            }
        }

        if (nombreCompleto != null && !nombreCompleto.isBlank()) {
            user.setNombreCompleto(nombreCompleto);
            // Si es ADMIN, "Clubs Registrados" lee nombreAdministrador — se mantiene
            // sincronizado para que ambas pestañas nunca vuelvan a divergir.
            if (user.getRole() == AppUser.Role.ADMIN) {
                user.setNombreAdministrador(nombreCompleto);
            }
        }

        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
            }
            user.setPasswordHash(passwordEncoder.encode(password));
        }

        AppUser.Role roleFinal = role != null ? role : user.getRole();
        if (role != null) {
            user.setRole(role);
        }

        // El "club" de un ADMIN es él mismo (su propio id): no se reasigna desde aquí,
        // se edita con "Editar Club" en la pestaña Clubs Registrados.
        // Solo un EMPLEADO puede moverse de un club a otro, y SIEMPRE eligiendo uno
        // de los clubes realmente registrados (nunca texto libre) para no dejarlo
        // apuntando a un club inexistente.
        if (roleFinal == AppUser.Role.EMPLEADO && nuevoClubId != null) {
            AppUser nuevoClub = buscarAdminPorId(nuevoClubId);
            boolean cambiaDeClub = !nuevoClubId.equals(user.getClubId());
            user.setClubId(nuevoClub.getId());
            user.setClubNombre(nuevoClub.getClubNombre());
            if (cambiaDeClub && user.getSedesAutorizadas() != null) {
                // Las sedes autorizadas pertenecen al club anterior: se limpian para
                // evitar que el empleado quede con acceso a sedes de otro club.
                user.getSedesAutorizadas().clear();
            }
        }

        return appUserRepository.save(user);
    }

    /**
     * Elimina un usuario global (solo EMPLEADO). Un ADMIN no puede eliminarse desde aquí porque
     * es dueño de un club entero con datos asociados (padres, sedes, etc.) — para eso está
     * "Eliminar Definitivamente" en Clubs Registrados, que borra todo en cascada de forma segura.
     */
    @Transactional
    public void eliminarUsuarioGlobal(Long userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + userId));

        if (user.getRole() == AppUser.Role.SUPERADMIN) {
            throw new IllegalArgumentException("No se puede eliminar una cuenta SUPERADMIN.");
        }
        if (user.getRole() == AppUser.Role.ADMIN) {
            throw new IllegalArgumentException("Para eliminar un ADMIN de club usa 'Eliminar Definitivamente' en Clubs Registrados (primero debes suspenderlo por mora). Así se borran también sus padres, deportistas y sedes.");
        }

        appUserRepository.delete(user);
    }

    @Transactional
    public AppUser cambiarRolUsuario(Long userId, AppUser.Role nuevoRol) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + userId));
        user.setRole(nuevoRol);
        return appUserRepository.save(user);
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

    public Integer obtenerLimiteDeportistas(AppUser.PlanActual planActual) {
        if (planActual == null) return 20;
        try {
            List<SaasPlan> planes = saasPlanRepository.findAll();
            if (planes.isEmpty()) return 20;
            planes.sort(java.util.Comparator.comparing(SaasPlan::getLimiteInferior));
            if (planActual == AppUser.PlanActual.TRAMO_1) {
                return planes.get(0).getLimiteSuperior();
            } else if (planActual == AppUser.PlanActual.TRAMO_2) {
                return planes.size() > 1 ? planes.get(1).getLimiteSuperior() : 40;
            } else {
                return planes.size() > 2 ? planes.get(2).getLimiteSuperior() : null;
            }
        } catch (Exception e) {
            log.error("Error al obtener límite de deportistas para tramo {}: {}", planActual, e.getMessage());
            if (planActual == AppUser.PlanActual.TRAMO_1) return 20;
            if (planActual == AppUser.PlanActual.TRAMO_2) return 40;
            return null;
        }
    }

    private AppUser.PlanActual parsePlanActual(SaasPlan plan) {
        try {
            List<SaasPlan> planes = saasPlanRepository.findAll();
            planes.sort(java.util.Comparator.comparing(SaasPlan::getLimiteInferior));
            for (int i = 0; i < planes.size(); i++) {
                if (planes.get(i).getId().equals(plan.getId())) {
                    if (i == 0) return AppUser.PlanActual.TRAMO_1;
                    if (i == 1) return AppUser.PlanActual.TRAMO_2;
                    return AppUser.PlanActual.TRAMO_3;
                }
            }
        } catch (Exception e) {
            log.error("Error al mapear SaasPlan a PlanActual enum: {}", e.getMessage());
        }
        // Fallback
        if (plan.getLimiteInferior() <= 20) return AppUser.PlanActual.TRAMO_1;
        if (plan.getLimiteInferior() <= 40) return AppUser.PlanActual.TRAMO_2;
        return AppUser.PlanActual.TRAMO_3;
    }
}

