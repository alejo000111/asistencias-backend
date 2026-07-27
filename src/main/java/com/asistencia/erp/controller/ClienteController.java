package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.asistencia.erp.security.SecurityUtils.*;

@RestController
@RequestMapping("/api/clientes")
@RequiredArgsConstructor
public class ClienteController {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final com.asistencia.erp.service.ExcelImportService excelImportService;

    @GetMapping
    public List<Parent> listarClientes() {
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // Consulta JPQL optimizada con JOIN, filtra a nivel BD
            return parentRepository.findParentsBySedes(sedes);
        }
        return parentRepository.findAll();
    }

    @GetMapping("/estudiantes")
    public List<Student> listarEstudiantes(
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String nivel) {
        // Filtra a nivel de base de datos con sedeId y nivel opcionales
        List<Student> resultados = studentRepository.filtrarEstudiantes(sedeId, nivel);

        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            return resultados.stream()
                    .filter(s -> estudianteEnSede(s, sedes))
                    .collect(Collectors.toList());
        }
        return resultados;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerCliente(@PathVariable Long id) {
        return parentRepository.findById(id)
                .map(parent -> {
                    if (isEmpleado()) {
                        List<Long> sedes = getSedesAutorizadas();
                        boolean tieneAcceso = parent.getStudents() != null &&
                                parent.getStudents().stream().anyMatch(s -> estudianteEnSede(s, sedes));
                        if (!tieneAcceso) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    return ResponseEntity.ok(parent);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/importar-excel", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importarExcel(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            java.util.Map<String, Object> resultado = excelImportService.importarExcel(file);
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/deshacer-importacion/{batchId}")
    public ResponseEntity<?> deshacerImportacion(@PathVariable String batchId) {
        try {
            java.util.Map<String, Object> resultado = excelImportService.deshacerImportacion(batchId);
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/exportar-excel")
    public ResponseEntity<byte[]> exportarClientesExcel() {
        byte[] excelBytes = excelImportService.exportarClientesExcel();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "clientes_deportistas.xlsx");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
        return new ResponseEntity<>(excelBytes, headers, org.springframework.http.HttpStatus.OK);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/plantilla-excel")
    public ResponseEntity<byte[]> descargarPlantillaExcel() {
        byte[] excelBytes = excelImportService.generarPlantillaExcel();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "plantilla_deportistas.xlsx");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
        return new ResponseEntity<>(excelBytes, headers, org.springframework.http.HttpStatus.OK);
    }
}
