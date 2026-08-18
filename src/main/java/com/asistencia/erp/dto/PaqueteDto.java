package com.asistencia.erp.dto;

import com.asistencia.erp.entity.Paquete;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaqueteDto {
    private Long id;
    private String nombre;
    private BigDecimal precio;
    private Integer clasesIncluidas;
    private Long sedeId;

    public static PaqueteDto fromEntity(Paquete paquete) {
        if (paquete == null) return null;
        return new PaqueteDto(
                paquete.getId(),
                paquete.getNombre(),
                paquete.getPrecio(),
                paquete.getClasesIncluidas(),
                paquete.getSedeId()
        );
    }
}
