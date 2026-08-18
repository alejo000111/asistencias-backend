package com.asistencia.erp.service;

import com.asistencia.erp.dto.WompiTransactionInitRequest;
import com.asistencia.erp.dto.WompiTransactionInitResponse;
import com.asistencia.erp.dto.WompiWebhookPayload;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.PaymentTransaction;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.PaymentTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class WompiService {

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private ClubConfigRepository clubConfigRepository;

    @Autowired
    private ParentRepository parentRepository;

    @Autowired
    private FinancialLogRepository financialLogRepository;

    public WompiTransactionInitResponse initializeTransaction(WompiTransactionInitRequest request) {
        Parent parent = parentRepository.findBySecretToken(request.getSecretToken())
                .orElseThrow(() -> new RuntimeException("Parent not found"));

        // SEC-CRÍTICO: el club SIEMPRE se deriva del padre autenticado por su secretToken —
        // NUNCA del clubId que envía el cliente. Antes se resolvía con
        // clubConfigRepository.findById(request.getClubId()), que es el PK autogenerado de
        // ClubConfig (un espacio de ids distinto al "clubId" = admin_id que usa el resto del
        // sistema), sin comparar contra el padre real. El portal de padres tampoco incluía el
        // clubId real en su DTO, así que el frontend siempre mandaba un valor por defecto —
        // en la práctica, TODOS los pagos de TODOS los clubes se procesaban contra la cuenta
        // Wompi del club cuyo ClubConfig tuviera PK=1, mezclando el dinero de todos los tenants.
        Long clubId = parent.getClubId();
        if (clubId == null) {
            throw new RuntimeException("El acudiente no tiene un club asociado.");
        }

        ClubConfig clubConfig = clubConfigRepository.findByAdminId(clubId)
                .orElseThrow(() -> new RuntimeException("Club Config not found"));

        if (clubConfig.getWompiPublicKey() == null || clubConfig.getWompiPublicKey().isBlank()) {
            throw new RuntimeException("El club no tiene configurada su cuenta de Wompi.");
        }

        long amountInCents = request.getAmount().multiply(new BigDecimal(100)).longValue();
        String reference = UUID.randomUUID().toString();

        PaymentTransaction transaction = PaymentTransaction.builder()
                .reference(reference)
                .amountInCents(amountInCents)
                .currency(request.getCurrency())
                .parent(parent)
                .clubId(clubId)
                .build();

        paymentTransactionRepository.save(transaction);

        // Generate integrity signature if Wompi Event Secret is configured
        String signature = "";
        if (clubConfig.getWompiEventSecret() != null && !clubConfig.getWompiEventSecret().isBlank()) {
            String concatenatedString = reference + amountInCents + request.getCurrency() + clubConfig.getWompiEventSecret();
            signature = generateSha256(concatenatedString);
        }

        return WompiTransactionInitResponse.builder()
                .reference(reference)
                .amountInCents(amountInCents)
                .currency(request.getCurrency())
                .publicKey(clubConfig.getWompiPublicKey())
                .signature(signature)
                .build();
    }

    @Transactional
    public void processWebhook(WompiWebhookPayload payload) {
        if (!"transaction.updated".equals(payload.getEvent())) {
            return;
        }

        String reference = payload.getData().getTransaction().getReference();
        PaymentTransaction transaction = paymentTransactionRepository.findByReference(reference)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Only process pending transactions
        if (transaction.getStatus() != PaymentTransaction.TransactionStatus.PENDING) {
            return; // Already processed
        }

        // clubId en PaymentTransaction es el admin_id real (mismo espacio de ids que el resto
        // del sistema) — ver initializeTransaction, ya no el PK de ClubConfig.
        ClubConfig clubConfig = clubConfigRepository.findByAdminId(transaction.getClubId())
                .orElseThrow(() -> new RuntimeException("Club config not found"));

        // SEC-CRÍTICO: validar SIEMPRE la firma del webhook, nunca solo "si el club configuró
        // el secreto". Antes, mientras un club no llenara wompiEventSecret, el webhook se
        // aceptaba sin ninguna verificación — cualquiera en internet podía forjar un POST a
        // /api/wompi/webhook con un reference obtenido de /transaction/init y marcarlo
        // APPROVED, generando un pago falso.
        if (clubConfig.getWompiEventSecret() == null || clubConfig.getWompiEventSecret().isBlank()) {
            throw new RuntimeException("El club no tiene configurado el secreto de eventos de Wompi; no se puede validar este webhook.");
        }
        if (payload.getSignature() == null
                || payload.getSignature().getProperties() == null
                || payload.getSignature().getProperties().isEmpty()
                || payload.getSignature().getChecksum() == null) {
            throw new RuntimeException("Webhook sin firma.");
        }

        // Fórmula oficial de Wompi: concatenar (sin separadores) el VALOR de cada propiedad
        // listada dinámicamente en signature.properties, en ese orden, luego el timestamp del
        // evento y luego el secreto — NUNCA "environment" (no forma parte del checksum real).
        StringBuilder concatenado = new StringBuilder();
        for (String propiedad : payload.getSignature().getProperties()) {
            concatenado.append(resolverPropiedadFirma(propiedad, payload.getData().getTransaction()));
        }
        concatenado.append(payload.getTimestamp());
        concatenado.append(clubConfig.getWompiEventSecret());
        String generatedHash = generateSha256(concatenado.toString());

        if (!generatedHash.equals(payload.getSignature().getChecksum())) {
            throw new RuntimeException("Firma de webhook inválida.");
        }

        String status = payload.getData().getTransaction().getStatus();
        transaction.setWompiTransactionId(payload.getData().getTransaction().getId());

        if ("APPROVED".equals(status)) {
            transaction.setStatus(PaymentTransaction.TransactionStatus.APPROVED);

            // Register Financial Log
            FinancialLog log = new FinancialLog();
            log.setParent(transaction.getParent());
            log.setClubId(transaction.getClubId());
            log.setFecha(LocalDateTime.now());
            log.setMonto(new BigDecimal(transaction.getAmountInCents()).divide(new BigDecimal(100)));
            log.setTipoMovimiento(FinancialLog.MovementType.PAGO_DIRECTO);
            log.setConcepto("Pago Deuda vía Wompi");
            log.setMetodoPago(FinancialLog.PaymentMethod.WOMPI);
            log.setDetalles("Wompi TX: " + transaction.getWompiTransactionId());
            financialLogRepository.save(log);

            // TODO: Aqui también se debería actualizar ParentSnapshot u otra entidad que represente la deuda actual para poner al cliente 'al día'.
            // Como el modelo de deuda exacto requiere más investigación, por ahora registramos el pago.

        } else if ("DECLINED".equals(status)) {
            transaction.setStatus(PaymentTransaction.TransactionStatus.DECLINED);
        } else if ("ERROR".equals(status)) {
            transaction.setStatus(PaymentTransaction.TransactionStatus.ERROR);
        } else if ("VOIDED".equals(status)) {
            transaction.setStatus(PaymentTransaction.TransactionStatus.VOIDED);
        }

        paymentTransactionRepository.save(transaction);
    }

    /** Resuelve el valor de una propiedad declarada en signature.properties (ej. "transaction.id"). */
    private String resolverPropiedadFirma(String propertyPath, WompiWebhookPayload.Transaction tx) {
        return switch (propertyPath) {
            case "transaction.id" -> String.valueOf(tx.getId());
            case "transaction.status" -> String.valueOf(tx.getStatus());
            case "transaction.amount_in_cents" -> String.valueOf(tx.getAmountInCents());
            case "transaction.reference" -> String.valueOf(tx.getReference());
            case "transaction.currency" -> String.valueOf(tx.getCurrency());
            default -> throw new RuntimeException("Propiedad de firma de webhook desconocida: " + propertyPath);
        };
    }

    private String generateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating SHA-256 hash", e);
        }
    }
}
