package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
    private Parent parent;

    //Relacion: Un estudiante tiene muchas matriculas (sede + nivel)
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Enrollment> matriculas = new ArrayList<>();

    @Column(name = "nombre_completo", nullable = false)
    private String nombreCompleto;

    @Column(name = "edad")
    private Integer edad;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    //Estado ACTIVO/RETIRADO
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private StudentStatus estado = StudentStatus.ACTIVO;

    //Relacion: Un estudiante tiene muchas asistencias a clases
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Attendance> attendances;

    public enum StudentStatus {
        ACTIVO, RETIRADO
    }
}
