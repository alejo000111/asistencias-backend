package com.asistencia.erp.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO para registrar la compra de un paquete por parte de un padre.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompraPaqueteRequest {
    @NotNull
    private Long parentId;

    private Long paqueteId;

    private String nombrePaquete;

    private Integer clasesIncluidas;

    private BigDecimal precio;

    private Long sedeId;

    /**
     * ID del estudiante al que se asignan las clases del paquete.
     * Si es null, las clases se dividen equitativamente entre todos los hijos activos del padre.
     */
    private Long studentId;

    @NotNull
    private String metodoPago; // debe coincidir con FinancialLog.PaymentMethod enum values

    // Fecha de compra opcional, si no se envía se usará la fecha actual en el servicio
    private String fechaCompra;
}
