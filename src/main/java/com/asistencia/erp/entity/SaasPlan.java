package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Tramo de precio del SaaS configurado por el SUPERADMIN.
 *
 * El sistema recalcula automáticamente en qué tramo se encuentra un club
 * según el total de deportistas en estado ACTIVO:
 *   Tramo 1: 1–20   deportistas activos
 *   Tramo 2: 21–40  deportistas activos
 *   Tramo 3: 41+    deportistas activos (limiteSuperior = null → ilimitado)
 */
@Entity
@Table(name = "saas_plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaasPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre legible del tramo (ej. "Tramo 1 — Hasta 20 deportistas"). */
    @Column(name = "nombre", nullable = false, length = 80)
    private String nombre;

    /** Límite inferior (inclusive) de deportistas activos para este tramo. */
    @Column(name = "limite_inferior", nullable = false)
    private Integer limiteInferior;

    /**
     * Límite superior (inclusive) de deportistas activos.
     * NULL = ilimitado (tramo más alto).
     */
    @Column(name = "limite_superior")
    private Integer limiteSuperior;

    /** Precio mensual del plan en COP. */
    @Column(name = "precio_cop_mensual", nullable = false, precision = 12, scale = 2)
    private BigDecimal precioCopMensual;
}
