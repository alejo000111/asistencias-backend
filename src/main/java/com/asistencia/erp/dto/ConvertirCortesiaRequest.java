package com.asistencia.erp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para convertir una clase de cortesía en un deportista regular registrado.
 * FASE 3 — Se usa desde el botón "Convertir a Deportista" en el historial.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConvertirCortesiaRequest {
    /** ID de la asistencia de cortesía a convertir */
    private Long cortesiaId;
    /** ID de la sede donde se va a matricular (opcional: si no se envía, se usa la de la cortesía) */
    private Long sedeId;
    /** Nivel/grupo en el que se va a matricular (opcional: si no se envía, se usa el de la cortesía) */
    private String nivel;

    // ── Campos de compatibilidad con cortesías antiguas (creadas antes de que el
    // registro generara automáticamente el perfil del cliente). Solo se usan si la
    // asistencia de cortesía no tiene un deportista asociado todavía. ──
    private String nombreAcudiente;
    private String telefonoAcudiente;
    private String nombreDeportista;
}
