package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Plan de mensualidad propio de una sede (ej. "Básico", "Medio", "Completo").
 * Cada sede tiene su propio catálogo de planes, independiente de las demás sedes:
 * nombre, cupos por escenario y montos preferencial/estándar/mora son libres por sede.
 */
@Entity
@Table(name = "planes_mensualidad")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PlanMensualidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sede;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "monto_preferencial", precision = 12, scale = 2)
    private BigDecimal montoPreferencial;

    @Column(name = "monto_estandar", precision = 12, scale = 2)
    private BigDecimal montoEstandar;

    @Column(name = "monto_mora", precision = 12, scale = 2)
    private BigDecimal montoMora;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "orden")
    private Integer orden;

    /** Días de cada escenario (cancha, pista, gym...) que este plan incluye sin costo extra. */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<PlanCupo> cupos = new java.util.ArrayList<>();
}
