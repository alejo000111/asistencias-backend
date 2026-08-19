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

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "app_user_sedes",
        joinColumns = @JoinColumn(name = "app_user_id"),
        inverseJoinColumns = @JoinColumn(name = "sede_id")
    )
    private Set<Sede> sedesAutorizadas;

    // ─── Campos exclusivos del club (aplican para rol ADMIN / ADMIN_CLUB) ───

    /** Nombre visible del club/escuela deportiva. */
    @Column(name = "club_nombre", length = 100)
    private String clubNombre;

    /** NIT o identificación fiscal del club. */
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

    /** Fecha de corte de la suscripción SaaS. */
    @Column(name = "fecha_corte")
    private LocalDate fechaCorte;

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
}
