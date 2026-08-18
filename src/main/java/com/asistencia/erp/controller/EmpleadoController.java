package com.asistencia.erp.controller;

import com.asistencia.erp.dto.EmpleadoDTO;
import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.SedeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/empleados")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EmpleadoController {

    private final AppUserRepository appUserRepository;
    private final SedeRepository sedeRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public List<EmpleadoDTO> listarEmpleados() {
        var currentPrincipal = com.asistencia.erp.security.SecurityUtils.getCurrentUser();
        if (currentPrincipal == null) return List.of();

        AppUser currentAdmin = appUserRepository.findById(currentPrincipal.getUserId()).orElse(null);
        if (currentAdmin == null) return List.of();

        // SEC: aislamiento estricto por club_id (antes se comparaba por nombre de club
        // y por solapamiento de sedesAutorizadas, lo cual filtraba erróneamente
        // admins/empleados de OTROS clubes que compartían texto de club o alguna sede).
        Long clubActual = com.asistencia.erp.security.SecurityUtils.getClubId();

        List<AppUser> delClub = appUserRepository.findAll()
                .stream()
                .filter(u -> u.getRole() != AppUser.Role.SUPERADMIN)
                .filter(u -> clubActual != null && clubActual.equals(clubEfectivoDe(u)))
                .collect(Collectors.toList());

        // Garantiza que el ADMIN autenticado siempre se vea a sí mismo en la lista
        // (para poder cambiar su propia contraseña/nombre desde esta pantalla).
        boolean incluyeAlAdmin = delClub.stream().anyMatch(u -> u.getId().equals(currentAdmin.getId()));
        if (!incluyeAlAdmin) {
            delClub.add(0, currentAdmin);
        }

        return delClub.stream()
                .map(u -> EmpleadoDTO.fromEntity(u, u.getId().equals(currentAdmin.getId())))
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<?> crearEmpleado(@RequestBody Map<String, Object> body) {
        var currentPrincipal = com.asistencia.erp.security.SecurityUtils.getCurrentUser();
        AppUser currentAdmin = currentPrincipal != null ? appUserRepository.findById(currentPrincipal.getUserId()).orElse(null) : null;

        String username = (String) body.get("username");
        String nombreCompleto = (String) body.get("nombreCompleto");
        String password = (String) body.get("password");
        String role = (String) body.get("role");
        Object tarifaObj = body.get("tarifaPorClase");
        String tipoTarifa = (String) body.get("tipoTarifa");
        Object puedeRecaudarObj = body.get("puedeRecaudar");
        Object exentoNominaObj = body.get("exentoNomina");
        @SuppressWarnings("unchecked")
        List<Integer> sedeIds = (List<Integer>) body.get("sedeIds");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Usuario y contraseña requeridos"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "La contraseña debe tener al menos 6 caracteres."));
        }

        String lowerUser = username.trim().toLowerCase();
        Set<String> reservados = Set.of("superadmin", "root", "admin", "system", "null", "undefined");
        if (reservados.contains(lowerUser)) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre de usuario '" + username + "' es una palabra reservada del sistema."));
        }

        if (appUserRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre de usuario ya existe"));
        }

        AppUser user = new AppUser();
        user.setUsername(username.trim());
        user.setNombreCompleto(nombreCompleto != null ? nombreCompleto : username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role != null && role.equals("ADMIN") ? AppUser.Role.ADMIN : AppUser.Role.EMPLEADO);

        // Auto-asignación estricta de ClubNombre para evitar valores null / —
        if (currentAdmin != null) {
            String club = currentAdmin.getClubNombre();
            if (club == null || club.isBlank()) {
                club = currentAdmin.getNombreAdministrador();
            }
            if (club == null || club.isBlank()) {
                club = currentAdmin.getUsername();
            }
            user.setClubNombre(club);
            user.setClubId(currentAdmin.getId());
        }
        if (tarifaObj != null && !tarifaObj.toString().isBlank()) {
            user.setTarifaPorClase(new java.math.BigDecimal(tarifaObj.toString()));
        }
        if (tipoTarifa != null && !tipoTarifa.isBlank()) {
            user.setTipoTarifa(tipoTarifa.equals("POR_HORA") ? AppUser.TipoTarifa.POR_HORA : AppUser.TipoTarifa.POR_CLASE);
        }
        if (puedeRecaudarObj != null) {
            user.setPuedeRecaudar(Boolean.parseBoolean(puedeRecaudarObj.toString()));
        }
        if (exentoNominaObj != null) {
            user.setExentoNomina(Boolean.parseBoolean(exentoNominaObj.toString()));
        }

        if (sedeIds != null && !sedeIds.isEmpty()) {
            Set<Sede> sedes = new HashSet<>(sedeRepository.findAllById(sedeIds.stream().map(Long::valueOf).toList()));
            user.setSedesAutorizadas(sedes);
        }

        return ResponseEntity.ok(EmpleadoDTO.fromEntity(appUserRepository.save(user)));
    }

    /**
     * Calcula el club efectivo de un AppUser igual que AuthController al emitir el JWT:
     * un ADMIN es dueño de su propio club (clubId = su propio id); un EMPLEADO hereda el clubId de su admin.
     */
    private Long clubEfectivoDe(AppUser user) {
        return user.getRole() == AppUser.Role.ADMIN ? user.getId() : user.getClubId();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarEmpleado(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return appUserRepository.findById(id)
                .map(user -> {
                    Long clubActual = com.asistencia.erp.security.SecurityUtils.getClubId();
                    if (clubActual == null || !clubActual.equals(clubEfectivoDe(user))) {
                        return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este empleado"));
                    }

                    String nombreCompleto = (String) body.get("nombreCompleto");
                    if (nombreCompleto != null) {
                        user.setNombreCompleto(nombreCompleto);
                    }

                    String password = (String) body.get("password");
                    if (password != null && !password.isBlank()) {
                        if (password.length() < 6) {
                            return ResponseEntity.badRequest().body(Map.of("error", "La contraseña debe tener al menos 6 caracteres."));
                        }
                        user.setPasswordHash(passwordEncoder.encode(password));
                    }

                    String role = (String) body.get("role");
                    if (role != null) {
                        user.setRole(role.equals("ADMIN") ? AppUser.Role.ADMIN : AppUser.Role.EMPLEADO);
                    }

                    Object tarifaObj = body.get("tarifaPorClase");
                    if (tarifaObj != null && !tarifaObj.toString().isBlank()) {
                        user.setTarifaPorClase(new java.math.BigDecimal(tarifaObj.toString()));
                    }

                    String tipoTarifa = (String) body.get("tipoTarifa");
                    if (tipoTarifa != null && !tipoTarifa.isBlank()) {
                        user.setTipoTarifa(tipoTarifa.equals("POR_HORA") ? AppUser.TipoTarifa.POR_HORA : AppUser.TipoTarifa.POR_CLASE);
                    }

                    Object puedeRecaudarObj = body.get("puedeRecaudar");
                    if (puedeRecaudarObj != null) {
                        user.setPuedeRecaudar(Boolean.parseBoolean(puedeRecaudarObj.toString()));
                    }

                    Object exentoNominaObj = body.get("exentoNomina");
                    if (exentoNominaObj != null) {
                        user.setExentoNomina(Boolean.parseBoolean(exentoNominaObj.toString()));
                    }

                    @SuppressWarnings("unchecked")
                    List<Integer> sedeIds = (List<Integer>) body.get("sedeIds");
                    if (sedeIds != null) {
                        Set<Sede> sedes = new HashSet<>(sedeRepository.findAllById(sedeIds.stream().map(Long::valueOf).toList()));
                        user.setSedesAutorizadas(sedes);
                    }

                    return ResponseEntity.ok(EmpleadoDTO.fromEntity(appUserRepository.save(user)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarEmpleado(@PathVariable Long id) {
        AppUser user = appUserRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        Long clubActual = com.asistencia.erp.security.SecurityUtils.getClubId();
        if (clubActual == null || !clubActual.equals(clubEfectivoDe(user))) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este empleado"));
        }
        var currentPrincipal = com.asistencia.erp.security.SecurityUtils.getCurrentUser();
        if (currentPrincipal != null && currentPrincipal.getUserId().equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("error", "No puedes eliminar tu propia cuenta de administrador"));
        }
        appUserRepository.deleteById(id);
        return ResponseEntity.ok("Empleado eliminado");
    }
}
