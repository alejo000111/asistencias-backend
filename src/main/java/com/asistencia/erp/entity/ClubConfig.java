package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Configuración de cobro de un club/escuela deportiva.
 * Vinculada 1:1 al AppUser de tipo ADMIN que administra el club.
 *
 * Permite que cada escuela configure libremente:
 *  - Esquema principal de cobro (mensualidad, paquetes o por clase).
 *  - Conceptos opcionales: matrícula anual y seguro deportivo.
 *  - Precios diferenciados por sede.
 */
@Entity
@Table(name = "club_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClubConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Admin propietario de esta configuración. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false, unique = true)
    private AppUser admin;

    // ─── Esquema principal de cobro ───

    /**
     * Tipo de esquema de cobro a clientes:
     * MENSUALIDAD, PAQUETE o POR_CLASE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "esquema_cobro", nullable = false, length = 20)
    @Builder.Default
    private EsquemaCobro esquemaCobro = EsquemaCobro.MENSUALIDAD;

    // ─── Matrícula anual (opcional) ───

    /** ¿El club cobra matrícula anual? */
    @Column(name = "cobra_matricula", nullable = false)
    @Builder.Default
    private Boolean cobraMatricula = false;

    /** ¿La matrícula es obligatoria (true) u opcional (false)? */
    @Column(name = "matricula_obligatoria", nullable = false)
    @Builder.Default
    private Boolean matriculaObligatoria = false;

    /** Monto de la matrícula anual en COP. */
    @Column(name = "monto_matricula", precision = 12, scale = 2)
    private BigDecimal montoMatricula;

    // ─── Seguro deportivo (opcional) ───

    /** ¿El club cobra seguro deportivo? */
    @Column(name = "cobra_seguro", nullable = false)
    @Builder.Default
    private Boolean cobraSeguro = false;

    /** ¿El seguro es obligatorio (true) u opcional (false)? */
    @Column(name = "seguro_obligatorio", nullable = false)
    @Builder.Default
    private Boolean seguroObligatorio = false;

    /** Monto del seguro deportivo en COP. */
    @Column(name = "monto_seguro", precision = 12, scale = 2)
    private BigDecimal montoSeguro;

    // ─── Precios por sede ───

    /**
     * Si true, cada sede puede tener precios diferenciados.
     * Si false, todos los precios son iguales en todas las sedes.
     */
    @Column(name = "precios_diferenciados", nullable = false)
    @Builder.Default
    private Boolean preciosDiferenciados = false;

    // ─── Enum ───

    public enum EsquemaCobro {
        /** Mensualidad con rangos de días (mora, estándar, preferencial). */
        MENSUALIDAD,
        /** Paquetes de N clases con descuento. */
        PAQUETE,
        /** Se cobra por cada clase asistida al momento del registro. */
        POR_CLASE
    }
}
