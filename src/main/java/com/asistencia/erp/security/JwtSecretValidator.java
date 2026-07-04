package com.asistencia.erp.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validador de seguridad al arranque de la aplicación.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 📌 PROPÓSITO
 * ══════════════════════════════════════════════════════════════════════
 * Evita que el sistema opere en producción/staging con el JWT_SECRET
 * hardcodeado de fábrica. Si por error humano no se inyecta la variable
 * de entorno JWT_SECRET en Render, la aplicación NO debe arrancar.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 🔍 LÓGICA DE ENTORNO (Zero-Manual-Changes)
 * ══════════════════════════════════════════════════════════════════════
 * - ENTORNO LOCAL (sin DDL_AUTO en env, o con update/create):
 *   → Permite el uso del fallback hardcodeado para desarrollo sin
 *     configuración manual de variables de entorno.
 *   → Solo emite una advertencia (WARN) en los logs.
 *
 * - ENTORNO PRODUCCIÓN/STAGING (System.getenv("DDL_AUTO") = "validate"):
 *   → Exige que System.getenv("JWT_SECRET") esté definido.
 *   → Si la variable de entorno es nula, vacía, o el valor coincide
 *     con el default hardcodeado, la aplicación lanza una excepción
 *     fatal y se detiene INMEDIATAMENTE.
 *
 *   Nota: Se usa System.getenv("DDL_AUTO") en lugar de la propiedad
 *   resuelta @Value("${spring.jpa.hibernate.ddl-auto}") porque en
 *   local la propiedad tiene un default "validate" en application.properties
 *   pero la variable de entorno NO está definida. Solo cuando Render
 *   inyecta explícitamente DDL_AUTO=validate se considera producción.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 🏗️ ARQUITECTURA
 * ══════════════════════════════════════════════════════════════════════
 * - Implementa un @PostConstruct en un @Component, por lo que se
 *   ejecuta automáticamente después de la inyección de dependencias.
 * - No depende de perfiles de Spring (@Profile), sino de variables de
 *   entorno del sistema, alineado con el diseño de la infraestructura
 *   (Render injecta variables de entorno en el dashboard).
 * - Esto mantiene la filosofía "Zero-Manual-Changes": el desarrollador
 *   no necesita activar perfiles ni configurar variables en local.
 */
@Slf4j
@Component
public class JwtSecretValidator {

    /** El valor hardcodeado definido en application.properties como fallback local */
    private static final String DEFAULT_JWT_SECRET =
        "asistencias_erp_secret_key_2026_must_be_at_least_256_bits_long_!!!!!";

    /** El valor real del JWT_SECRET inyectado por Spring (desde properties o env) */
    private final String jwtSecret;

    public JwtSecretValidator(@Value("${jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /**
     * Método protegido para obtener la variable de entorno JWT_SECRET.
     * Separado para permitir sobrescritura en pruebas unitarias
     * sin depender de PowerMock o manipulaciones de System.getenv().
     */
    String getEnvSecret() {
        return System.getenv("JWT_SECRET");
    }

    /**
     * Método protegido para detectar si estamos en producción.
     * Separado para permitir sobrescritura en pruebas unitarias.
     * <p>
     * La detección se basa en {@code System.getenv("DDL_AUTO")} en lugar de la
     * propiedad resuelta de Spring, porque en local la propiedad tiene un default
     * "validate" en {@code application.properties} pero la variable de entorno
     * no está definida. Solo cuando Render inyecta explícitamente
     * {@code DDL_AUTO=validate} se considera entorno de producción/staging.
     */
    boolean isProductionEnvironment() {
        String ddlAutoEnv = System.getenv("DDL_AUTO");
        return "validate".equalsIgnoreCase(ddlAutoEnv);
    }

    @PostConstruct
    public void validate() {
        boolean isProduction = isProductionEnvironment();

        if (isProduction) {
            String envSecret = getEnvSecret();

            // Verificar 1: La variable de entorno debe existir
            if (envSecret == null || envSecret.isBlank()) {
                throw new IllegalStateException(
                    "❌ ERROR FATAL DE SEGURIDAD: JWT_SECRET no configurado.\n" +
                    "En entorno de produccion/staging (DDL_AUTO=validate en env), " +
                    "la variable de entorno JWT_SECRET es OBLIGATORIA.\n" +
                    "Configurala en el dashboard de Render: Settings > Environment Variables > JWT_SECRET.\n" +
                    "La aplicacion se detendra para evitar operar con un secreto inseguro."
                );
            }

            // Verificar 2: No debe usar el valor hardcodeado
            if (DEFAULT_JWT_SECRET.equals(envSecret)) {
                throw new IllegalStateException(
                    "❌ ERROR FATAL DE SEGURIDAD: JWT_SECRET esta usando el valor por defecto.\n" +
                    "La variable de entorno JWT_SECRET coincide con el fallback hardcodeado.\n" +
                    "Genera una clave segura (minimo 256 bits / 32 caracteres) y configurala en Render.\n" +
                    "La aplicacion se detendra para evitar operar con un secreto inseguro."
                );
            }

            log.info("JWT_SECRET validado correctamente para entorno de produccion/staging.");
        } else {
            // Entorno local (DDL_AUTO no definido en env): permitir fallback
            if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
                log.warn("JWT_SECRET usando valor por defecto. " +
                         "Esto es ACEPTABLE para desarrollo local. " +
                         "En produccion/staging, configura la variable de entorno JWT_SECRET en Render.");
            } else {
                log.info("JWT_SECRET personalizado detectado.");
            }
        }
    }
}
