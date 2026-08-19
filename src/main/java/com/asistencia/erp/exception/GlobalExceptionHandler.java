package com.asistencia.erp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Manejador global de excepciones para asegurar que todas las respuestas de error
 * se devuelvan en formato JSON estructurado y con mensajes en español.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Excepción de argumento inválido: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Parámetros de solicitud inválidos."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Acceso denegado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No tienes permisos suficientes para realizar esta acción."));
    }

    @ExceptionHandler(UnexpectedRollbackException.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedRollback(UnexpectedRollbackException ex) {
        log.error("Error de transacción (rollback): {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "La operación no pudo completarse debido a un error en el procesamiento de los datos. Por favor revisa la información enviada e intenta nuevamente."));
    }

    /**
     * Debe declararse ANTES del catch-all de Exception.class: sin un @ExceptionHandler propio,
     * Spring's ExceptionHandlerExceptionResolver intercepta un ResponseStatusException lanzado
     * por un controlador (ej. el 404 "Token inválido" de PublicPortalController) antes de que el
     * ResponseStatusExceptionResolver por defecto llegue a procesarlo — así que terminaba
     * cayendo al catch-all genérico y saliendo como 500 con el mensaje interno filtrado, en vez
     * del 404 limpio que el controlador quería devolver. Corregido re-emitiendo el status/reason
     * originales tal como el controlador los definió.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("ResponseStatusException: {} — {}", ex.getStatusCode(), ex.getReason());
        HttpStatusCode status = ex.getStatusCode();
        String mensaje = ex.getReason() != null ? ex.getReason() : "Solicitud inválida.";
        return ResponseEntity.status(status).body(Map.of("error", mensaje));
    }

    /** Body JSON mal formado o de tipo incorrecto — error del cliente, no un fallo del servidor. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Cuerpo de solicitud ilegible: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "La solicitud enviada no tiene un formato válido."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        // SEC: nunca se expone ex.getMessage() al cliente aquí — puede contener nombres de tabla,
        // restricciones SQL, rutas internas o nombres de clase de excepción. El detalle completo
        // queda en el log del servidor para diagnóstico; el cliente solo recibe un mensaje genérico.
        log.error("Error no controlado en el servidor: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Ocurrió un error interno en el servidor. Por favor intenta de nuevo o contacta a soporte si el problema persiste."));
    }
}
