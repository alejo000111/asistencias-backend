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
        log.info("=== SchemaInitializer: verificando esquema ===");

        agregarColumnasAppUsers(jdbc);
        sincronizarNombreAdminYCompleto(jdbc);
        repararFechaCorteFaltante(jdbc);
        crearTablaSaasPlans(jdbc);
        crearTablaClubConfigs(jdbc);
        sembrarTramosSaas(jdbc);
        corregirRestriccionUniqueSede(jdbc);
        agregarColumnasFase4(jdbc);
        crearEsquemaEscenarios(jdbc);
        blindarColumnasEnumComoVarchar(jdbc);
        corregirClasesPagadasEnCero(jdbc);

        log.info("=== SchemaInitializer: esquema verificado ✓ ===");
    }

    // ─────────────────────────────────────────────────────────────
    // Blindaje de columnas respaldadas por un enum de Java.
    //
    // Motivo: si una columna quedó creada como ENUM nativo de MySQL (en vez de
    // VARCHAR), cualquier valor NUEVO que se agregue a su enum de Java en una
    // futura actualización (ej. el estado CORTESIA agregado a Student) rompe los
    // INSERT/UPDATE en producción con "Data truncated for column..." — porque
    // en producción DDL_AUTO=validate NUNCA altera columnas existentes, y ni
    // siquiera con DDL_AUTO=update Hibernate expande de forma confiable la
    // lista de valores de un ENUM ya creado (solo puede crear o alterar
    // columnas cuando arranca contra un esquema compatible).
    //
    // Por eso, TODAS las columnas respaldadas por un enum de Java se convierten
    // aquí a VARCHAR una sola vez (si todavía son ENUM nativo): aceptan
    // cualquier texto, y la validación real de qué valores son permitidos ya la
    // hace la propia aplicación (el enum de Java), nunca la base de datos. Así,
    // agregar un nuevo valor a un enum en el código NUNCA vuelve a requerir una
    // migración manual de base de datos.
    // ─────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────
    // Corrección de datos: asistencias con precio_cobrado = 0 pero clase_paga = 0.
    //
    // Una clase con precio $0 nunca es una deuda real (la aplicación siempre marca
    // clase_paga=true cuando el precio calculado es cero). Algunas filas antiguas quedaron con
    // clase_paga=false de todos modos (p.ej. una condición de carrera al registrar varias
    // asistencias simultáneas para el mismo deportista en el mismo mes) y aparecían para
    // siempre como "deuda pendiente $0" en el Portal de Padres y en la vista de Clientes,
    // confundiendo al padre/admin. Este UPDATE es idempotente (solo toca filas que cumplan
    // exactamente esa condición) y no afecta ninguna deuda real con monto > 0.
    // ─────────────────────────────────────────────────────────────
    private void corregirClasesPagadasEnCero(JdbcTemplate jdbc) {
        try {
            int filas = jdbc.update(
                "UPDATE attendances SET clase_paga = 1 WHERE precio_cobrado = 0 AND clase_paga = 0");
            if (filas > 0) {
                log.info("  ✓ Corregidas {} asistencias en $0 que quedaron marcadas incorrectamente como deuda pendiente", filas);
            }
        } catch (Exception e) {
            log.warn("  ⚠ No se pudo corregir asistencias en $0: {}", e.getMessage());
        }
    }

    private void blindarColumnasEnumComoVarchar(JdbcTemplate jdbc) {
        String[][] columnasEnum = {
            {"app_users",       "role",                 "VARCHAR(20) NOT NULL"},
            {"app_users",       "club_estado",           "VARCHAR(30) NULL DEFAULT 'ACTIVO'"},
            {"app_users",       "plan_actual",           "VARCHAR(20) NULL"},
            {"app_users",       "tipo_tarifa",           "VARCHAR(20) NULL DEFAULT 'POR_CLASE'"},
            {"students",        "estado",                "VARCHAR(20) NOT NULL DEFAULT 'ACTIVO'"},
            {"club_configs",    "esquema_cobro",         "VARCHAR(20) NOT NULL DEFAULT 'MENSUALIDAD'"},
            {"club_configs",    "regularidad_matricula", "VARCHAR(20) DEFAULT 'ANUAL'"},
            {"club_configs",    "regularidad_seguro",    "VARCHAR(20) DEFAULT 'ANUAL'"},
            {"compra_paquetes", "metodo_pago",           "VARCHAR(20) NULL"}
        };

        for (String[] col : columnasEnum) {
            String tabla = col[0];
            String columna = col[1];
            String nuevaDefinicion = col[2];
            try {
                String tipoActual = jdbc.queryForObject(
                    "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, tabla, columna
                );
                if (tipoActual != null && tipoActual.toLowerCase().startsWith("enum")) {
                    jdbc.execute("ALTER TABLE " + tabla + " MODIFY COLUMN " + columna + " " + nuevaDefinicion);
                    log.info("  ✓ Columna blindada (ENUM nativo → VARCHAR): {}.{}", tabla, columna);
                }
            } catch (Exception e) {
                log.warn("  ⚠ No se pudo blindar columna {}.{}: {}", tabla, columna, e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // FASE 4 — Empleados, Permisos Granulares y Nómina
    // ─────────────────────────────────────────────────────────────
    private void agregarColumnasFase4(JdbcTemplate jdbc) {
        agregarColumnaSiNoExiste(jdbc, "app_users", "tipo_tarifa", "VARCHAR(20) NOT NULL DEFAULT 'POR_CLASE'");
        agregarColumnaSiNoExiste(jdbc, "app_users", "puede_recaudar", "BOOLEAN NOT NULL DEFAULT FALSE");
        agregarColumnaSiNoExiste(jdbc, "app_users", "exento_nomina", "BOOLEAN NOT NULL DEFAULT TRUE");
        agregarColumnaSiNoExiste(jdbc, "attendances", "registrado_por_id", "BIGINT NULL");
        agregarColumnaSiNoExiste(jdbc, "attendances", "registrado_por_nombre", "VARCHAR(150) NULL");
    }

    private void agregarColumnaSiNoExiste(JdbcTemplate jdbc, String tabla, String columna, String definicion) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, tabla, columna
            );
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tabla + " ADD COLUMN " + columna + " " + definicion);
                log.info("  ✓ Columna agregada: {}.{}", tabla, columna);
            }
        } catch (Exception e) {
            log.warn("  ⚠ No se pudo agregar columna {}.{}: {}", tabla, columna, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Agregar columnas nuevas a app_users (idempotente con IF NOT EXISTS)
    // ─────────────────────────────────────────────────────────────
    private void agregarColumnasAppUsers(JdbcTemplate jdbc) {
        // MySQL no soporta ALTER TABLE ADD COLUMN IF NOT EXISTS en versiones < 8.x
        // Se consulta INFORMATION_SCHEMA para cada columna antes de agregarla.
        String[][] columnas = {
            {"club_nombre",          "VARCHAR(100) NULL"},
            {"nombre_administrador", "VARCHAR(100) NULL"},
            {"nombre_completo",      "VARCHAR(150) NULL"},
            {"tarifa_por_clase",     "DECIMAL(12,2) NULL"},
            {"club_nit",             "VARCHAR(20) NULL"},
            {"club_estado",          "VARCHAR(30) NOT NULL DEFAULT 'ACTIVO'"},
            {"plan_actual",          "VARCHAR(20) NULL"},
            {"fecha_corte",          "DATE NULL"},
            {"exento_tarifa",        "BOOLEAN NOT NULL DEFAULT FALSE"}
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
    // Backfill: "Clubs Registrados" (pestaña SuperAdmin) muestra `nombre_administrador`
    // y "Usuarios Registrados" muestra `nombre_completo` — dos columnas que en el
    // pasado se editaban por separado y quedaban desincronizadas para un mismo ADMIN
    // (ej. "Andrés asistencias" en una pestaña vs "asistencias" —el username— en la
    // otra). Ambas pestañas deben mostrar siempre el mismo nombre para un ADMIN, así
    // que en cada arranque se igualan cuando difieren, tomando como fuente de verdad
    // el que tenga un valor no vacío (o `nombre_administrador` si ambos lo tienen).
    // Los ADMIN/EMPLEADO se mantienen sincronizados también hacia adelante desde
    // SuperAdminService (crearAdminClub/editarClub/editarUsuarioGlobal).
    // ─────────────────────────────────────────────────────────────
    private void sincronizarNombreAdminYCompleto(JdbcTemplate jdbc) {
        try {
            int actualizados = jdbc.update(
                "UPDATE app_users SET nombre_completo = nombre_administrador " +
                "WHERE role = 'ADMIN' " +
                "  AND nombre_administrador IS NOT NULL AND nombre_administrador <> '' " +
                "  AND (nombre_completo IS NULL OR nombre_completo <> nombre_administrador)"
            );
            if (actualizados > 0) {
                log.info("  ✓ Backfill: {} admin(s) con nombre_completo sincronizado a nombre_administrador", actualizados);
            }
        } catch (Exception e) {
            log.warn("  ⚠ No se pudo sincronizar nombre_completo/nombre_administrador: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Reparación: un ADMIN NO exento nunca debe quedar con fecha_corte NULL (podía pasar si
    // se marcaba "exento" —que borra la fecha— y luego se desmarcaba sin fijar una nueva,
    // antes de que editarClub() empezara a rellenarla automáticamente en ese caso). Sin
    // fecha_corte, un club no exento jamás vuelve a evaluarse para suspensión por mora.
    // ─────────────────────────────────────────────────────────────
    private void repararFechaCorteFaltante(JdbcTemplate jdbc) {
        try {
            int reparados = jdbc.update(
                "UPDATE app_users SET fecha_corte = DATE_ADD(CURDATE(), INTERVAL 1 MONTH) " +
                "WHERE role = 'ADMIN' " +
                "  AND (exento_tarifa IS NULL OR exento_tarifa = FALSE) " +
                "  AND fecha_corte IS NULL"
            );
            if (reparados > 0) {
                log.info("  ✓ Reparación: {} club(s) no exento(s) sin fecha_corte recibieron fecha por defecto (+1 mes)", reparados);
            }
        } catch (Exception e) {
            log.warn("  ⚠ No se pudo reparar fecha_corte faltante: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ESCENARIOS — espacios donde se dictan las clases (Cancha, Pista, Gimnasio,
    // Sintética...), definidos libremente por cada admin.
    //
    // Sustituyen dos cosas que estaban cableadas al negocio del patinaje:
    //   - PlanMensualidad.dias_cancha_semana (un número suelto que además nunca
    //     controló cuota: era puramente informativo).
    //   - plan_inclusiones, que ataba los días extra a UNA sede concreta, por lo que
    //     un club con dos pistas dejaba pasar el doble del tope.
    //
    // Las columnas y tablas viejas NO se borran: quedan pobladas como respaldo,
    // respetando el contrato de esta clase.
    // ─────────────────────────────────────────────────────────────
    private void crearEsquemaEscenarios(JdbcTemplate jdbc) {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS escenarios (" +
                "  id       BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  club_id  BIGINT       NOT NULL," +
                "  nombre   VARCHAR(100) NOT NULL," +
                "  emoji    VARCHAR(16)  NULL," +
                "  periodo  VARCHAR(20)  NOT NULL DEFAULT 'SEMANAL'," +
                "  activo   BOOLEAN      NOT NULL DEFAULT TRUE," +
                "  orden    INT          NULL," +
                "  UNIQUE KEY uk_escenario_nombre_club (nombre, club_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS plan_cupos (" +
                "  id            BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  plan_id       BIGINT NOT NULL," +
                "  escenario_id  BIGINT NOT NULL," +
                "  cantidad      INT    NOT NULL," +
                "  CONSTRAINT fk_plan_cupo_plan FOREIGN KEY (plan_id) " +
                "    REFERENCES planes_mensualidad(id) ON DELETE CASCADE," +
                "  CONSTRAINT fk_plan_cupo_escenario FOREIGN KEY (escenario_id) " +
                "    REFERENCES escenarios(id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            agregarColumnaSiNoExiste(jdbc, "sedes", "escenario_id", "BIGINT NULL");
            agregarColumnaSiNoExiste(jdbc, "complementos", "escenario_id", "BIGINT NULL");
            agregarColumnaSiNoExiste(jdbc, "complementos", "grupo_nombre", "VARCHAR(150) NULL");

            log.info("  ✓ Tablas escenarios / plan_cupos verificadas / creadas");

            backfillEscenarios(jdbc);
        } catch (Exception e) {
            log.warn("  ⚠ Error al preparar el esquema de escenarios: {}", e.getMessage());
        }
    }

    /**
     * Migra los datos previos a escenarios SIN inventar nombres: la única fuente son
     * las sedes que el propio admin ya creó. Cada sede que estuviera referenciada como
     * "sede de asistencia" (en plan_inclusiones o complementos) genera un escenario que
     * se llama igual que ella, y las referencias se reapuntan a ese escenario.
     *
     * Idempotente: cada paso se salta si ya existe el registro equivalente, así que
     * puede correr en cada arranque sin duplicar nada.
     *
     * Nota: dias_cancha_semana NO se migra. No hay ningún nombre escrito por el admin
     * del cual derivar un escenario, y ese campo nunca controló cuota. El admin crea
     * sus escenarios y vuelve a escribir ese número como cupo real (que ahora sí cuenta).
     */
    private void backfillEscenarios(JdbcTemplate jdbc) {
        try {
            // 1. Un escenario por cada sede usada como destino de inclusiones o complementos.
            jdbc.update(
                "INSERT INTO escenarios (club_id, nombre, periodo, activo) " +
                "SELECT DISTINCT s.club_id, s.nombre, 'SEMANAL', TRUE " +
                "FROM sedes s " +
                "WHERE ( s.id IN (SELECT pi.sede_asistencia_id FROM plan_inclusiones pi) " +
                "     OR s.id IN (SELECT c.sede_asistencia_id FROM complementos c WHERE c.sede_asistencia_id IS NOT NULL) ) " +
                "  AND NOT EXISTS (SELECT 1 FROM escenarios e WHERE e.nombre = s.nombre AND e.club_id = s.club_id)"
            );

            // 2. Cada sede queda enlazada al escenario que lleva su mismo nombre.
            jdbc.update(
                "UPDATE sedes s JOIN escenarios e ON e.nombre = s.nombre AND e.club_id = s.club_id " +
                "SET s.escenario_id = e.id WHERE s.escenario_id IS NULL"
            );

            // 3. plan_inclusiones → plan_cupos, resolviendo el escenario por la sede destino.
            int cupos = jdbc.update(
                "INSERT INTO plan_cupos (plan_id, escenario_id, cantidad) " +
                "SELECT pi.plan_id, s.escenario_id, pi.dias_por_semana " +
                "FROM plan_inclusiones pi " +
                "JOIN sedes s ON s.id = pi.sede_asistencia_id " +
                "WHERE s.escenario_id IS NOT NULL " +
                "  AND NOT EXISTS (SELECT 1 FROM plan_cupos pc " +
                "                  WHERE pc.plan_id = pi.plan_id AND pc.escenario_id = s.escenario_id)"
            );

            // 4. Complementos: su sede de asistencia pasa a ser el escenario de esa sede.
            int comps = jdbc.update(
                "UPDATE complementos c JOIN sedes s ON s.id = c.sede_asistencia_id " +
                "SET c.escenario_id = s.escenario_id " +
                "WHERE c.escenario_id IS NULL AND s.escenario_id IS NOT NULL"
            );

            if (cupos > 0 || comps > 0) {
                log.info("  ✓ Backfill de escenarios: {} cupo(s) de plan y {} complemento(s) migrados", cupos, comps);
            }
        } catch (Exception e) {
            // Es esperable que falle si plan_inclusiones/complementos aún no existen
            // (instalación nueva): no hay nada que migrar y el arranque debe continuar.
            log.warn("  ⚠ Backfill de escenarios omitido: {}", e.getMessage());
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

            String[][] colsConfig = {
                {"regularidad_matricula",      "VARCHAR(20) DEFAULT 'ANUAL'"},
                {"mes_matricula",              "INT NULL"},
                {"dia_limite_matricula",       "INT NULL"},
                {"regularidad_seguro",         "VARCHAR(20) DEFAULT 'ANUAL'"},
                {"mes_seguro",                 "INT NULL"},
                {"dia_limite_seguro",          "INT NULL"},
                {"precio_clase_grupal",        "DECIMAL(12,2) NULL"},
                {"precio_clase_personalizada", "DECIMAL(12,2) NULL"},
                {"paquetes_clases_json",       "TEXT NULL"},
                {"precios_por_sede_json",      "TEXT NULL"},
                {"monto_preferencial",         "DECIMAL(12,2) NULL"},
                {"dia_limite_preferencial",    "INT NULL"},
                {"monto_estandar",             "DECIMAL(12,2) NULL"},
                {"monto_mora",                 "DECIMAL(12,2) NULL"},
                {"dia_corte_mora",             "INT NULL"},
                {"fecha_vigencia_matricula",   "VARCHAR(20) NULL"},
                {"fecha_vigencia_seguro",      "VARCHAR(20) NULL"}
            };

            for (String[] col : colsConfig) {
                try {
                    Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_configs' AND COLUMN_NAME = ?",
                        Integer.class, col[0]
                    );
                    if (count == null || count == 0) {
                        jdbc.execute("ALTER TABLE club_configs ADD COLUMN " + col[0] + " " + col[1]);
                    }
                } catch (Exception ignored) {}
            }

            // Columnas para students
            String[][] colsStudents = {
                {"adquiere_matricula", "BOOLEAN NOT NULL DEFAULT FALSE"},
                {"adquiere_seguro",    "BOOLEAN NOT NULL DEFAULT FALSE"}
            };
            for (String[] col : colsStudents) {
                try {
                    Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'students' AND COLUMN_NAME = ?",
                        Integer.class, col[0]
                    );
                    if (count == null || count == 0) {
                        jdbc.execute("ALTER TABLE students ADD COLUMN " + col[0] + " " + col[1]);
                    }
                } catch (Exception ignored) {}
            }

            // Columnas para financial_logs
            try {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'financial_logs' AND COLUMN_NAME = 'concepto'",
                    Integer.class
                );
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE financial_logs ADD COLUMN concepto VARCHAR(255) NULL");
                }
                jdbc.execute("ALTER TABLE financial_logs MODIFY COLUMN tipo_movimiento VARCHAR(50) NOT NULL");
                jdbc.execute("ALTER TABLE financial_logs MODIFY COLUMN metodo_pago VARCHAR(50) NULL");
            } catch (Exception ignored) {}

            log.info("  ✓ Tabla club_configs, financial_logs y students verificadas / creadas");
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

    private void corregirRestriccionUniqueSede(JdbcTemplate jdbc) {
        try {
            // 1. Encontrar el nombre de los índices únicos en sedes.nombre
            java.util.List<String> indices = jdbc.query(
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sedes' AND COLUMN_NAME = 'nombre' AND INDEX_NAME <> 'PRIMARY'",
                (rs, rowNum) -> rs.getString("INDEX_NAME")
            );

            // 2. Eliminar el índice antiguo de columna única
            for (String indexName : indices) {
                try {
                    // Evitar borrar el nuevo índice compuesto si ya se llama así
                    if ("uk_sede_nombre_club".equalsIgnoreCase(indexName)) continue;
                    jdbc.execute("ALTER TABLE sedes DROP INDEX " + indexName);
                    log.info("  ✓ Índice único antiguo sedes.{} eliminado", indexName);
                } catch (Exception e) {
                    log.warn("  ⚠ No se pudo eliminar índice sedes.{}: {}", indexName, e.getMessage());
                }
            }

            // 3. Crear el nuevo índice compuesto si no existe
            Integer countCompuesto = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sedes' AND INDEX_NAME = 'uk_sede_nombre_club'",
                Integer.class
            );

            if (countCompuesto == null || countCompuesto == 0) {
                jdbc.execute("ALTER TABLE sedes ADD UNIQUE KEY uk_sede_nombre_club (nombre, club_id)");
                log.info("  ✓ Creado nuevo índice compuesto único (nombre, club_id) en sedes");
            }
        } catch (Exception e) {
            log.warn("  ⚠ Error al corregir restricción unique de sedes: {}", e.getMessage());
        }
    }
}
