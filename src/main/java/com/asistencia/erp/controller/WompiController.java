package com.asistencia.erp.controller;

import com.asistencia.erp.dto.WompiTransactionInitRequest;
import com.asistencia.erp.dto.WompiTransactionInitResponse;
import com.asistencia.erp.dto.WompiWebhookPayload;
import com.asistencia.erp.service.WompiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/wompi")
public class WompiController {

    @Autowired
    private WompiService wompiService;

    /**
     * Endpoint público (llamado desde el Portal de Padres, sin sesión, sin diferenciación de
     * caller). Por eso el body de error nunca debe llevar el mensaje técnico interno (nombres de
     * excepción, detalles de por qué falló una consulta) — cualquiera en internet puede golpear
     * este endpoint directamente. El detalle completo sí queda en el log del servidor para que
     * el ADMIN o soporte puedan diagnosticarlo; solo dos mensajes de negocio, ya pensados para
     * mostrarse tal cual al padre, se dejan pasar sin filtrar.
     */
    @PostMapping("/transaction/init")
    public ResponseEntity<?> initializeTransaction(@RequestBody WompiTransactionInitRequest request) {
        try {
            WompiTransactionInitResponse response = wompiService.initializeTransaction(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error iniciando transacción Wompi (secretToken={}): {}", request.getSecretToken(), e.getMessage(), e);
            String mensajeSeguro = "No tienes deuda pendiente por pagar.".equals(e.getMessage())
                    ? e.getMessage()
                    : "No se pudo iniciar el pago. Por favor contacta al administrador de tu club.";
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", mensajeSeguro));
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(@RequestBody WompiWebhookPayload payload) {
        try {
            wompiService.processWebhook(payload);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            // Wompi exige una respuesta 2xx aunque falle (para no generar reintentos infinitos),
            // pero el fallo SIEMPRE debe quedar visible en el log del servidor — antes se
            // tragaba en silencio, haciendo imposible diagnosticar por qué un pago aprobado en
            // Wompi nunca se reflejaba como abono en el sistema (firma inválida, transacción no
            // encontrada, club sin event secret, etc.).
            String reference = (payload.getData() != null && payload.getData().getTransaction() != null)
                    ? payload.getData().getTransaction().getReference() : "desconocida";
            log.error("Error procesando webhook de Wompi (reference={}): {}", reference, e.getMessage(), e);
            return ResponseEntity.ok().build();
        }
    }
}
