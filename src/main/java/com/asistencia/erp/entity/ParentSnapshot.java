package com.asistencia.erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.math.BigDecimal;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParentSnapshot {

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "original_nombre_completo")
    private String originalNombreCompleto;

    @Column(name = "original_telefono")
    private String originalTelefono;

    @Column(name = "original_estado")
    private String originalEstado;

    @Column(name = "original_saldo_abono", precision = 10, scale = 2)
    private BigDecimal originalSaldoAbono;
}
