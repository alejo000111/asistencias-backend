package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "parents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Parent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_completo", nullable = false)
    private String nombreCompleto;

    @Column(name = "telefono", nullable = false, unique = true)
    private String telefono;

    @Column(name = "estado")
    private String estado = "ACTIVO";

    //Dinero abono del padre o madre
    @Column(name = "saldo_abono", precision = 10, scale = 2)
    private BigDecimal saldoAbono = BigDecimal.ZERO;

    //Relación: Un padre tiene muchos hijos
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<Student> students;
}
