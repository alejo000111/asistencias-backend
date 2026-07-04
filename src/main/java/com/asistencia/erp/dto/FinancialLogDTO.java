package com.asistencia.erp.dto;

import com.asistencia.erp.entity.FinancialLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para FinancialLog — evita serializar la entidad JPA directamente (PERF-JACKSON-01).
 * Expone solo los campos que el frontend de Vue 3 necesita.
 */
public record FinancialLogDTO(
    Long id,
    Long parentId,
    String nombreCliente,
    LocalDateTime fecha,
    BigDecimal monto,
    String tipoMovimiento,
    String metodoPago
) {
    public static FinancialLogDTO fromEntity(FinancialLog log) {
        return new FinancialLogDTO(
            log.getId(),
            log.getParent() != null ? log.getParent().getId() : null,
            log.getNombreClienteRespaldo() != null
                ? log.getNombreClienteRespaldo()
                : (log.getParent() != null ? log.getParent().getNombreCompleto() : "Cliente eliminado"),
            log.getFecha(),
            log.getMonto(),
            log.getTipoMovimiento().name(),
            log.getMetodoPago().name()
        );
    }
}
