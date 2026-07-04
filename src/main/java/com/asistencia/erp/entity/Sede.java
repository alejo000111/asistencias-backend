package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sedes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Sede {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, unique = true)
    private String nombre;

    @Column(name = "activa", nullable = false)
    private Boolean activa = true;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "sede_grupos", joinColumns = @JoinColumn(name = "sede_id"))
    private List<GrupoSede> grupos = new ArrayList<>();
}
