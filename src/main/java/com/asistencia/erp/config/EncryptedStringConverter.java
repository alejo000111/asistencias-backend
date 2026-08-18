package com.asistencia.erp.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Convertidor JPA que cifra en reposo los campos sensibles de {@code ClubConfig}
 * (credenciales Wompi: {@code wompiPrivateKey}, {@code wompiEventSecret}).
 *
 * ══════════════════════════════════════════════════════════════════════
 * 📌 PROPÓSITO
 * ══════════════════════════════════════════════════════════════════════
 * Antes, estas credenciales se guardaban en texto plano en la tabla
 * {@code club_configs}. Cualquiera con acceso de lectura a la base de
 * datos (backup, dump, réplica, acceso indebido) podía leerlas y operar
 * la cuenta de Wompi del club. Este convertidor cifra el valor con
 * AES-256-GCM antes de escribirlo, y lo descifra transparentemente al
 * leerlo — el resto del código (entidad, servicios, controladores) sigue
 * viendo el valor en texto plano en memoria, como si no existiera.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 🔐 FORMATO DE ALMACENAMIENTO
 * ══════════════════════════════════════════════════════════════════════
 * base64( IV[12 bytes] || ciphertext-con-tag-GCM[16 bytes] )
 * Un IV aleatorio nuevo por cada cifrado (requisito de seguridad de GCM:
 * jamás reutilizar IV con la misma clave), guardado junto al ciphertext
 * porque no es secreto — se necesita para descifrar.
 *
 * ══════════════════════════════════════════════════════════════════════
 * 🔑 CLAVE (ENCRYPTION_KEY) — mismo patrón "Zero-Manual-Changes" que JWT_SECRET
 * ══════════════════════════════════════════════════════════════════════
 * Ver {@link EncryptionKeyValidator} para la validación de arranque que
 * exige ENCRYPTION_KEY en producción/staging (DDL_AUTO=validate). Aquí
 * solo se resuelve el valor (igual que JwtUtil resuelve jwt.secret) y se
 * usa; el fallback hardcodeado es únicamente para desarrollo local sin
 * configuración manual.
 *
 * Nota: al no haber datos reales de clientes en la base de datos al
 * momento de introducir este cifrado, no se requiere migración de datos
 * existentes en texto plano — es un corte limpio (clean cutover).
 */
@Slf4j
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /**
     * Fallback de desarrollo local, en base64, de una clave AES-256 (32 bytes).
     * Generada una única vez y hardcodeada aquí para que el arranque en local no
     * requiera configuración manual (mismo espíritu que JwtSecretValidator.DEFAULT_JWT_SECRET).
     * NUNCA debe usarse en producción/staging — ver {@link EncryptionKeyValidator}.
     */
    public static final String DEFAULT_ENCRYPTION_KEY_B64 = "k6KofEsL8jXbjD+T2TxYuwFn1BM+O9obVF+THCz5ZkY=";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32; // 256 bits

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec secretKey;

    public EncryptedStringConverter(@Value("${encryption.key}") String encryptionKeyBase64) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encryptionKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "ENCRYPTION_KEY invalida: el valor configurado no es base64 valido.", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                "ENCRYPTION_KEY invalida: debe decodificar a 256 bits (32 bytes) en base64; " +
                "se recibieron " + keyBytes.length + " bytes.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] ivYCiphertext = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, ivYCiphertext, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivYCiphertext, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(ivYCiphertext);
        } catch (GeneralSecurityException e) {
            // No se loguea el valor en texto plano: es una credencial sensible (Wompi).
            log.error("Fallo al cifrar un valor sensible para almacenamiento en base de datos.", e);
            throw new IllegalStateException("No se pudo cifrar el valor para almacenamiento.", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        try {
            byte[] ivYCiphertext = Base64.getDecoder().decode(dbData);
            if (ivYCiphertext.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalStateException("Valor cifrado invalido: longitud insuficiente para contener el IV.");
            }
            byte[] iv = Arrays.copyOfRange(ivYCiphertext, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(ivYCiphertext, GCM_IV_LENGTH_BYTES, ivYCiphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            log.error("Fallo al descifrar un valor sensible leido de base de datos.", e);
            throw new IllegalStateException("No se pudo descifrar el valor leido de base de datos.", e);
        }
    }
}
