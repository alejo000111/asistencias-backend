package com.asistencia.erp.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendances")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //Relación: Muchas asistencias pertenecen a un estudiante
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "student_id", nullable = true, foreignKey = @ForeignKey(name = "FK_attendance_student"))
    @JsonIgnoreProperties({"attendances", "parent", "hibernateLazyInitializer", "handler"})
    private Student student;

    //Relación: Cada asistencia está vinculada a una sede específica
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sede;

    @Column(name = "nombre_estudiante_historico")
    private String nombreEstudianteHistorico;

    @Column(name = "fecha", nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime fecha;

    @Column(name = "es_media_clase")
    private Boolean esMediaClase;

    //CRÍTICO: Inmutable. Se guarda el valor exacto en el momento del entrenamiento. Inmutabilidad histórica
    @Column(name = "precio_cobrado", precision = 10, scale = 2, nullable = false)
    private BigDecimal precioCobrado;

    @Column(name = "nivel_clase")
    private String nivel; // Guardará el nombre del grupo/nivel (ej. "🌱 Iniciación")

    //Indica el tipo de clase: "GRUPAL" o "PERSONALIZADA"
    @Column(name = "tipo_clase")
    private String tipoClase;

    //Nos indicará si esta clase ya fue cubierta por un abono o pago
    @Column(name = "clase_paga", nullable = false)
    private Boolean clasePaga= false;
}
