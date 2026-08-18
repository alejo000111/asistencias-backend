package com.asistencia.erp.dto;

import com.asistencia.erp.entity.AppUser;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class EmpleadoDTO {
    private Long id;
    private String username;
    private String nombreCompleto;
    private String role;
    private java.math.BigDecimal tarifaPorClase;
    private String tipoTarifa;
    private Boolean puedeRecaudar;
    private Boolean exentoNomina;
    private Boolean esUsuarioActual;
    private List<Long> sedeIds;
    private List<String> sedeNombres;

    public static EmpleadoDTO fromEntity(AppUser user) {
        return fromEntity(user, false);
    }

    public static EmpleadoDTO fromEntity(AppUser user, boolean esUsuarioActual) {
        List<Long> ids = Collections.emptyList();
        List<String> nombres = Collections.emptyList();

        if (user.getSedesAutorizadas() != null && !user.getSedesAutorizadas().isEmpty()) {
            ids = user.getSedesAutorizadas().stream()
                    .map(s -> s != null ? s.getId() : null)
                    .collect(Collectors.toList());
            nombres = user.getSedesAutorizadas().stream()
                    .map(s -> s != null ? s.getNombre() : "Sede eliminada")
                    .collect(Collectors.toList());
        }

        return new EmpleadoDTO(
            user.getId(),
            user.getUsername(),
            user.getNombreCompleto() != null ? user.getNombreCompleto() : user.getUsername(),
            user.getRole().name(),
            user.getTarifaPorClase(),
            user.getTipoTarifa() != null ? user.getTipoTarifa().name() : AppUser.TipoTarifa.POR_CLASE.name(),
            Boolean.TRUE.equals(user.getPuedeRecaudar()),
            user.getExentoNomina() == null || user.getExentoNomina(),
            esUsuarioActual,
            ids,
            nombres
        );
    }
}
