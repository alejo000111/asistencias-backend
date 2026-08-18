package com.asistencia.erp.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Validador de seguridad al arranque de la aplicación.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 📌 PROPÓSITO
 * ══════════════════════════════════════════════════════════════════════
 * Evita que el sistema opere en producción/staging con la ENCRYPTION_KEY
 * hardcodeada de fábrica (usada por {@link EncryptedStringConverter} para
 * cifrar en reposo las credenciales Wompi de ClubConfig). Si por error
 * humano no se inyecta la variable de entorno ENCRYPTION_KEY en Render,
 * la aplicación NO debe arrancar — mismo criterio que JwtSecretValidator
 * para JWT_SECRET, aplicado aquí a la clave de cifrado.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 🔍 LÓGICA DE ENTORNO (Zero-Manual-Changes) — idéntica a JwtSecretValidator
 * ══════════════════════════════════════════════════════════════════════
 * - ENTORNO LOCAL (sin DDL_AUTO en env, o con update/create):
 *   → Permite el uso del fallback hardcodeado para desarrollo sin
 *     configuración manual de variables de entorno.
 *   → Solo emite una advertencia (WARN) en los logs.
 *
 * - ENTORNO PRODUCCIÓN/STAGING (System.getenv("DDL_AUTO") = "validate"):
 *   → Exige que System.getenv("ENCRYPTION_KEY") esté definido.
 *   → Si la variable de entorno es nula, vacía, coincide con el default
 *     hardcodeado, o no decodifica a una clave AES-256 (32 bytes) válida
 *     en base64, la aplicación lanza una excepción fatal y se detiene
 *     INMEDIATAMENTE.
 *
 *   Nota: Se usa System.getenv("DDL_AUTO") en lugar de la propiedad
 *   resuelta @Value("${spring.jpa.hibernate.ddl-auto}") por el mismo
 *   motivo que JwtSecretValidator: en local la propiedad tiene un default
 *   "update" en application.properties pero la variable de entorno NO
 *   está definida. Solo cuando Render inyecta explícitamente
 *   DDL_AUTO=validate se considera producción.
 */
@Slf4j
@Component
public class EncryptionKeyValidator {

    /** El valor de la clave real inyectada por Spring (desde properties o env) */
    private final String encryptionKey;

    public EncryptionKeyValidator(@Value("${encryption.key}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /**
     * Método protegido para obtener la variable de entorno ENCRYPTION_KEY.
     * Separado para permitir sobrescritura en pruebas unitarias
     * sin depender de PowerMock o manipulaciones de System.getenv().
     */
    String getEnvKey() {
        return System.getenv("ENCRYPTION_KEY");
    }

    /**
     * Método protegido para detectar si estamos en producción.
     * Separado para permitir sobrescritura en pruebas unitarias.
     */
    boolean isProductionEnvironment() {
        String ddlAutoEnv = System.getenv("DDL_AUTO");
        return "validate".equalsIgnoreCase(ddlAutoEnv);
    }

    @PostConstruct
    public void validate() {
        boolean isProduction = isProductionEnvironment();

        if (isProduction) {
            String envKey = getEnvKey();

            // Verificar 1: La variable de entorno debe existir
            if (envKey == null || envKey.isBlank()) {
                throw new IllegalStateException(
                    "❌ ERROR FATAL DE SEGURIDAD: ENCRYPTION_KEY no configurado.\n" +
                    "En entorno de produccion/staging (DDL_AUTO=validate en env), " +
                    "la variable de entorno ENCRYPTION_KEY es OBLIGATORIA (cifra en reposo las credenciales Wompi).\n" +
                    "Configurala en el dashboard de Render: Settings > Environment Variables > ENCRYPTION_KEY.\n" +
                    "Genera una clave con: openssl rand -base64 32\n" +
                    "La aplicacion se detendra para evitar operar con una clave de cifrado insegura."
                );
            }

            // Verificar 2: No debe usar el valor hardcodeado
            if (EncryptedStringConverter.DEFAULT_ENCRYPTION_KEY_B64.equals(envKey)) {
                throw new IllegalStateException(
                    "❌ ERROR FATAL DE SEGURIDAD: ENCRYPTION_KEY esta usando el valor por defecto.\n" +
                    "La variable de entorno ENCRYPTION_KEY coincide con el fallback hardcodeado de desarrollo.\n" +
                    "Genera una clave segura con: openssl rand -base64 32, y configurala en Render.\n" +
                    "La aplicacion se detendra para evitar operar con una clave de cifrado insegura."
                );
            }

            // Verificar 3: Debe decodificar a una clave AES-256 (32 bytes) válida en base64
            try {
                byte[] decoded = Base64.getDecoder().decode(envKey.trim());
                if (decoded.length != 32) {
                    throw new IllegalStateException(
                        "❌ ERROR FATAL DE SEGURIDAD: ENCRYPTION_KEY invalida.\n" +
                        "Debe decodificar a 256 bits (32 bytes) en base64; se recibieron " + decoded.length + " bytes.\n" +
                        "Genera una clave valida con: openssl rand -base64 32\n" +
                        "La aplicacion se detendra para evitar operar con una clave de cifrado insegura."
                    );
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "❌ ERROR FATAL DE SEGURIDAD: ENCRYPTION_KEY invalida.\n" +
                    "El valor configurado no es base64 valido.\n" +
                    "Genera una clave valida con: openssl rand -base64 32\n" +
                    "La aplicacion se detendra para evitar operar con una clave de cifrado insegura.", e
                );
            }

            log.info("ENCRYPTION_KEY validada correctamente para entorno de produccion/staging.");
        } else {
            // Entorno local (DDL_AUTO no definido en env): permitir fallback
            if (EncryptedStringConverter.DEFAULT_ENCRYPTION_KEY_B64.equals(encryptionKey)) {
                log.warn("ENCRYPTION_KEY usando valor por defecto. " +
                         "Esto es ACEPTABLE para desarrollo local. " +
                         "En produccion/staging, configura la variable de entorno ENCRYPTION_KEY en Render.");
            } else {
                log.info("ENCRYPTION_KEY personalizada detectada.");
            }
        }
    }
}
