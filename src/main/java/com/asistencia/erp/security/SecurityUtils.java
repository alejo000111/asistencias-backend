package com.asistencia.erp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import com.asistencia.erp.entity.Student;

public class SecurityUtils {

    public static JwtUserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtUserPrincipal) {
            return (JwtUserPrincipal) auth.getPrincipal();
        }
        return null;
    }

    public static boolean isEmpleado() {
        JwtUserPrincipal user = getCurrentUser();
        return user != null && "EMPLEADO".equals(user.getRole());
    }

    public static boolean isAdmin() {
        JwtUserPrincipal user = getCurrentUser();
        return user != null && "ADMIN".equals(user.getRole());
    }

    public static List<Long> getSedesAutorizadas() {
        JwtUserPrincipal user = getCurrentUser();
        if (user != null && user.getSedesAutorizadas() != null) {
            return user.getSedesAutorizadas();
        }
        return Collections.emptyList();
    }

    /**
     * Verifica si un estudiante pertenece a alguna de las sedes autorizadas.
     * Revisa matrículas activas primero, luego asistencias históricas.
     * Centralizada aquí para evitar duplicación (antes estaba en AsistenciaController y ClienteController).
     */
    public static boolean estudianteEnSede(Student s, List<Long> sedesIds) {
        if (s.getMatriculas() != null &&
                s.getMatriculas().stream().anyMatch(m -> m.getSede() != null && sedesIds.contains(m.getSede().getId()))) {
            return true;
        }
        return s.getAttendances() != null &&
                s.getAttendances().stream().anyMatch(a -> a.getSede() != null && sedesIds.contains(a.getSede().getId()));
    }
}
