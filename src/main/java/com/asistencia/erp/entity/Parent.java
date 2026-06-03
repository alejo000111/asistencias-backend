package com.asistencia.erp.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
    @Min(0)
    @Column(name = "saldo_abono", precision = 10, scale = 2)
    private BigDecimal saldoAbono = BigDecimal.ZERO;

    @Column(name = "secret_token", unique = true, length = 36)
    private String secretToken;

    //Relación: Un padre tiene muchos hijos
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<Student> students;

    @PrePersist
    public void prePersist() {
        if (secretToken == null || secretToken.isBlank()) {
            secretToken = UUID.randomUUID().toString();
        }
    }
}
