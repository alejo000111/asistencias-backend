package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.TarifaSede;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.service.FinancialService;

@RestController
@RequestMapping("/api/config/cobro")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
public class ClubConfigController {

    private final ClubConfigRepository clubConfigRepository;
    private final AppUserRepository appUserRepository;
    private final StudentRepository studentRepository;
    private final FinancialService financialService;
    private final SedeRepository sedeRepository;

    /**
     * Obtiene la configuración de cobro del club del usuario autenticado (lectura, no escritura).
     * Se permite también a EMPLEADO: un entrenador necesita conocer el esquema de cobro, los
     * montos de matrícula/seguro y demás reglas de SU club para que la UI (registrar asistencia,
     * ficha de clientes, etc.) se comporte igual que para el ADMIN — antes esto estaba restringido
     * a ADMIN/SUPERADMIN y el entrenador nunca heredaba la configuración real del club.
     * Si no existe aún, retorna una configuración con valores por defecto.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN', 'EMPLEADO')")
    @GetMapping
    public ResponseEntity<ClubConfig> obtenerConfig() {
        Long clubId = SecurityUtils.getClubId();
        ClubConfig config = clubConfigRepository.findByAdminId(clubId)
                .orElseGet(() -> crearConfigPorDefecto(clubId));
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

        // Snapshot de los campos que disparan cobros de matrícula/seguro, para no
        // reprocesar a TODOS los deportistas activos en cada guardado si nada de esto
        // realmente cambió (antes se ejecutaba siempre, incluso sin tocar nada).
        boolean cobraMatriculaAntes = Boolean.TRUE.equals(config.getCobraMatricula());
        boolean matriculaObligatoriaAntes = Boolean.TRUE.equals(config.getMatriculaObligatoria());
        BigDecimal montoMatriculaAntes = config.getMontoMatricula();
        String vigenciaMatriculaAntes = config.getFechaVigenciaMatricula();
        boolean cobraSeguroAntes = Boolean.TRUE.equals(config.getCobraSeguro());
        boolean seguroObligatorioAntes = Boolean.TRUE.equals(config.getSeguroObligatorio());
        BigDecimal montoSeguroAntes = config.getMontoSeguro();
        String vigenciaSeguroAntes = config.getFechaVigenciaSeguro();

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
        if (body.containsKey("regularidadMatricula") && body.get("regularidadMatricula") != null)
            config.setRegularidadMatricula(ClubConfig.Regularidad.valueOf(body.get("regularidadMatricula").toString()));
        if (body.containsKey("mesMatricula") && body.get("mesMatricula") != null)
            config.setMesMatricula(Integer.parseInt(body.get("mesMatricula").toString()));
        if (body.containsKey("fechaVigenciaMatricula") && body.get("fechaVigenciaMatricula") != null)
            config.setFechaVigenciaMatricula(body.get("fechaVigenciaMatricula").toString());

        // Seguro deportivo
        if (body.containsKey("cobraSeguro"))
            config.setCobraSeguro(Boolean.valueOf(body.get("cobraSeguro").toString()));
        if (body.containsKey("seguroObligatorio"))
            config.setSeguroObligatorio(Boolean.valueOf(body.get("seguroObligatorio").toString()));
        if (body.containsKey("montoSeguro") && body.get("montoSeguro") != null)
            config.setMontoSeguro(new BigDecimal(body.get("montoSeguro").toString()));
        if (body.containsKey("regularidadSeguro") && body.get("regularidadSeguro") != null)
            config.setRegularidadSeguro(ClubConfig.Regularidad.valueOf(body.get("regularidadSeguro").toString()));
        if (body.containsKey("mesSeguro") && body.get("mesSeguro") != null)
            config.setMesSeguro(Integer.parseInt(body.get("mesSeguro").toString()));
        if (body.containsKey("diaLimiteSeguro") && body.get("diaLimiteSeguro") != null)
            config.setDiaLimiteSeguro(Integer.parseInt(body.get("diaLimiteSeguro").toString()));
        if (body.containsKey("fechaVigenciaSeguro") && body.get("fechaVigenciaSeguro") != null)
            config.setFechaVigenciaSeguro(body.get("fechaVigenciaSeguro").toString());

        // Calendario mensualidad
        if (body.containsKey("montoPreferencial") && body.get("montoPreferencial") != null)
            config.setMontoPreferencial(new BigDecimal(body.get("montoPreferencial").toString()));
        if (body.containsKey("diaLimitePreferencial") && body.get("diaLimitePreferencial") != null)
            config.setDiaLimitePreferencial(Integer.parseInt(body.get("diaLimitePreferencial").toString()));
        if (body.containsKey("montoEstandar") && body.get("montoEstandar") != null)
            config.setMontoEstandar(new BigDecimal(body.get("montoEstandar").toString()));
        if (body.containsKey("montoMora") && body.get("montoMora") != null)
            config.setMontoMora(new BigDecimal(body.get("montoMora").toString()));
        if (body.containsKey("diaCorteMora") && body.get("diaCorteMora") != null)
            config.setDiaCorteMora(Integer.parseInt(body.get("diaCorteMora").toString()));

        // El calendario de mensualidad solo tiene sentido dentro de los días reales
        // del mes y con la mora ocurriendo después de la tarifa preferencial; si no,
        // MonthlyBillingService.calculateMonthlyPrice queda con un rango inválido.
        // 0 es válido y significa "tarifa desactivada" (ver MonthlyBillingService).
        if (config.getEsquemaCobro() == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            Integer diaLimPref = config.getDiaLimitePreferencial();
            Integer diaCorteMora = config.getDiaCorteMora();
            if (diaLimPref != null && (diaLimPref < 0 || diaLimPref > 31)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "diaLimitePreferencial debe estar entre 0 y 31."));
            }
            if (diaCorteMora != null && (diaCorteMora < 0 || diaCorteMora > 31)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "diaCorteMora debe estar entre 0 y 31."));
            }
            if (diaLimPref != null && diaCorteMora != null && diaLimPref > 0 && diaCorteMora > 0 && diaCorteMora <= diaLimPref) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "diaCorteMora debe ser posterior a diaLimitePreferencial."));
            }
        }

        // JSONs
        if (body.containsKey("preciosPorSedeJson") && body.get("preciosPorSedeJson") != null)
            config.setPreciosPorSedeJson(body.get("preciosPorSedeJson").toString());

        // Tarifas de mensualidad por sede (Preferencial/Actual/Mora), usadas por
        // MonthlyBillingService cuando preciosDiferenciados=true.
        if (body.containsKey("tarifasSede") && body.get("tarifasSede") != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tarifasBody = (List<Map<String, Object>>) body.get("tarifasSede");

            Map<Long, TarifaSede> existentes = new HashMap<>();
            for (TarifaSede t : config.getTarifasSede()) {
                existentes.put(t.getSede().getId(), t);
            }

            List<TarifaSede> actualizadas = new ArrayList<>();
            for (Map<String, Object> item : tarifasBody) {
                if (item.get("sedeId") == null) continue;
                Long sedeId = Long.parseLong(item.get("sedeId").toString());
                Sede sede = sedeRepository.findById(sedeId).orElse(null);
                // Cierra el mismo hueco cross-tenant que PlanMensualidadController.validarSedeDelClub:
                // sin este check, un club podría fijar tarifas para una sede de OTRO club con solo adivinar el id.
                if (sede == null || !sede.getClubId().equals(SecurityUtils.getClubId())) continue;

                TarifaSede t = existentes.getOrDefault(sedeId, new TarifaSede());
                t.setClubConfig(config);
                t.setSede(sede);
                t.setMontoPreferencial(item.get("montoPreferencial") != null ? new BigDecimal(item.get("montoPreferencial").toString()) : null);
                t.setMontoEstandar(item.get("montoEstandar") != null ? new BigDecimal(item.get("montoEstandar").toString()) : null);
                t.setMontoMora(item.get("montoMora") != null ? new BigDecimal(item.get("montoMora").toString()) : null);
                actualizadas.add(t);
            }

            config.getTarifasSede().clear();
            config.getTarifasSede().addAll(actualizadas);
        }

        // Tarifas por clase y paquetes
        if (body.containsKey("precioClaseGrupal") && body.get("precioClaseGrupal") != null)
            config.setPrecioClaseGrupal(new BigDecimal(body.get("precioClaseGrupal").toString()));
        if (body.containsKey("precioClasePersonalizada") && body.get("precioClasePersonalizada") != null)
            config.setPrecioClasePersonalizada(new BigDecimal(body.get("precioClasePersonalizada").toString()));
        if (body.containsKey("paquetesClasesJson") && body.get("paquetesClasesJson") != null)
            config.setPaquetesClasesJson(body.get("paquetesClasesJson").toString());

        // Precios diferenciados
        if (body.containsKey("preciosDiferenciados"))
            config.setPreciosDiferenciados(Boolean.valueOf(body.get("preciosDiferenciados").toString()));

        ClubConfig saved = clubConfigRepository.save(config);

        // Solo reprocesar cobros para TODOS los deportistas activos si algo que
        // realmente afecta matrícula/seguro cambió en este guardado.
        boolean cambioRelevante =
                cobraMatriculaAntes != Boolean.TRUE.equals(saved.getCobraMatricula())
                || matriculaObligatoriaAntes != Boolean.TRUE.equals(saved.getMatriculaObligatoria())
                || !java.util.Objects.equals(montoMatriculaAntes, saved.getMontoMatricula())
                || !java.util.Objects.equals(vigenciaMatriculaAntes, saved.getFechaVigenciaMatricula())
                || cobraSeguroAntes != Boolean.TRUE.equals(saved.getCobraSeguro())
                || seguroObligatorioAntes != Boolean.TRUE.equals(saved.getSeguroObligatorio())
                || !java.util.Objects.equals(montoSeguroAntes, saved.getMontoSeguro())
                || !java.util.Objects.equals(vigenciaSeguroAntes, saved.getFechaVigenciaSeguro());

        if (cambioRelevante) {
            try {
                financialService.procesarCargosParaTodosLosEstudiantes(saved);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return ResponseEntity.ok(saved);
    }

    /**
     * Obtiene la configuración de Wompi del ADMIN autenticado.
     */
    // SEC: la configuración de Wompi (llaves de pago, a dónde llega el dinero de todo el club)
    // es exclusiva del ADMIN dueño del club — ni SUPERADMIN (soporte de la plataforma SaaS) ni
    // ningún EMPLEADO deben poder verla o cambiarla, aunque el resto de /api/config/cobro sí
    // permita SUPERADMIN para soporte.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/wompi")
    public ResponseEntity<Map<String, Object>> obtenerWompiConfig() {
        Long adminId = obtenerAdminId();
        ClubConfig config = clubConfigRepository.findByAdminId(adminId).orElse(null);
        if (config == null) {
            return ResponseEntity.ok(Map.of());
        }
        // SEC: la llave privada y el secreto de eventos NUNCA se devuelven en texto plano al
        // frontend (antes viajaban completos en cada carga del formulario) — solo se informa
        // si ya están configurados, para que la UI pueda mostrar "ya configurada" sin exponerla.
        Map<String, Object> wompiConfig = new HashMap<>();
        wompiConfig.put("wompiEnvironment", config.getWompiEnvironment());
        wompiConfig.put("wompiPublicKey", config.getWompiPublicKey());
        wompiConfig.put("wompiPrivateKeyConfigurada", config.getWompiPrivateKey() != null && !config.getWompiPrivateKey().isBlank());
        wompiConfig.put("wompiEventSecretConfigurado", config.getWompiEventSecret() != null && !config.getWompiEventSecret().isBlank());
        return ResponseEntity.ok(wompiConfig);
    }

