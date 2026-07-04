package com.asistencia.erp.dto;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DTO para resumen de padres — evita serializar la entidad JPA directamente (PERF-JACKSON-01).
 * Expone solo los campos necesarios para los listados financieros y de clientes.
 */
public record ParentSummaryDTO(
    Long id,
    String nombreCompleto,
    String telefono,
    String estado,
    BigDecimal saldoAbono,
    List<StudentSummaryDTO> estudiantes
) {
    public static ParentSummaryDTO fromEntity(Parent parent) {
        List<StudentSummaryDTO> estudiantes = Collections.emptyList();
        if (parent.getStudents() != null) {
            estudiantes = parent.getStudents().stream()
                .map(s -> new StudentSummaryDTO(s.getId(), s.getNombreCompleto(), s.getEstado().name()))
                .collect(Collectors.toList());
        }
        return new ParentSummaryDTO(
            parent.getId(),
            parent.getNombreCompleto(),
            parent.getTelefono(),
            parent.getEstado(),
            parent.getSaldoAbono(),
            estudiantes
        );
    }

    public record StudentSummaryDTO(
        Long id,
        String nombreCompleto,
        String estado
    ) {}
}
