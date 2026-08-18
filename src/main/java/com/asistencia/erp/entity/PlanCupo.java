package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Días (o sesiones) de un Escenario que un PlanMensualidad incluye sin costo extra.
 *
 * Unifica lo que antes eran dos cosas distintas: el viejo campo suelto
 * PlanMensualidad.diasCanchaSemana (que además nunca controló cuota, era solo
 * informativo) y la vieja PlanInclusion, que apuntaba a una sede concreta.
 *
 * Al apuntar a un Escenario, el plan "Completo" queda como [Cancha:3, Gym:2, Pista:2]
 * y la cuota se cuenta sobre todas las sedes de ese escenario. La unidad (semanal o
 * mensual) la define el propio escenario.
 */
@Entity
@Table(name = "plan_cupos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PlanCupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private PlanMensualidad plan;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "escenario_id", nullable = false)
    private Escenario escenario;

    /** Cuántos días/sesiones de ese escenario incluye el plan por periodo. */
    @Column(name = "cantidad", nullable = false)
    private Integer cantidad;
}
