package com.asistencia.erp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para registrar una clase de cortesía (prospecto visitante sin matrícula).
 * FASE 3 — No requiere studentId; solo datos básicos del visitante.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CortesiaRequest {
    /** Nombre completo del deportista visitante (prospecto) */
    private String nombreDeportista;
    /** Nombre completo del padre/acudiente */
    private String nombreAcudiente;
    /** Teléfono del acudiente. Sirve para vincular con una familia ya registrada. */
    private String telefonoAcudiente;
    /** ID de la sede donde asiste */
    private Long sedeId;
    /** Nivel/grupo de la clase (ej. "🌱 Iniciación") */
    private String nivel;
    /** Fecha opcional (yyyy-MM-dd). Si es null, se usa la fecha de hoy. */
    private String fecha;
}
