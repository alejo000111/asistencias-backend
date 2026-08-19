package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Precio de un Complemento para una sede de origen específica.
 * Solo se usa cuando Complemento.preciosDiferenciadosPorSede = true.
 */
@Entity
@Table(name = "complemento_sede_precio")
@Data
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ComplementoSedePrecio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complemento_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Complemento complemento;

    /** Sede de origen (dónde está matriculado/facturado el estudiante), no la sede física de asistencia. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_origen_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sedeOrigen;

    @Column(name = "precio", precision = 12, scale = 2, nullable = false)
    private BigDecimal precio;
}
