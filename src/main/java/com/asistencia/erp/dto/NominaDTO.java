package com.asistencia.erp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * FASE 4 — Resumen de liquidación de un entrenador/empleado dentro de un rango de fechas.
 */
@Data
@AllArgsConstructor
public class NominaDTO {
    private Long empleadoId;
    private String nombreEmpleado;
    private String tipoTarifa;
    private BigDecimal tarifa;
    private long cantidadClases;
    private BigDecimal totalPagar;
}
