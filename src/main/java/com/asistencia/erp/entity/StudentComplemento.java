package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Asignación de un Complemento a un estudiante (ej. "este estudiante paga Gym Presencial 2 días").
 */
@Entity
@Table(name = "student_complementos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class StudentComplemento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complemento_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Complemento complemento;

    /** Sede desde la cual se factura este complemento (determina el precio si es diferenciado por sede). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_origen_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sedeOrigen;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "fecha_asignacion", nullable = false)
    @Builder.Default
    private LocalDateTime fechaAsignacion = LocalDateTime.now();
}
