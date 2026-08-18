package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserRepository appUserRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final com.asistencia.erp.service.SuperAdminService superAdminService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> creds) {
        String username = creds.get("username");
        String password = creds.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Usuario y contraseña requeridos"));
        }

        AppUser user = appUserRepository.findByUsername(username.trim())
                .orElseGet(() -> appUserRepository.findByUsername(username.trim().toLowerCase()).orElse(null));

        boolean pwdMatches = user != null && passwordEncoder.matches(password, user.getPasswordHash());

        if (user == null || !pwdMatches) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
        }

        List<Long> sedeIds = user.getSedesAutorizadas().stream()
                .map(Sede::getId)
                .collect(Collectors.toList());

        Long clubId = (user.getRole() == AppUser.Role.ADMIN) ? user.getId() : user.getClubId();

        AppUser.ClubEstado clubEstado = AppUser.ClubEstado.ACTIVO;
        AppUser.PlanActual planActual = AppUser.PlanActual.TRAMO_1;
        long deportistasActivos = 0;
        Integer maxPermitido = 20; // Default TRAMO_1

        if (user.getRole() == AppUser.Role.ADMIN) {
             clubEstado = user.getClubEstado();
             planActual = user.getPlanActual() != null ? user.getPlanActual() : AppUser.PlanActual.TRAMO_1;
             // Conteo por club_id directo, no por sedesAutorizadas: correcto sin importar cómo
             // se creó cada sede (incluidas las auto-creadas por importación de Excel, que no
             // quedan registradas en AppUser.sedesAutorizadas).
             deportistasActivos = studentRepository.countByClubIdAndEstado(user.getId(), com.asistencia.erp.entity.Student.StudentStatus.ACTIVO);
        } else {
             Long resolvedAdminId = user.getClubId();
             AppUser admin = (resolvedAdminId != null) ? appUserRepository.findById(resolvedAdminId).orElse(null) : null;
             if (admin == null && user.getSedesAutorizadas() != null && !user.getSedesAutorizadas().isEmpty()) {
                 List<Long> empSedes = user.getSedesAutorizadas().stream().map(Sede::getId).toList();
                 admin = appUserRepository.findAllByRoleOrderByUsernameAsc(AppUser.Role.ADMIN).stream()
                         .filter(a -> a.getSedesAutorizadas() != null && a.getSedesAutorizadas().stream().anyMatch(s -> empSedes.contains(s.getId())))
                         .findFirst().orElse(null);
             }
             if (admin != null) {
                 clubId = admin.getId();
                 clubEstado = admin.getClubEstado();
                 planActual = admin.getPlanActual() != null ? admin.getPlanActual() : AppUser.PlanActual.TRAMO_1;
                 deportistasActivos = studentRepository.countByClubIdAndEstado(admin.getId(), com.asistencia.erp.entity.Student.StudentStatus.ACTIVO);
             }
        }

        maxPermitido = superAdminService.obtenerLimiteDeportistas(planActual);

        String token = jwtUtil.generateToken(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                sedeIds,
                clubId
        );

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("userId", user.getId());
        response.put("role", user.getRole().name());
        response.put("username", user.getUsername());
        response.put("sedesAutorizadas", sedeIds);
        response.put("clubId", clubId);
        response.put("clubEstado", clubEstado != null ? clubEstado.name() : "ACTIVO");
        response.put("planActual", planActual != null ? planActual.name() : "TRAMO_1");
        response.put("maxPermitido", maxPermitido);
        response.put("deportistasActivos", deportistasActivos);
        response.put("puedeRecaudar", user.getRole() == AppUser.Role.ADMIN || Boolean.TRUE.equals(user.getPuedeRecaudar()));

        return ResponseEntity.ok(response);
    }
}
