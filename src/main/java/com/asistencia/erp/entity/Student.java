package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "students")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //Relacion: Muchos estudiantes/deportistas a un padre
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    private Parent parent;

    //Relacion: Un estudiante tiene muchas matriculas (sede + nivel)
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private List<Enrollment> matriculas = new ArrayList<>();

    @Column(name = "nombre_completo", nullable = false)
    private String nombreCompleto;

    @Column(name = "edad")
    private Integer edad;

    @Column(name = "fecha_nacimiento")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate fechaNacimiento;

    //Estado ACTIVO/RETIRADO
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private StudentStatus estado = StudentStatus.ACTIVO;

    /** ¿El deportista ha adquirido matrícula individual? */
    @Column(name = "adquiere_matricula", nullable = false)
    private Boolean adquiereMatricula = false;

    /** ¿El deportista ha adquirido seguro deportivo individual? */
    @Column(name = "adquiere_seguro", nullable = false)
    private Boolean adquiereSeguro = false;

    /** Saldo de clases disponibles (esquema PAQUETE) */
    @Column(name = "clases_disponibles")
    private Integer clasesDisponibles = 0;

    //Relacion: Un estudiante tiene muchas asistencias a clases
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private List<Attendance> attendances;

    @Column(name = "club_id")
    private Long clubId;

    public enum StudentStatus {
        ACTIVO, RETIRADO,
        /** Prospecto: tomó una clase de cortesía pero aún no está matriculado formalmente. */
        CORTESIA
    }
}
