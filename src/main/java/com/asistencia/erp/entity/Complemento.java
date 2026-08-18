package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Servicio adicional opcional a la mensualidad (ej. Gym Virtual, Gym Presencial,
 * Pista Adicional, Valor x Clase). Catálogo a nivel club: cada complemento decide
 * por sí mismo si su precio es único para todo el club (precioBase) o diferenciado
 * por sede de origen (ver ComplementoSedePrecio), para que cada club configure sus
 * propias reglas.
 */
@Entity
@Table(name = "complementos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Complemento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    /**
     * Cuota incluida en este complemento, en la unidad del escenario al que apunta
     * (semanal o mensual según Escenario.periodo). Null = sin tope.
     */
    @Column(name = "veces_por_semana")
    private Integer vecesPorPeriodo;

    /**
     * Escenario donde se toma asistencia bajo este complemento (ej. el escenario "Pista").
     * Null si el complemento no controla asistencia (ej. un "Valor x Clase" suelto).
     *
     * La cuota se cuenta agregando todas las sedes de ese escenario, y el periodo
     * (semanal/mensual) lo define el propio escenario — así un plan y sus complementos
     * sobre el mismo espacio nunca quedan con ventanas de conteo contradictorias.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "escenario_id")
    private Escenario escenario;

    /** Grupo/nivel al que aplica la cuota (debe coincidir con Sede.grupos[].nombre). Null = aplica a todo el escenario. */
    @Column(name = "grupo_nombre")
    private String grupoNombre;

    /** Si true, el precio se resuelve por sede de origen (ver ComplementoSedePrecio); si false, aplica precioBase para todo el club. */
    @Column(name = "precios_diferenciados_por_sede", nullable = false)
    @Builder.Default
    private Boolean preciosDiferenciadosPorSede = false;

    @Column(name = "precio_base", precision = 12, scale = 2)
    private BigDecimal precioBase;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @OneToMany(mappedBy = "complemento", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<ComplementoSedePrecio> preciosPorSede = new java.util.ArrayList<>();
}
