package com.asistencia.erp.dto;

import com.asistencia.erp.entity.Attendance;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AttendanceDTO {
    private Long id;
    private Long studentId;
    private String nombreEstudiante;
    private String nombreEstudianteHistorico;
    private LocalDateTime fecha;
    private BigDecimal precioCobrado;
    private Boolean clasePaga;
    private String nivel;
    private String tipoClase;
    private Boolean esMediaClase;
    private Long sedeId;
    private String sedeNombre;
    private Boolean esCortesia;
    private String telefonoAcudienteCortesia;
    private Long registradoPorId;
    private String registradoPorNombre;
    private Boolean fueraDePlan;
    private Long complementoEvaluadoId;
    private String motivoFueraDePlan;
    /** Solo se completa para deudas de esquema MENSUALIDAD (ver FinancialController#obtenerDeudasPadre). */
    private String planNombre;
    /** "Preferencial" | "Actual" | "Mora" — tarifa vigente hoy, solo para deudas de MENSUALIDAD. */
    private String tarifaLabel;

    public static AttendanceDTO fromEntity(Attendance a) {
        return new AttendanceDTO(
            a.getId(),
            a.getStudent() != null ? a.getStudent().getId() : null,
            a.getStudent() != null ? a.getStudent().getNombreCompleto() : (a.getNombreEstudianteHistorico() != null ? a.getNombreEstudianteHistorico() : "Estudiante retirado"),
            a.getNombreEstudianteHistorico(),
            a.getFecha(),
            a.getPrecioCobrado(),
            a.getClasePaga(),
            a.getNivel(),
            a.getTipoClase(),
            a.getEsMediaClase(),
            a.getSede() != null ? a.getSede().getId() : null,
            a.getSede() != null ? a.getSede().getNombre() : "Sede eliminada",
            Boolean.TRUE.equals(a.getEsCortesia()),
            a.getTelefonoAcudienteCortesia(),
            a.getRegistradoPorId(),
            a.getRegistradoPorNombre(),
            Boolean.TRUE.equals(a.getFueraDePlan()),
            a.getComplementoEvaluadoId(),
            a.getMotivoFueraDePlan(),
            null,
            null
        );
    }
}
