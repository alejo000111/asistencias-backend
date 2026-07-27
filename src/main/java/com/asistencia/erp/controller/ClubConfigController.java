package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Controlador para la configuración de cobro de cada club.
 *
 * Permite que el ADMIN_CLUB configure libremente cómo cobra a sus clientes:
 *   - Esquema principal: MENSUALIDAD, PAQUETE o POR_CLASE.
 *   - Matrícula anual: habilitar/deshabilitar, obligatorio, monto.
 *   - Seguro deportivo: habilitar/deshabilitar, obligatorio, monto.
 *   - Precios diferenciados por sede.
 *
 * Solo el propio ADMIN del club puede leer y modificar su configuración.
 */
@RestController
@RequestMapping("/api/config/cobro")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ClubConfigController {

    private final ClubConfigRepository clubConfigRepository;
    private final AppUserRepository appUserRepository;

    /**
     * Obtiene la configuración de cobro del ADMIN autenticado.
     * Si no existe aún, retorna una configuración con valores por defecto.
     */
    @GetMapping
    public ResponseEntity<ClubConfig> obtenerConfig() {
        Long adminId = obtenerAdminId();
        ClubConfig config = clubConfigRepository.findByAdminId(adminId)
                .orElseGet(() -> crearConfigPorDefecto(adminId));
        return ResponseEntity.ok(config);
    }

    /**
     * Guarda o actualiza la configuración de cobro del ADMIN autenticado.
     *
     * Body JSON esperado:
     * {
     *   "esquemaCobro": "MENSUALIDAD" | "PAQUETE" | "POR_CLASE",
     *   "cobraMatricula": true | false,
     *   "matriculaObligatoria": true | false,
     *   "montoMatricula": 120000,
     *   "cobraSeguro": true | false,
     *   "seguroObligatorio": true | false,
     *   "montoSeguro": 50000,
     *   "preciosDiferenciados": true | false
     * }
     */
    @PutMapping
    public ResponseEntity<?> guardarConfig(@RequestBody Map<String, Object> body) {
        Long adminId = obtenerAdminId();
        ClubConfig config = clubConfigRepository.findByAdminId(adminId)
                .orElseGet(() -> crearConfigPorDefecto(adminId));

        // Esquema de cobro
        if (body.containsKey("esquemaCobro")) {
            try {
                config.setEsquemaCobro(ClubConfig.EsquemaCobro.valueOf(body.get("esquemaCobro").toString()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "esquemaCobro inválido. Valores: MENSUALIDAD, PAQUETE, POR_CLASE"));
            }
        }

        // Matrícula
        if (body.containsKey("cobraMatricula"))
            config.setCobraMatricula(Boolean.valueOf(body.get("cobraMatricula").toString()));
        if (body.containsKey("matriculaObligatoria"))
            config.setMatriculaObligatoria(Boolean.valueOf(body.get("matriculaObligatoria").toString()));
        if (body.containsKey("montoMatricula") && body.get("montoMatricula") != null)
            config.setMontoMatricula(new BigDecimal(body.get("montoMatricula").toString()));

        // Seguro deportivo
        if (body.containsKey("cobraSeguro"))
            config.setCobraSeguro(Boolean.valueOf(body.get("cobraSeguro").toString()));
        if (body.containsKey("seguroObligatorio"))
            config.setSeguroObligatorio(Boolean.valueOf(body.get("seguroObligatorio").toString()));
        if (body.containsKey("montoSeguro") && body.get("montoSeguro") != null)
            config.setMontoSeguro(new BigDecimal(body.get("montoSeguro").toString()));

        // Precios diferenciados
        if (body.containsKey("preciosDiferenciados"))
            config.setPreciosDiferenciados(Boolean.valueOf(body.get("preciosDiferenciados").toString()));

        ClubConfig saved = clubConfigRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    // ─────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────

    private Long obtenerAdminId() {
        var principal = SecurityUtils.getCurrentUser();
        if (principal == null) throw new SecurityException("Usuario no autenticado.");
        return principal.getUserId();
    }

    /**
     * Crea una ClubConfig con valores por defecto para el admin dado.
     * No persiste; el llamador debe decidir si guardarlo.
     */
    private ClubConfig crearConfigPorDefecto(Long adminId) {
        AppUser admin = appUserRepository.findById(adminId)
                .orElseThrow(() -> new IllegalArgumentException("Admin no encontrado: " + adminId));
        return ClubConfig.builder()
                .admin(admin)
                .esquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD)
                .cobraMatricula(false)
                .matriculaObligatoria(false)
                .cobraSeguro(false)
                .seguroObligatorio(false)
                .preciosDiferenciados(false)
                .build();
    }
}
