package com.asistencia.erp.service;

import com.asistencia.erp.dto.WompiTransactionInitRequest;
import com.asistencia.erp.dto.WompiTransactionInitResponse;
import com.asistencia.erp.dto.WompiWebhookPayload;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.PaymentTransaction;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.PaymentTransactionRepository;
import com.asistencia.erp.service.billing.ClubConfigResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class WompiService {

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private ClubConfigRepository clubConfigRepository;

    @Autowired
    private ParentRepository parentRepository;

    @Autowired
    private FinancialLogRepository financialLogRepository;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private ClubConfigResolver clubConfigResolver;

    @Autowired
    private FinancialService financialService;

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

        // SEC-CRÍTICO: el monto a cobrar NUNCA se toma de lo que envía el cliente
        // (request.getAmount() se ignora por completo) — se calcula siempre en el servidor a
        // partir de la deuda real del padre, con la misma fórmula que expone el Portal de
        // Padres. Antes un llamado directo a este endpoint público (sin sesión) podía iniciar
        // una transacción por cualquier monto, incluido 0.01 o negativo.
        BigDecimal deuda = calcularDeudaTotal(parent);
        if (deuda.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("No tienes deuda pendiente por pagar.");
        }
        long amountInCents = deuda.multiply(new BigDecimal(100)).longValue();
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

        // Atajo de solo-lectura: evita repetir la verificación de firma para una referencia que
        // ya se sabe procesada. La protección real contra procesamiento duplicado (dos entregas
        // casi simultáneas del mismo webhook) es la transición atómica más abajo, no esta lectura.
        if (transaction.getStatus() != PaymentTransaction.TransactionStatus.PENDING) {
            return;
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
        String wompiTransactionId = payload.getData().getTransaction().getId();

        PaymentTransaction.TransactionStatus nuevoEstado = switch (status) {
            case "APPROVED" -> PaymentTransaction.TransactionStatus.APPROVED;
            case "DECLINED" -> PaymentTransaction.TransactionStatus.DECLINED;
            case "ERROR" -> PaymentTransaction.TransactionStatus.ERROR;
            case "VOIDED" -> PaymentTransaction.TransactionStatus.VOIDED;
            default -> null;
        };
        if (nuevoEstado == null) {
            log.warn("Webhook de Wompi con estado no accionable '{}' para reference={}", status, reference);
            return;
        }

        // SEC-CRÍTICO: transición atómica PENDING -> nuevoEstado en base de datos (UPDATE
        // condicional, no "leer -> decidir en memoria -> guardar"). Si esto devuelve 0 filas,
        // otra entrega concurrente de este mismo webhook (Wompi reintenta si no responde 2xx a
        // tiempo) ya ganó la carrera y transicionó la transacción primero — no se debe acreditar
        // el pago dos veces.
        int filasActualizadas = paymentTransactionRepository.marcarProcesadaSiPendiente(
                reference, nuevoEstado, wompiTransactionId);
        if (filasActualizadas == 0) {
            log.warn("Webhook de Wompi ignorado por transición concurrente ya ganada — reference={}", reference);
            return;
        }

        if (nuevoEstado == PaymentTransaction.TransactionStatus.APPROVED) {
            BigDecimal monto = new BigDecimal(transaction.getAmountInCents()).divide(new BigDecimal(100));
            // Completa el TODO histórico de este servicio: un pago aprobado por Wompi ahora se
            // acredita por el MISMO camino que un abono manual registrado por el ADMIN (suma
            // saldoAbono del padre y reconcilia FIFO sus clases/cargos impagos) — antes solo se
            // escribía una fila en la bitácora financiera sin tocar la deuda real, así que el
            // Portal de Padres seguía mostrando exactamente la misma deuda después de pagar.
            financialService.registrarAbono(
                    transaction.getParent().getId(), monto, FinancialLog.PaymentMethod.WOMPI, LocalDate.now());
        }
    }

    /**
     * Deuda total real y vigente del padre (clases MENSUALIDAD impagas repriciadas a la tarifa
     * de hoy + cargos extra pendientes), calculada en el servidor con la misma fórmula que usa
     * el Portal de Padres para mostrar "Total a Pagar" — nunca a partir de un monto enviado por
     * el cliente. Réplica intencional (no una extracción compartida) para no tocar
     * PublicPortalController/FinancialService en este cambio; ver PublicPortalController.obtenerPortal
     * si esta fórmula necesita evolucionar en el futuro, para mantenerlas alineadas.
     */
    private BigDecimal calcularDeudaTotal(Parent parent) {
        ClubConfig config = clubConfigResolver.resolveForParent(parent);

        List<Attendance> deudasClases = (config != null && config.getEsquemaCobro() == ClubConfig.EsquemaCobro.PAQUETE)
                ? List.of()
                : attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parent.getId()).stream()
                    .filter(a -> a.getPrecioCobrado() != null && a.getPrecioCobrado().compareTo(BigDecimal.ZERO) > 0)
                    .toList();

        BigDecimal deudaClasesTotal = BigDecimal.ZERO;
        for (Attendance att : deudasClases) {
            BigDecimal monto = att.getPrecioCobrado();
            if (config != null && config.getEsquemaCobro() == ClubConfig.EsquemaCobro.MENSUALIDAD
                    && monto.compareTo(BigDecimal.ZERO) > 0) {
                monto = financialService.calcularPrecioMensualidadDeudaVigente(att.getStudent(), config, att);
            }
            deudaClasesTotal = deudaClasesTotal.add(monto);
        }

        List<FinancialLog> cargosExtras = financialLogRepository
                .findByParentIdAndTipoMovimiento(parent.getId(), FinancialLog.MovementType.CARGO_EXTRA)
                .stream()
                .filter(cargo -> cargo.getConcepto() == null || !cargo.getConcepto().toLowerCase().startsWith("paquete"))
                .toList();

        BigDecimal deudaExtrasTotal = cargosExtras.stream()
                .map(FinancialLog::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return deudaClasesTotal.add(deudaExtrasTotal);
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
