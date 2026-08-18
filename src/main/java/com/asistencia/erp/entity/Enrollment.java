package com.asistencia.erp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "enrollments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Enrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    @JsonIgnore
    private Student student;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sede_id", nullable = false)
    private Sede sede;

    @Column(name = "nivel", nullable = false)
    private String nivel;

    /** Plan de mensualidad elegido en esta sede. Debe pertenecer a la misma sede. Null = usa el esquema legado (ClubConfig/TarifaSede). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_mensualidad_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "sede", "cupos"})
    private PlanMensualidad planMensualidad;

    /**
     * Sede principal del deportista: la mensualidad se calcula siempre con el plan de esta
     * matrícula, sin importar en qué sede se registre la primera clase del mes. Solo una
     * matrícula por estudiante puede tener este flag en true (se normaliza al guardar).
     */
    @Column(name = "es_principal", nullable = false)
    private Boolean esPrincipal = false;
}
