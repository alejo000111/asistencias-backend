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
    private final com.asistencia.erp.service.FinancialService financialService;

    @GetMapping
    public List<Parent> listarClientes() {
        Long clubId = SecurityUtils.getClubId();
        List<Parent> parents;
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return List.of();
            // Consulta JPQL optimizada con JOIN, filtra a nivel BD
            parents = parentRepository.findParentsBySedes(sedes, clubId);
        } else {
            parents = parentRepository.findByClubId(clubId);
        }
        ajustarPreciosMensualidadesPendientes(parents);
        return parents;
    }

    @GetMapping("/estudiantes")
    public List<Student> listarEstudiantes(
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String nivel) {
        Long clubId = SecurityUtils.getClubId();
        // Filtra a nivel de base de datos con sedeId y nivel opcionales y clubId
        List<Student> resultados = studentRepository.filtrarEstudiantes(sedeId, nivel, clubId);

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
        Long clubId = SecurityUtils.getClubId();
        return parentRepository.findById(id)
                .map(parent -> {
                    if (!clubId.equals(parent.getClubId())) {
                        return ResponseEntity.status(403).body("Acceso denegado a este cliente");
                    }
                    if (isEmpleado()) {
                        List<Long> sedes = getSedesAutorizadas();
                        boolean tieneAcceso = parent.getStudents() != null &&
                                parent.getStudents().stream().anyMatch(s -> estudianteEnSede(s, sedes));
                        if (!tieneAcceso) {
                            return ResponseEntity.status(403).body("Acceso denegado a esta sede");
                        }
                    }
                    ajustarPreciosMensualidadesPendientes(java.util.Collections.singletonList(parent));
                    return ResponseEntity.ok(parent);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void ajustarPreciosMensualidadesPendientes(List<Parent> parents) {
        for (Parent p : parents) {
            com.asistencia.erp.entity.ClubConfig config = financialService.resolverConfigParaPadre(p);
            if (config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.MENSUALIDAD) {
                if (p.getStudents() != null) {
                    for (Student s : p.getStudents()) {
                        if (s.getAttendances() != null) {
                            for (com.asistencia.erp.entity.Attendance a : s.getAttendances()) {
                                if (!Boolean.TRUE.equals(a.getClasePaga()) && a.getPrecioCobrado().compareTo(java.math.BigDecimal.ZERO) > 0) {
                                    java.math.BigDecimal precioHoy = financialService.calcularPrecioMensualidadDeudaVigente(s, config, a);
                                    a.setPrecioCobrado(precioHoy);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // SEC: importar (escritura masiva) es exclusivo de ADMIN — a diferencia de plantilla/exportar
    // (solo lectura), un EMPLEADO nunca debe poder crear/editar deportistas en lote.
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/importar-excel", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importarExcel(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            java.util.Map<String, Object> resultado = excelImportService.importarExcel(file);
            return ResponseEntity.ok(resultado);
        } catch (com.asistencia.erp.service.ExcelImportService.ImportValidationException ive) {
            // No se guardó nada: se listan TODOS los errores encontrados para que el admin los
            // corrija de una sola vez y vuelva a subir el archivo.
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "No se importó nada. Corrige estos errores en el Excel y vuelve a subirlo:",
                    "errores", ive.getErrores()
            ));
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("rollback-only")) {
                errorMsg = "Error al procesar la importación. Por favor verifica las columnas del archivo Excel (fechas, teléfonos o nombres de deportista).";
            } else if (errorMsg == null || errorMsg.isBlank()) {
                errorMsg = "Ocurrió un fallo desconocido al procesar el archivo Excel.";
            }
            return ResponseEntity.badRequest().body(java.util.Map.of("error", errorMsg));
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = {"/deshacer-importacion", "/deshacer-importacion/{batchId}"})
    public ResponseEntity<?> deshacerImportacion(@PathVariable(required = false) String batchId) {
        try {
            String targetBatchId = (batchId != null && !batchId.isBlank()) ? batchId : "ultimo";
            java.util.Map<String, Object> resultado = excelImportService.deshacerImportacion(targetBatchId);
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // Un EMPLEADO (entrenador) también puede exportar, pero el servicio filtra el contenido
    // a únicamente sus sedes autorizadas (SecurityUtils.isEmpleado() + getSedesAutorizadas()).
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    @GetMapping("/exportar-excel")
    public ResponseEntity<byte[]> exportarClientesExcel() {
        byte[] excelBytes = excelImportService.exportarClientesExcel();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "deportistas.xlsx");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
        return new ResponseEntity<>(excelBytes, headers, org.springframework.http.HttpStatus.OK);
    }

    // Igual que exportar: un EMPLEADO puede descargar la plantilla, pero solo trae sus sedes autorizadas.
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
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
