package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/registro")
@RequiredArgsConstructor
public class RegistroController {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;

    @PostMapping("/padre")
    public String registrarPadre(@RequestParam String nombre, @RequestParam String apellido, @RequestParam String telefono) {
        Parent padre = new Parent();
        padre.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        padre.setTelefono(telefono);
        padre.setSaldoAbono(BigDecimal.ZERO);
        parentRepository.save(padre);
        return "Padre registrado con éxito";
    }

    @PostMapping("/deportista")
    public String registrarDeportista(
            @RequestParam Long parentId,
            @RequestParam String nombre,
            @RequestParam String apellido,
            @RequestParam Integer edad,
            @RequestParam String fechaNacimiento,
            @RequestParam String nivel) { // <-- NUEVO

        Parent padre = parentRepository.findById(parentId).orElseThrow();
        Student deportista = new Student();
        deportista.setParent(padre);
        deportista.setNombreCompleto(nombre.trim() + " " + apellido.trim());
        deportista.setEdad(edad);
        deportista.setFechaNacimiento(java.time.LocalDate.parse(fechaNacimiento));
        deportista.setNivel(nivel);
        studentRepository.save(deportista);
        return "¡Deportista registrado con éxito!";
    }

    @PutMapping("/deportista/{id}")
    public String actualizarDeportista(
            @PathVariable Long id,
            @RequestParam String nombreCompleto,
            @RequestParam Integer edad,
            @RequestParam String fechaNacimiento,
            @RequestParam String estado,
            @RequestParam String nivel) {

        Student deportista = studentRepository.findById(id).orElseThrow();
        deportista.setNombreCompleto(nombreCompleto.trim());
        deportista.setEdad(edad);
        deportista.setFechaNacimiento(java.time.LocalDate.parse(fechaNacimiento));
        deportista.setEstado(Student.StudentStatus.valueOf(estado));
        deportista.setNivel(nivel); // <-- NUEVO
        studentRepository.save(deportista);
        return "Deportista actualizado";
    }

    @PutMapping("/padre/{id}")
    public String actualizarPadre(
            @PathVariable Long id,
            @RequestParam String nombreCompleto,
            @RequestParam String telefono,
            @RequestParam String estado) {
        Parent padre = parentRepository.findById(id).orElseThrow();
        padre.setNombreCompleto(nombreCompleto.trim());
        padre.setTelefono(telefono.trim());
        padre.setEstado(estado);
        parentRepository.save(padre);
        return "Padre actualizado";
    }

    //*************************************
    // URL para eliminar un Padre completo (y sus hijos)
    //*************************************
    @DeleteMapping("/padre/{id}")
    public org.springframework.http.ResponseEntity<?> eliminarPadre(@PathVariable Long id) {
        try {
            // Nota: Dependiendo de cómo tengas tu base de datos,
            // esto borrará al padre y a sus hijos automáticamente.
            parentRepository.deleteById(id);
            return org.springframework.http.ResponseEntity.ok("Padre eliminado correctamente");
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body("Error al eliminar el padre. Verifica que no tenga asistencias o pagos amarrados.");
        }
    }

    //*************************************
    // URL para eliminar solo un Deportista
    //*************************************
    @DeleteMapping("/deportista/{id}")
    public org.springframework.http.ResponseEntity<?> eliminarDeportista(@PathVariable Long id) {
        try {
            studentRepository.deleteById(id);
            return org.springframework.http.ResponseEntity.ok("Deportista eliminado correctamente");
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body("Error al eliminar. Verifica que no tenga historial de asistencias.");
        }
    }
}