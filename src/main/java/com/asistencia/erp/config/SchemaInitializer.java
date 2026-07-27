package com.asistencia.erp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Inicializador de esquema para FASE 2 — SuperAdmin y Cobros.
 *
 * Se ejecuta con @Order(0) para correr ANTES que DataSeeder (@Order(1)).
 *
 * Propósito:
 *   - Detectar si las tablas nuevas (saas_plans, club_configs) ya existen.
 *   - Si no existen, crearlas con las columnas correctas.
 *   - Agregar columnas nuevas a app_users si no existen (idempotente).
 *   - Insertar los tramos SaaS semilla si la tabla está vacía.
 *
 * Esto permite que el despliegue en Render (DDL_AUTO=validate) funcione
 * sin intervención manual: el esquema se crea automáticamente al arrancar.
 *
 * Diseño:
 *   - Usa SQL con IF NOT EXISTS → 100% idempotente (se puede ejecutar N veces).
 *   - No borra ni modifica datos existentes jamás.
 *   - En caso de error, loga la causa y continúa (no detiene el arranque).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(0)  // Antes de DataSeeder (@Order(1))
public class SchemaInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        init();
    }

    public void init() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        log.info("=== SchemaInitializer: verificando esquema FASE 2 ===");

        agregarColumnasAppUsers(jdbc);
        crearTablaSaasPlans(jdbc);
        crearTablaClubConfigs(jdbc);
        sembrarTramosSaas(jdbc);

        log.info("=== SchemaInitializer: esquema FASE 2 verificado ✓ ===");
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Agregar columnas nuevas a app_users (idempotente con IF NOT EXISTS)
    // ─────────────────────────────────────────────────────────────
    private void agregarColumnasAppUsers(JdbcTemplate jdbc) {
        // MySQL no soporta ALTER TABLE ADD COLUMN IF NOT EXISTS en versiones < 8.x
        // Se consulta INFORMATION_SCHEMA para cada columna antes de agregarla.
        String[][] columnas = {
            {"club_nombre",   "VARCHAR(100) NULL"},
            {"club_nit",      "VARCHAR(20) NULL"},
            {"club_estado",   "VARCHAR(30) NOT NULL DEFAULT 'ACTIVO'"},
            {"plan_actual",   "VARCHAR(20) NULL"},
            {"fecha_corte",   "DATE NULL"}
        };

        for (String[] col : columnas) {
            String colName  = col[0];
            String colDef   = col[1];
            try {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_users' AND COLUMN_NAME = ?",
                    Integer.class, colName
                );
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE app_users ADD COLUMN " + colName + " " + colDef);
                    log.info("  ✓ Columna agregada: app_users.{}", colName);
                } else {
                    log.debug("  · Columna ya existe: app_users.{}", colName);
                }
            } catch (Exception e) {
                log.warn("  ⚠ No se pudo agregar columna app_users.{}: {}", colName, e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Crear tabla saas_plans si no existe
    // ─────────────────────────────────────────────────────────────
    private void crearTablaSaasPlans(JdbcTemplate jdbc) {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS saas_plans (" +
                "  id                  BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  nombre              VARCHAR(80)   NOT NULL," +
                "  limite_inferior     INT           NOT NULL," +
                "  limite_superior     INT           NULL," +
                "  precio_cop_mensual  DECIMAL(12,2) NOT NULL," +
                "  UNIQUE KEY uk_tramo_rango (limite_inferior)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            log.info("  ✓ Tabla saas_plans verificada / creada");
        } catch (Exception e) {
            log.warn("  ⚠ Error al crear saas_plans: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Crear tabla club_configs si no existe
    // ─────────────────────────────────────────────────────────────
    private void crearTablaClubConfigs(JdbcTemplate jdbc) {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS club_configs (" +
                "  id                    BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  admin_id              BIGINT        NOT NULL UNIQUE," +
                "  esquema_cobro         VARCHAR(20)   NOT NULL DEFAULT 'MENSUALIDAD'," +
                "  cobra_matricula       BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  matricula_obligatoria BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  monto_matricula       DECIMAL(12,2) NULL," +
                "  cobra_seguro          BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  seguro_obligatorio    BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  monto_seguro          DECIMAL(12,2) NULL," +
                "  precios_diferenciados BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  CONSTRAINT fk_club_config_admin FOREIGN KEY (admin_id) " +
                "    REFERENCES app_users(id) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            log.info("  ✓ Tabla club_configs verificada / creada");
        } catch (Exception e) {
            log.warn("  ⚠ Error al crear club_configs: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Sembrar los 3 tramos SaaS si la tabla está vacía
    // ─────────────────────────────────────────────────────────────
    private void sembrarTramosSaas(JdbcTemplate jdbc) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM saas_plans", Integer.class);
            if (count == null || count == 0) {
                jdbc.execute(
                    "INSERT INTO saas_plans (nombre, limite_inferior, limite_superior, precio_cop_mensual) VALUES " +
                    "('Tramo 1 — Hasta 20 deportistas', 1, 20, 150000.00)," +
                    "('Tramo 2 — Hasta 40 deportistas', 21, 40, 250000.00)," +
                    "('Tramo 3 — Más de 40 deportistas', 41, NULL, 400000.00)"
                );
                log.info("  ✓ Tramos SaaS semilla insertados (3 tramos)");
            } else {
                log.debug("  · Tramos SaaS ya existen ({} registros)", count);
            }
        } catch (Exception e) {
            log.warn("  ⚠ Error al sembrar tramos SaaS: {}", e.getMessage());
        }
    }
}
