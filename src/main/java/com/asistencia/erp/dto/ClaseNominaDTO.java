package com.asistencia.erp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * FASE 4.2 — Una "sesión" de nómina: todas las asistencias que un mismo empleado registró
 * el mismo día en la misma sede se unifican en una sola fila (aunque haya dictado varios
 * grupos/niveles ese día), porque al entrenador se le paga una sola vez por esa jornada.
 */
@Data
@AllArgsConstructor
public class ClaseNominaDTO {
    /** IDs de las asistencias (Attendance) que componen esta sesión unificada. */
    private List<Long> attendanceIds;
    private Long empleadoId;
    private String nombreEmpleado;
    private LocalDate fecha;
    private String sedeNombre;
    /** Niveles/grupos distintos dictados ese día en esa sede (puede ser más de uno). */
    private List<String> niveles;
    private String tipoClase;
    private BigDecimal tarifa;
    private Boolean pagadoNomina;
}
