package com.asistencia.erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrupoSede {

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "emoji")
    private String emoji;

    @Column(name = "color_hex")
    private String colorHex;
}
