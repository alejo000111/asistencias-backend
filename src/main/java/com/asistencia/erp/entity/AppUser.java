package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.Set;

@Entity
@Table(name = "app_users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /** Nombre completo de la persona (Admin o Empleado). */
    @Column(name = "nombre_completo", length = 150)
    private String nombreCompleto;

    /** Tarifa o costo por clase individual del entrenador/empleado. */
    @Column(name = "tarifa_por_clase", precision = 12, scale = 2)
    private java.math.BigDecimal tarifaPorClase;

    /** Indica si la tarifa configurada se calcula por clase dictada o por hora trabajada. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_tarifa", length = 20)
    @Builder.Default
    private TipoTarifa tipoTarifa = TipoTarifa.POR_CLASE;

    /** FASE 4 — Permiso de recaudación: autoriza al empleado a recibir abonos/pagos en efectivo. */
    @Column(name = "puede_recaudar")
    @Builder.Default
    private Boolean puedeRecaudar = false;

    /**
     * FASE 4 — Aplica únicamente a usuarios con rol ADMIN (el dueño del club también puede
     * registrar asistencias en campo). Si es TRUE (valor por defecto), sus asistencias
     * quedan fuera del cálculo de nómina. Si el propio ADMIN lo desmarca, sus clases
     * registradas se liquidan igual que las de un entrenador, usando su tarifaPorClase.
     */
    @Column(name = "exento_nomina")
    @Builder.Default
    private Boolean exentoNomina = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "app_user_sedes",
        joinColumns = @JoinColumn(name = "app_user_id"),
        inverseJoinColumns = @JoinColumn(name = "sede_id")
    )
    private Set<Sede> sedesAutorizadas;

    // ─── Campos exclusivos del club (aplican para rol ADMIN / ADMIN_CLUB) ───
    @Column(name = "club_id")
    private Long clubId;

    /** Nombre visible del club/escuela deportiva. */
    @Column(name = "club_nombre", length = 100)
    private String clubNombre;

    /** Nombre del administrador responsable del club. */
    @Column(name = "nombre_administrador", length = 100)
    private String nombreAdministrador;

    /** NIT o identificación fiscal del club (opcional). */
    @Column(name = "club_nit", length = 20)
    private String clubNit;

    /** Estado del club ante el SaaS (mora, activo, etc.). */
    @Enumerated(EnumType.STRING)
    @Column(name = "club_estado", length = 30)
    @Builder.Default
    private ClubEstado clubEstado = ClubEstado.ACTIVO;

    /** Tramo de precio del plan SaaS según cantidad de deportistas activos. */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_actual", length = 20)
    private PlanActual planActual;

    /** Fecha de corte de la suscripción SaaS. Puede ser null si el club está exento de tarifa. */
    @Column(name = "fecha_corte")
    private LocalDate fechaCorte;

    /**
     * Club exento de tarifa SaaS (ej. convenios especiales): no tiene fecha de corte y
     * nunca se suspende automáticamente por mora. El SUPERADMIN puede seguir
     * activándolo/suspendiéndolo manualmente en cualquier momento.
     */
    @Column(name = "exento_tarifa")
    @Builder.Default
    private Boolean exentoTarifa = false;

    // ─── Enums ───

    public enum Role {
        SUPERADMIN, ADMIN, EMPLEADO
    }

    /** Estado del club ante la plataforma SaaS. */
    public enum ClubEstado {
        ACTIVO, SUSPENDIDO_POR_MORA
    }

    /** Tramo de precio según deportistas activos. */
    public enum PlanActual {
        TRAMO_1, TRAMO_2, TRAMO_3
    }

    /** FASE 4 — Modalidad de cálculo de la tarifa del empleado/entrenador. */
    public enum TipoTarifa {
        POR_CLASE, POR_HORA
    }
}
