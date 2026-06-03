package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.SedeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/empleados")
@RequiredArgsConstructor
public class EmpleadoController {

    private final AppUserRepository appUserRepository;
    private final SedeRepository sedeRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public List<AppUser> listarEmpleados() {
        return appUserRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> crearEmpleado(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String role = (String) body.get("role");
        @SuppressWarnings("unchecked")
        List<Integer> sedeIds = (List<Integer>) body.get("sedeIds");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body("Usuario y contraseña requeridos");
        }

        if (appUserRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body("El nombre de usuario ya existe");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role != null && role.equals("ADMIN") ? AppUser.Role.ADMIN : AppUser.Role.EMPLEADO);

        if (sedeIds != null && !sedeIds.isEmpty()) {
            Set<Sede> sedes = new HashSet<>(sedeRepository.findAllById(sedeIds.stream().map(Long::valueOf).toList()));
            user.setSedesAutorizadas(sedes);
        }

        return ResponseEntity.ok(appUserRepository.save(user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarEmpleado(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return appUserRepository.findById(id)
                .map(user -> {
                    String password = (String) body.get("password");
                    if (password != null && !password.isBlank()) {
                        user.setPasswordHash(passwordEncoder.encode(password));
                    }

                    String role = (String) body.get("role");
                    if (role != null) {
                        user.setRole(role.equals("ADMIN") ? AppUser.Role.ADMIN : AppUser.Role.EMPLEADO);
                    }

                    @SuppressWarnings("unchecked")
                    List<Integer> sedeIds = (List<Integer>) body.get("sedeIds");
                    if (sedeIds != null) {
                        Set<Sede> sedes = new HashSet<>(sedeRepository.findAllById(sedeIds.stream().map(Long::valueOf).toList()));
                        user.setSedesAutorizadas(sedes);
                    }

                    return ResponseEntity.ok(appUserRepository.save(user));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarEmpleado(@PathVariable Long id) {
        if (appUserRepository.existsById(id)) {
            appUserRepository.deleteById(id);
            return ResponseEntity.ok("Empleado eliminado");
        }
        return ResponseEntity.notFound().build();
    }
}
