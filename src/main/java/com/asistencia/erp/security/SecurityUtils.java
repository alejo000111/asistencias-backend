package com.asistencia.erp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

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
}