    /**
     * Guarda la configuración de Wompi del ADMIN autenticado. La llave privada y el secreto de
     * eventos solo se sobreescriben si viene un valor no vacío — un campo dejado en blanco
     * significa "no cambiar" (nunca se le muestra al admin el valor guardado para poder
     * confirmarlo, así que el formulario siempre los envía vacíos salvo que se estén reemplazando).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/wompi")
    public ResponseEntity<?> guardarWompiConfig(@RequestBody Map<String, String> body) {
        Long adminId = obtenerAdminId();
        ClubConfig config = clubConfigRepository.findByAdminId(adminId)
                .orElseGet(() -> crearConfigPorDefecto(adminId));

        if (body.containsKey("wompiEnvironment")) config.setWompiEnvironment(body.get("wompiEnvironment"));
        if (body.containsKey("wompiPublicKey")) config.setWompiPublicKey(body.get("wompiPublicKey"));
        if (body.get("wompiPrivateKey") != null && !body.get("wompiPrivateKey").isBlank())
            config.setWompiPrivateKey(body.get("wompiPrivateKey"));
        if (body.get("wompiEventSecret") != null && !body.get("wompiEventSecret").isBlank())
            config.setWompiEventSecret(body.get("wompiEventSecret"));

        clubConfigRepository.save(config);
        return ResponseEntity.ok(Map.of("message", "Configuración de Wompi guardada"));
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
