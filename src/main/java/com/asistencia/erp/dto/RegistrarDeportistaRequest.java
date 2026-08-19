package com.asistencia.erp.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class RegistrarDeportistaRequest {
    private Long parentId;
    private String nombre;
    private String apellido;
    private Integer edad;
    private String fechaNacimiento;
    private Boolean adquiereMatricula;
    private Boolean adquiereSeguro;
    private List<ActualizarDeportistaRequest.MatriculaDTO> matriculas;
}
