package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "paquetes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Paquete {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "precio", precision = 10, scale = 2, nullable = false)
    private BigDecimal precio;

    @Column(name = "clases_incluidas", nullable = false)
    private Integer clasesIncluidas;

    // optional: sede asociada al paquete
    @Column(name = "sede_id")
    private Long sedeId;

    @Column(name = "nombre", nullable = false)
    private String nombre;
}
