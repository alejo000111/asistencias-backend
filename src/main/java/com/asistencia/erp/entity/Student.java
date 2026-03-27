package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    //Relación: Muchos estudiantes/deportistas a un padre
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = false)
    @JsonIgnore //Para que no haga el efecto espejo en el GET de postman
    private Parent parent;

    @Column(name = "nombre_completo", nullable = false)
    private String nombreCompleto;

    @Column(name = "edad")
    private Integer edad;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    @Column(name = "nivel")
    private String nivel = "INICIACIÓN";

    //Usamos un Enum para limitar los estados a solo dos opciones válidas
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private StudentStatus estado = StudentStatus.ACTIVO;

    //Relación: Un estudiante tiene muchas asistencias a clases
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL)
    private List<Attendance> attendances;

    //Definición de los estados permitidos
    public enum StudentStatus {
        ACTIVO, RETIRADO
    }
}
