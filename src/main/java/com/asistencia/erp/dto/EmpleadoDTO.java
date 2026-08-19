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
    private String role;
    private List<Long> sedeIds;
    private List<String> sedeNombres;

    public static EmpleadoDTO fromEntity(AppUser user) {
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
            user.getRole().name(),
            ids,
            nombres
        );
    }
}
