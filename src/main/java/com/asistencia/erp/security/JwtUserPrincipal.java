package com.asistencia.erp.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class JwtUserPrincipal {
    private Long userId;
    private String username;
    private String role;
    private List<Long> sedesAutorizadas;
}
