package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.repository.SedeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sedes")
@RequiredArgsConstructor
public class SedeController {

    private final SedeRepository sedeRepository;

    @GetMapping
    public List<Sede> listarSedes() {
        return sedeRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> crearSede(@RequestBody Sede sede) {
        if (sede.getNombre() == null || sede.getNombre().isBlank()) {
            return ResponseEntity.badRequest().body("El nombre de la sede es obligatorio");
        }
        Sede saved = sedeRepository.save(sede);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarSede(@PathVariable Long id, @RequestBody Sede sede) {
        return sedeRepository.findById(id)
                .map(existing -> {
                    existing.setNombre(sede.getNombre());
                    return ResponseEntity.ok(sedeRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarSede(@PathVariable Long id) {
        if (sedeRepository.existsById(id)) {
            sedeRepository.deleteById(id);
            return ResponseEntity.ok("Sede eliminada");
        }
        return ResponseEntity.notFound().build();
    }
}
