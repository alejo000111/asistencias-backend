package com.asistencia.erp.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "sedes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"nombre", "club_id"})
})
public class Sede {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "activa", nullable = false)
    private Boolean activa = true;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sede_grupos", joinColumns = @JoinColumn(name = "sede_id"))
    private List<GrupoSede> grupos = new ArrayList<>();

    /**
     * Espacio donde se dictan las clases de esta sede (Cancha, Pista, Gimnasio...).
     * Null si el club no usa escenarios — en ese caso no hay control de cuota.
     *
     * EAGER igual que grupos: las asistencias se serializan con su sede y un LAZY
     * aquí reintroduciría el N+1 que documenta el encabezado de AttendanceRepository.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "escenario_id")
    private Escenario escenario;

    public Sede() {}

    public Sede(Long id, String nombre, Boolean activa, Long clubId, List<GrupoSede> grupos) {
        this(id, nombre, activa, clubId, grupos, null);
    }

    public Sede(Long id, String nombre, Boolean activa, Long clubId, List<GrupoSede> grupos, Escenario escenario) {
        this.id = id;
        this.nombre = nombre;
        this.activa = activa;
        this.clubId = clubId;
        this.grupos = grupos;
        this.escenario = escenario;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public Boolean getActiva() { return activa; }
    public void setActiva(Boolean activa) { this.activa = activa; }

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }

    public List<GrupoSede> getGrupos() { return grupos; }
    public void setGrupos(List<GrupoSede> grupos) { this.grupos = grupos; }

    public Escenario getEscenario() { return escenario; }
    public void setEscenario(Escenario escenario) { this.escenario = escenario; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Sede)) return false;
        Sede that = (Sede) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "Sede{id=" + id + ", nombre='" + nombre + "', clubId=" + clubId + "}";
    }
}
