package com.asistencia.erp.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ══════════════════════════════════════════════════════════════════════
 * 🧪 PRUEBAS UNITARIAS - JwtSecretValidator (SEC-JWT-02)
 * ══════════════════════════════════════════════════════════════════════
 *
 * Validación del comportamiento del JwtSecretValidator en diferentes
 * entornos:
 *   - LOCAL (DDL_AUTO no definido en env): debe permitir el fallback sin excepción
 *   - PRODUCCIÓN (DDL_AUTO=validate en env): debe lanzar excepción si
 *     la variable de entorno JWT_SECRET está ausente o usa el default
 *
 * Estrategia: Usamos subclases anónimas para sobrescribir
 * getEnvSecret() e isProductionEnvironment() y así controlar el
 * comportamiento sin depender del entorno real.
 * ══════════════════════════════════════════════════════════════════════
 */
class JwtSecretValidatorTest {

    private static final String DEFAULT_SECRET =
        "asistencias_erp_secret_key_2026_must_be_at_least_256_bits_long_!!!!!";

    private static final String CUSTOM_SECRET = "mi-clave-personalizada-segura-de-256-bits-para-test";

    @Test
    @DisplayName("SEC-JWT-02: Local con default NO lanza excepción")
    void localConDefaultNoLanzaExcepcion() {
        assertDoesNotThrow(() -> {
            JwtSecretValidator validator = new JwtSecretValidator(DEFAULT_SECRET) {
                @Override
                boolean isProductionEnvironment() {
                    return false; // Simula entorno local
                }
            };
            validator.validate();
        }, "En local con default NO debe lanzar excepción");
    }

    @Test
    @DisplayName("SEC-JWT-02: Local con secreto personalizado NO lanza excepción")
    void localConSecretoPersonalizadoNoLanzaExcepcion() {
        assertDoesNotThrow(() -> {
            JwtSecretValidator validator = new JwtSecretValidator(CUSTOM_SECRET) {
                @Override
                boolean isProductionEnvironment() {
                    return false; // Simula entorno local
                }
            };
            validator.validate();
        }, "En local con secreto personalizado NO debe lanzar excepción");
    }

    @Test
    @DisplayName("SEC-JWT-02: Producción sin env var lanza IllegalStateException")
    void produccionSinEnvLanzaExcepcion() {
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            // Sobrescribimos getEnvSecret() para simular que no hay variable de entorno
            JwtSecretValidator validator = new JwtSecretValidator(DEFAULT_SECRET) {
                @Override
                boolean isProductionEnvironment() {
                    return true; // Simula entorno producción
                }
                @Override
                String getEnvSecret() {
                    return null; // Simula que System.getenv("JWT_SECRET") es null
                }
            };
            validator.validate();
        });
        assertTrue(exception.getMessage().contains("JWT_SECRET no configurado"),
            "El mensaje debe indicar que JWT_SECRET no esta configurado");
    }

    @Test
    @DisplayName("SEC-JWT-02: Producción con default en env lanza IllegalStateException")
    void produccionConDefaultEnEnvLanzaExcepcion() {
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            JwtSecretValidator validator = new JwtSecretValidator(DEFAULT_SECRET) {
                @Override
                boolean isProductionEnvironment() {
                    return true; // Simula entorno producción
                }
                @Override
                String getEnvSecret() {
                    return DEFAULT_SECRET; // Simula que env var tiene el valor por defecto
                }
            };
            validator.validate();
        });
        assertTrue(exception.getMessage().contains("JWT_SECRET esta usando el valor por defecto"),
            "El mensaje debe indicar que JWT_SECRET esta usando el valor por defecto");
    }

    @Test
    @DisplayName("SEC-JWT-02: Producción con secreto válido NO lanza excepción")
    void produccionConSecretoValidoNoLanzaExcepcion() {
        assertDoesNotThrow(() -> {
            JwtSecretValidator validator = new JwtSecretValidator(CUSTOM_SECRET) {
                @Override
                boolean isProductionEnvironment() {
                    return true; // Simula entorno producción
                }
                @Override
                String getEnvSecret() {
                    return CUSTOM_SECRET; // Simula que env var tiene un valor personalizado válido
                }
            };
            validator.validate();
        }, "En producción con secreto valido NO debe lanzar excepción");
    }
}
