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
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ClubConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Admin propietario de esta configuración. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false, unique = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
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

    // ─── Regularidad y Calendario de Matrícula ───
    @Enumerated(EnumType.STRING)
    @Column(name = "regularidad_matricula", length = 20)
    @Builder.Default
    private Regularidad regularidadMatricula = Regularidad.ANUAL;

    @Column(name = "mes_matricula")
    private Integer mesMatricula;

    @Column(name = "dia_limite_matricula")
    private Integer diaLimiteMatricula;

    // ─── Regularidad y Calendario de Seguro ───
    @Enumerated(EnumType.STRING)
    @Column(name = "regularidad_seguro", length = 20)
    @Builder.Default
    private Regularidad regularidadSeguro = Regularidad.ANUAL;

    @Column(name = "mes_seguro")
    private Integer mesSeguro;

    @Column(name = "dia_limite_seguro")
    private Integer diaLimiteSeguro;

    // ─── Calendario Mensualidad ───
    @Column(name = "monto_preferencial", precision = 12, scale = 2)
    private BigDecimal montoPreferencial;

    @Column(name = "dia_limite_preferencial")
    private Integer diaLimitePreferencial;

    @Column(name = "monto_estandar", precision = 12, scale = 2)
    private BigDecimal montoEstandar;

    @Column(name = "monto_mora", precision = 12, scale = 2)
    private BigDecimal montoMora;

    @Column(name = "dia_corte_mora")
    private Integer diaCorteMora;

    @Column(name = "fecha_vigencia_matricula", length = 20)
    private String fechaVigenciaMatricula;

    @Column(name = "fecha_vigencia_seguro", length = 20)
    private String fechaVigenciaSeguro;

    /** JSON con precios por sede (ej: {"1": {"mensualidad": 100000, "grupal": 25000}}) */
    @Column(name = "precios_por_sede_json", columnDefinition = "TEXT")
    private String preciosPorSedeJson;

    // ─── Tarifas por clase asistida ───
    @Column(name = "precio_clase_grupal", precision = 12, scale = 2)
    private BigDecimal precioClaseGrupal;

    @Column(name = "precio_clase_personalizada", precision = 12, scale = 2)
    private BigDecimal precioClasePersonalizada;

    /** JSON con paquetes de clases (ej: [{"clases": 5, "precio": 100000}]) */
    @Column(name = "paquetes_clases_json", columnDefinition = "TEXT")
    private String paquetesClasesJson;

    // ─── Precios por sede ───
    @OneToMany(mappedBy = "clubConfig", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<TarifaSede> tarifasSede = new java.util.ArrayList<>();

    /**
     * Si true, cada sede puede tener precios diferenciados.
     * Si false, todos los precios son iguales en todas las sedes.
     */
    @Column(name = "precios_diferenciados", nullable = false)
    @Builder.Default
    private Boolean preciosDiferenciados = false;

    // ─── Wompi Configuration ───
    @Column(name = "wompi_public_key")
    private String wompiPublicKey;

    // SEC: nunca debe serializarse hacia el frontend (GET /api/config/cobro devuelve esta
    // entidad completa) — solo se lee/escribe internamente y vía el endpoint dedicado
    // /api/config/cobro/wompi, que expone únicamente un booleano "configurada", no el valor.
    @Column(name = "wompi_private_key")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String wompiPrivateKey;

    @Column(name = "wompi_event_secret")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String wompiEventSecret;

    @Column(name = "wompi_environment")
    @Builder.Default
    private String wompiEnvironment = "test";

    // ─── Enums ───

    public enum EsquemaCobro {
        MENSUALIDAD,
        PAQUETE,
        POR_CLASE
    }

    public enum Regularidad {
        ANUAL,
        SEMESTRAL,
        MENSUAL
    }
}
