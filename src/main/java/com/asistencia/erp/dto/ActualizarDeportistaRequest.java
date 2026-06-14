package com.asistencia.erp.dto;

import lombok.Data;
import java.util.List;

@Data
public class ActualizarDeportistaRequest {
    private String nombreCompleto;
    private Integer edad;
    private String fechaNacimiento;
    private String estado;
    private List<MatriculaDTO> matriculas;

    @Data
    public static class MatriculaDTO {
        private Long sedeId;
        private String nivel;
    }
}
