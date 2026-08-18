package com.asistencia.erp.config;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

/**
 * Bootstrap mínimo y autocontenido de la plataforma SaaS.
 *
 * Contrato estricto, para que esto NUNCA vuelva a tocar datos reales en
 * producción (que es exactamente lo que pasó antes de esta reescritura):
 *
 *   1. Hace UNA sola lectura: ¿existe ya un usuario "superadmin"?
 *   2. Si existe → no hace absolutamente nada más. No reescribe su
 *      contraseña, no toca clubes, no toca sedes, no toca grupos, no
 *      toca clientes. El método retorna de inmediato.
 *   3. Si NO existe (base de datos limpia, o la cuenta superadmin se
 *      perdió/rompió por algún motivo) → crea ÚNICAMENTE esa cuenta,
 *      con la contraseña inicial SUPERGOAT, y nada más.
 *
 * Todo lo demás — clubes, sedes, entrenadores, deportistas, configuración
 * de cobros — se crea exclusivamente a través de la aplicación normal
 * (panel SuperAdmin → "Nuevo Club", Gestión de Sedes, Gestión de
 * Empleados, Inscripciones, etc.), nunca desde este arranque.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (appUserRepository.findByUsername("superadmin").isPresent()) {
            return;
        }

        AppUser superAdmin = new AppUser();
        superAdmin.setUsername("superadmin");
        superAdmin.setPasswordHash(passwordEncoder.encode("SUPERGOAT"));
        superAdmin.setRole(AppUser.Role.SUPERADMIN);
        superAdmin.setClubEstado(AppUser.ClubEstado.ACTIVO);
        superAdmin.setSedesAutorizadas(new HashSet<>());
        appUserRepository.save(superAdmin);
        log.info("Base de datos limpia detectada: usuario SUPERADMIN creado (contraseña inicial: SUPERGOAT). " +
                "Cámbiala luego de tu primer inicio de sesión y crea tus clubes desde el panel SuperAdmin.");
    }
}
