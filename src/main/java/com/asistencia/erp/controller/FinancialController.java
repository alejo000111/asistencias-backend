package com.asistencia.erp.controller;

import com.asistencia.erp.dto.AttendanceDTO;
import com.asistencia.erp.dto.ConvertirCortesiaRequest;
import com.asistencia.erp.dto.CortesiaRequest;
import com.asistencia.erp.dto.FinancialLogDTO;
import com.asistencia.erp.dto.ParentSummaryDTO;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import com.asistencia.erp.service.AsistenciaExportService;
import com.asistencia.erp.service.CortesiaService;
import com.asistencia.erp.service.FinancialService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static com.asistencia.erp.security.SecurityUtils.*;

@RestController
@RequestMapping("/api/finanzas")
@RequiredArgsConstructor
public class FinancialController {

    private final FinancialService financialService;
    private final ParentRepository parentRepository;
    private final FinancialLogRepository financialLogRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final CortesiaService cortesiaService;
    private final AsistenciaExportService asistenciaExportService;
    private final AppUserRepository appUserRepository;
    private final com.asistencia.erp.repository.ClubConfigRepository clubConfigRepository;
    private final com.asistencia.erp.service.billing.MonthlyBillingService monthlyBillingService;

    private ResponseEntity<?> verificarAccesoEmpleadoAPadre(Long parentId) {
        if (isEmpleado()) {
            List<Long> sedes = getSedesAutorizadas();
            if (sedes.isEmpty()) return ResponseEntity.status(403).body("Acceso denegado");

            // PERF-N1-03: UNA consulta SQL (COUNT con JOIN) en lugar de cargar todo el grafo en memoria
            boolean tieneAcceso = parentRepository.existsParentWithAccess(parentId, sedes, getClubId());

            if (!tieneAcceso) {
                return ResponseEntity.status(403).body("Acceso denegado a los datos de este padre");
            }
            return null;
        }

        // ADMIN / SUPERADMIN: el padre debe pertenecer al club del usuario autenticado
        Parent parent = parentRepository.findById(parentId).orElse(null);
        if (parent == null || parent.getClubId() == null || !parent.getClubId().equals(getClubId())) {
            return ResponseEntity.status(403).body("Acceso denegado a los datos de este padre");
        }
        return null;
    }

    //*************************************
    //PUERTA 1: URL para registrar asistencias
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/asistencia
    //*************************************
    @PostMapping("/asistencia")
    public ResponseEntity<?> registrarAsistencia(
            @RequestParam Long studentId,
            @RequestParam String tipoClase,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String fecha,
            @RequestParam(required = false) BigDecimal precioPersonalizado,
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false, defaultValue = "false") Boolean esClaseSuelta) {

        // SEC-BOLA: el deportista siempre debe pertenecer al club del usuario autenticado,
        // sin importar el rol (ADMIN incluido) — antes solo se validaba la sede para EMPLEADO.
        Student studentParaValidar = studentRepository.findById(studentId).orElse(null);
        if (studentParaValidar == null || studentParaValidar.getClubId() == null
                || !studentParaValidar.getClubId().equals(getClubId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Acceso denegado a este deportista");
        }

        // Si es EMPLEADO: validar que la sede esté en sus autorizadas y esté activa
        if (SecurityUtils.isEmpleado()) {
            if (sedeId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Debe especificar una sede autorizada");
            }
            List<Long> sedesAutorizadas = SecurityUtils.getSedesAutorizadas();
            if (!sedesAutorizadas.contains(sedeId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No tiene permisos para registrar asistencias en esta sede");
            }
        }
        // SEC-BOLA: la sede (cuando se especifica) siempre debe pertenecer al club del
        // usuario autenticado, tanto para EMPLEADO como para ADMIN.
        if (sedeId != null) {
            Sede sede = sedeRepository.findById(sedeId).orElse(null);
            if (sede == null || Boolean.FALSE.equals(sede.getActiva()) || sede.getClubId() == null || !sede.getClubId().equals(getClubId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("La sede actual está inactiva o no pertenece al club");
            }
        }

        ResponseEntity<?> errorFecha = validarFechaNoFutura(fecha);
        if (errorFecha != null) return errorFecha;

        com.asistencia.erp.entity.Attendance asistencia = financialService.registrarAsistencia(
                studentId,
                FinancialService.TipoClase.valueOf(tipoClase),
                nivel,
                fecha,
                precioPersonalizado,
                sedeId,
                esClaseSuelta
        );
        java.util.Map<String, Object> respuesta = new java.util.HashMap<>();
        respuesta.put("mensaje", "Asistencia registrada");
        respuesta.put("id", asistencia.getId());
        respuesta.put("fueraDePlan", Boolean.TRUE.equals(asistencia.getFueraDePlan()));
        respuesta.put("motivoFueraDePlan", asistencia.getMotivoFueraDePlan());
        respuesta.put("avisoTarifaNoConfigurada", asistencia.getAvisoTarifaNoConfigurada());
        return ResponseEntity.ok(respuesta);
    }

    //*************************************
    //PUERTA 2: URL para registrar abono de dinero
    //METODO: POST
    //Ruta final: http://localhost:8080/api/finanzas/abono
    //*************************************
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLEADO')")
    @PostMapping("/abono")
    public ResponseEntity<?> registrarAbono(
            @RequestParam Long parentId,
            @RequestParam BigDecimal monto,
            @RequestParam String metodoPago,
            @RequestParam(required = false) String fecha) {

        // FASE 4 — Permisos de Recaudación: un EMPLEADO solo puede registrar abonos
        // si su perfil tiene puedeRecaudar = true. Además debe tener acceso al padre.
        if (SecurityUtils.isEmpleado()) {
            var currentPrincipal = SecurityUtils.getCurrentUser();
            AppUser empleado = currentPrincipal != null ? appUserRepository.findById(currentPrincipal.getUserId()).orElse(null) : null;
            if (empleado == null || !Boolean.TRUE.equals(empleado.getPuedeRecaudar())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("No tiene permiso para recaudar pagos");
            }
        }
        // SEC-BOLA: verificar acceso al padre para EMPLEADO y también para ADMIN
        // (antes solo se validaba dentro del bloque de EMPLEADO, dejando a cualquier
        // ADMIN abonar saldo a un padre de otro club adivinando su id).
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        // El esquema PAQUETE se contabiliza EXCLUSIVAMENTE en clases (nunca en saldoAbono) —
        // antes solo el frontend ocultaba el botón "Abono" para este esquema (guardia débil,
        // saltable llamando la API directo). Ahora también se rechaza en el backend, para que
        // "respetar las reglas de cada esquema" no dependa de la UI.
        com.asistencia.erp.entity.ClubConfig configAbono = clubConfigRepository.findByAdminId(getClubId()).orElse(null);
        if (configAbono != null && configAbono.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.PAQUETE) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error",
                "Este club usa el esquema PAQUETE: no se registran abonos de dinero, solo compras de paquetes de clases."));
        }

        java.time.LocalDate fechaAbono = (fecha != null && !fecha.isEmpty())
                ? java.time.LocalDate.parse(fecha)
                : java.time.LocalDate.now();

        Long abonoId = financialService.registrarAbono(parentId, monto, FinancialLog.PaymentMethod.valueOf(metodoPago), fechaAbono);
        return ResponseEntity.ok(java.util.Map.of("mensaje", "Abono registrado con éxito", "abonoId", abonoId));
    }

    //*************************************
    // Precio vigente del Plan de mensualidad de la sede PRINCIPAL de un deportista — usa la
    // MISMA lógica de calendario (preferencial/estándar/mora) que ya calcula el cobro real, para
    // que un botón de "Pagar Plan" nunca muestre un monto distinto al que el sistema
    // realmente le cobraría hoy.
    //*************************************
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLEADO')")
    @GetMapping("/deportista/{studentId}/precio-plan")
    public ResponseEntity<?> obtenerPrecioPlanVigente(@PathVariable Long studentId) {
        Student student = studentRepository.findById(studentId).orElse(null);
        Long clubActual = SecurityUtils.getClubId();
        if (student == null || student.getClubId() == null || !student.getClubId().equals(clubActual)) {
            return ResponseEntity.status(403).body("Acceso denegado a este deportista");
        }

        com.asistencia.erp.entity.Enrollment principal = (student.getMatriculas() == null
                ? List.<com.asistencia.erp.entity.Enrollment>of() : student.getMatriculas()).stream()
                .filter(m -> Boolean.TRUE.equals(m.getEsPrincipal()))
                .findFirst().orElse(null);
        if (principal == null) {
            return ResponseEntity.badRequest().body("Este deportista no tiene una sede principal asignada.");
        }

        com.asistencia.erp.entity.ClubConfig config = clubConfigRepository.findByAdminId(clubActual).orElse(null);
        if (config == null) {
            return ResponseEntity.badRequest().body("Este club no tiene configuración de cobro.");
        }

        BigDecimal monto = monthlyBillingService.calculateMonthlyPriceForEnrollment(principal, config, java.time.LocalDateTime.now());
        return ResponseEntity.ok(java.util.Map.of(
                "studentId", student.getId(),
                "nombreDeportista", student.getNombreCompleto(),
                "sede", principal.getSede() != null ? principal.getSede().getNombre() : "",
                "plan", principal.getPlanMensualidad() != null ? principal.getPlanMensualidad().getNombre() : "Tarifa general",
                "monto", monto
        ));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/compra-paquete")
    public ResponseEntity<?> registrarCompraPaquete(@Valid @RequestBody com.asistencia.erp.dto.CompraPaqueteRequest request) {
        // SEC-BOLA: el padre de la compra debe pertenecer al club del ADMIN autenticado.
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(request.getParentId());
        if (permiso != null) return permiso;

        // Comprar "paquetes de clases" solo tiene sentido si el club usa el esquema PAQUETE —
        // en MENSUALIDAD/POR_CLASE, Student.clasesDisponibles nunca se lee, así que aceptar esta
        // compra dejaría el dinero cobrado sin ningún efecto real ("dinero volando").
        com.asistencia.erp.entity.ClubConfig configPaquete = clubConfigRepository.findByAdminId(getClubId()).orElse(null);
        if (configPaquete == null || configPaquete.getEsquemaCobro() != com.asistencia.erp.entity.ClubConfig.EsquemaCobro.PAQUETE) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error",
                "Este club no usa el esquema PAQUETE — cambia el esquema de cobro en Ajustes antes de vender paquetes de clases."));
        }

        financialService.registrarCompraPaquete(request);
        return ResponseEntity.ok("Compra de paquete registrada con éxito");
    }

    @GetMapping("/historial")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<?> obtenerHistorialCaja() {
        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<FinancialLogDTO> dtos = financialLogRepository.findByClubId(getClubId()).stream()
                .map(FinancialLogDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/historial/{parentId}")
    public ResponseEntity<?> obtenerHistorialPorPadre(@PathVariable Long parentId) {
        // EMPLEADO: validar que el padre pertenezca a sus sedes autorizadas
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<FinancialLogDTO> historialPadre = financialLogRepository
                .findByParentIdOrderByFechaDesc(parentId)
                .stream()
                .limit(10)
                .map(FinancialLogDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(historialPadre);
    }

    //*************************************
    //PUERTA 3: URL para ver todos los padres y sus saldos
    //METODO: GET
    //Ruta final: http://localhost:8080/api/finanzas/padres
    @GetMapping("/padres")
    public List<ParentSummaryDTO> obtenerPadres() {
        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        // SEC: filtrar siempre por club_id del usuario autenticado (aislamiento multi-tenant)
        List<Parent> todos = parentRepository.findByClubId(getClubId());
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            return todos.stream()
                    .filter(p -> parentRepository.existsParentWithAccess(p.getId(), sedes, getClubId()))
                    .map(ParentSummaryDTO::fromEntity)
                    .collect(Collectors.toList());
        }
        return todos.stream()
                .map(ParentSummaryDTO::fromEntity)
                .collect(Collectors.toList());
    }

    //*************************************
    //PUERTA 4: URL para ver el historial completo de asistencias
    //METODO: GET
    //*************************************
    @Transactional(readOnly = true)
    @GetMapping("/historial-asistencias")
    public ResponseEntity<?> obtenerTodasLasAsistencias() {
        List<Attendance> asistencias;
        if (SecurityUtils.isEmpleado()) {
            List<Long> sedes = SecurityUtils.getSedesAutorizadas();
            if (sedes.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            asistencias = attendanceRepository.findBySedeIdIn(sedes, getClubId());
        } else {
            asistencias = attendanceRepository.findAllWithFetch(getClubId());
        }
        List<AttendanceDTO> dtos = asistencias.stream()
                .map(AttendanceDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    //*************************************
    //PUERTA 5: URL para eliminar una asistencia
    //METODO: DELETE
    //*************************************
    @DeleteMapping("/asistencia/{id}")
    public ResponseEntity<?> eliminarAsistencia(@PathVariable Long id) {
        // Verificar que la asistencia existe y pertenece al club del usuario autenticado (SEC-BOLA-01)
        Attendance asistencia = attendanceRepository.findById(id).orElse(null);
        if (asistencia == null) {
            return ResponseEntity.notFound().build();
        }
        if (asistencia.getClubId() == null || !asistencia.getClubId().equals(getClubId())) {
            return ResponseEntity.status(403).body("Acceso denegado a esta asistencia");
        }
        // FASE 4 — Deshacer asistencia: un EMPLEADO solo puede eliminar (deshacer)
        // las asistencias que él mismo registró. El ADMIN puede eliminar cualquiera.
        if (SecurityUtils.isEmpleado()) {
            var currentUser = SecurityUtils.getCurrentUser();
            boolean esQuienLaRegistro = currentUser != null && currentUser.getUserId().equals(asistencia.getRegistradoPorId());
            if (!esQuienLaRegistro) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Solo puedes deshacer las asistencias que tú mismo registraste");
            }
        }
        // Nómina: al eliminarse la asistencia, deja de contarse automáticamente en el
        // cálculo de NominaService (que consulta en vivo la tabla attendances), sin
        // necesidad de un paso adicional de sincronización.
        attendanceRepository.deleteById(id);
        return ResponseEntity.ok("Asistencia eliminada");
    }

    //*************************************
    //PUERTA 6: URL para ver las deudas de un papá específico
    //METODO: GET
    //*************************************
    @GetMapping("/deudas/{parentId}")
    public ResponseEntity<?> obtenerDeudasPadre(@PathVariable Long parentId) {
        // EMPLEADO: validar que el padre pertenezca a sus sedes autorizadas
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        Parent parent = parentRepository.findById(parentId).orElse(null);
        com.asistencia.erp.entity.ClubConfig config = parent != null ? financialService.resolverConfigParaPadre(parent) : null;

        List<Attendance> deudasRaw;
        // Sede + Plan + tarifa vigente (Preferencial/Actual/Mora) de cada deuda de MENSUALIDAD,
        // para que el admin/padre vea exactamente qué está pagando y bajo qué tarifa — antes solo
        // se veía el nombre del deportista y el monto, sin contexto de sede/plan.
        java.util.Map<Long, String[]> planYTarifaPorAttendance = new java.util.HashMap<>();
        if (config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.PAQUETE) {
            deudasRaw = List.of();
        } else {
            // Filtrar $0: una clase con precioCobrado=0 nunca es una deuda real (mensualidad ya
            // cobrada ese mes, o datos históricos con clase_paga=false por un bug ya corregido) —
            // mostrarla como "deuda pendiente $0" solo confunde al admin.
            deudasRaw = attendanceRepository.findUnpaidByParentIdOrderByFechaDesc(parentId).stream()
                .filter(a -> a.getPrecioCobrado() != null && a.getPrecioCobrado().compareTo(java.math.BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
            if (config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.MENSUALIDAD) {
                String tarifaVigente = monthlyBillingService.resolveTariffLabel(config);
                for (Attendance a : deudasRaw) {
                    if (a.getPrecioCobrado().compareTo(java.math.BigDecimal.ZERO) > 0) {
                        java.math.BigDecimal precioHoy = financialService.calcularPrecioMensualidadDeudaVigente(a.getStudent(), config, a);
                        a.setPrecioCobrado(precioHoy);

                        Long sedeId = a.getSede() != null ? a.getSede().getId() : null;
                        String nombrePlan = "Tarifa general";
                        if (a.getStudent() != null && a.getStudent().getMatriculas() != null && sedeId != null) {
                            var enrollment = a.getStudent().getMatriculas().stream()
                                .filter(m -> m.getSede() != null && m.getSede().getId().equals(sedeId))
                                .findFirst().orElse(null);
                            if (enrollment != null && enrollment.getPlanMensualidad() != null) {
                                nombrePlan = enrollment.getPlanMensualidad().getNombre();
                            }
                        }
                        planYTarifaPorAttendance.put(a.getId(), new String[]{nombrePlan, tarifaVigente});
                    }
                }
            }
        }

        // PERF-JACKSON-01: Usar DTO en lugar de exponer la entidad JPA directamente
        List<AttendanceDTO> deudas = deudasRaw.stream()
                .map(AttendanceDTO::fromEntity)
                .peek(dto -> {
                    String[] planYTarifa = planYTarifaPorAttendance.get(dto.getId());
                    if (planYTarifa != null) {
                        dto.setPlanNombre(planYTarifa[0]);
                        dto.setTarifaLabel(planYTarifa[1]);
                    }
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(deudas);
    }

    //*************************************
    //PUERTA 7: URL para eliminar un abono/ingreso equivocado
    //METODO: DELETE
    //Regla: Resta el monto del saldoAbono del Parent y re-ejecuta FIFO
    //*************************************
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/abono/{id}")
    public ResponseEntity<?> eliminarAbono(@PathVariable Long id) {
        try {
            financialService.eliminarAbono(id);
            return ResponseEntity.ok("Abono eliminado correctamente");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    //*************************************
    //PUERTA 8: Obtener cargos extras pendientes (matrícula, seguro) de un padre
    //METODO: GET
    //Retorna los CARGO_EXTRA registrados en financial_logs para mostrar deuda pendiente
    //*************************************
    @GetMapping("/cargos-extras/{parentId}")
    public ResponseEntity<?> obtenerCargosExtras(@PathVariable Long parentId) {
        ResponseEntity<?> permiso = verificarAccesoEmpleadoAPadre(parentId);
        if (permiso != null) return permiso;

        List<FinancialLog> extras = financialService.obtenerCargosExtrasPendientes(parentId).stream()
                .filter(log -> log.getConcepto() == null || !log.getConcepto().toLowerCase().startsWith("paquete"))
                .toList();
        List<java.util.Map<String, Object>> resultado = extras.stream()
            .map(log -> {
                java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", log.getId());
                item.put("concepto", log.getConcepto() != null ? log.getConcepto() : "Cargo Extra");
                item.put("monto", log.getMonto());
                item.put("fecha", log.getFecha() != null ? log.getFecha().toString() : null);
                item.put("tipoMovimiento", log.getTipoMovimiento() != null ? log.getTipoMovimiento().name() : "CARGO_EXTRA");
                return item;
            })
            .collect(Collectors.toList());

        return ResponseEntity.ok(resultado);
    }

    //*************************************
    //PUERTA 9: Marcar un cargo extra como pagado (eliminarlo del pendiente)
    //METODO: DELETE
    //El admin confirma que el cargo extra ya fue cobrado/pagado
    //*************************************
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/cargo-extra/{id}")
    public ResponseEntity<?> marcarCargoExtroPagado(@PathVariable Long id) {
        try {
            FinancialLog log = financialLogRepository.findById(id).orElse(null);
            if (log == null) {
                return ResponseEntity.notFound().build();
            }
            if (log.getClubId() == null || !log.getClubId().equals(getClubId())) {
                return ResponseEntity.status(403).body("Acceso denegado a este cargo extra");
            }
            financialLogRepository.deleteById(id);
            return ResponseEntity.ok("Cargo extra marcado como pagado");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    //*************************************
    //PUERTA: Condonar/eliminar la deuda de una clase individual sin pagar
    //METODO: DELETE
    //No borra el registro histórico de la asistencia, solo la marca como pagada
    //(equivalente a "perdonar" esa deuda), igual que "✓ Pagado" hace con CARGO_EXTRA.
    //*************************************
    @DeleteMapping("/deuda-clase/{id}")
    public ResponseEntity<?> condonarDeudaClase(@PathVariable Long id) {
        try {
            Attendance asistencia = attendanceRepository.findById(id).orElse(null);
            if (asistencia == null) {
                return ResponseEntity.notFound().build();
            }
            if (asistencia.getClubId() == null || !asistencia.getClubId().equals(getClubId())) {
                return ResponseEntity.status(403).body("Acceso denegado a esta asistencia");
            }
            asistencia.setClasePaga(true);
            attendanceRepository.save(asistencia);
            return ResponseEntity.ok("Deuda de la clase condonada");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    // Endpoint unificado en RegistroController.eliminarDeportista() vía FinancialService

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 3 — CLASES DE CORTESÍA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/finanzas/cortesia
     * Registra una clase de cortesía para un visitante/prospecto sin matrícula.
     * Permitido para ADMIN y EMPLEADO (operativa de campo).
     */
    @PostMapping("/cortesia")
    public ResponseEntity<?> registrarCortesia(@RequestBody CortesiaRequest req) {
        // Validar que la sede esté autorizada si es EMPLEADO
        if (SecurityUtils.isEmpleado()) {
            if (req.getSedeId() == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Debe especificar una sede autorizada");
            }
            List<Long> sedesAutorizadas = SecurityUtils.getSedesAutorizadas();
            if (!sedesAutorizadas.contains(req.getSedeId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("No tiene permisos para esta sede");
            }
        }
        // SEC-BOLA: la sede (si se especifica) siempre debe pertenecer al club del usuario
        // autenticado — antes esto solo se validaba para EMPLEADO, dejando a un ADMIN asociar
        // una cortesía a una sede de otro club.
        if (req.getSedeId() != null) {
            Sede sede = sedeRepository.findById(req.getSedeId()).orElse(null);
            if (sede == null || sede.getClubId() == null || !sede.getClubId().equals(getClubId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("La sede no pertenece a este club");
            }
        }
        ResponseEntity<?> errorFecha = validarFechaNoFutura(req.getFecha());
        if (errorFecha != null) return errorFecha;
        try {
            Attendance cortesia = cortesiaService.registrarCortesia(req);
            return ResponseEntity.ok(AttendanceDTO.fromEntity(cortesia));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error al registrar cortesía: " + e.getMessage());
        }
    }

    /**
     * POST /api/finanzas/cortesia/{id}/convertir
     * Convierte una clase de cortesía en un deportista matriculado (1 clic).
     * Solo ADMIN puede crear registros permanentes de clientes.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/cortesia/{id}/convertir")
    public ResponseEntity<?> convertirCortesia(
            @PathVariable Long id,
            @RequestBody ConvertirCortesiaRequest req) {
        req.setCortesiaId(id);
        try {
            java.util.Map<String, Object> resultado = cortesiaService.convertirCortesia(req);
            return ResponseEntity.ok(resultado);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error al convertir cortesía: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 3 — EXPORTACIÓN DE PLANILLAS EN EXCEL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/finanzas/exportar-asistencias
     * Exporta las asistencias como archivo .xlsx con filtros opcionales.
     * Solo ADMIN. (Bloqueado por SuspensionFilter en estado de mora.)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/exportar-asistencias")
    public ResponseEntity<byte[]> exportarAsistenciasExcel(
            @RequestParam(required = false) Long sedeId,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String fechaDesde,
            @RequestParam(required = false) String fechaHasta) {
        try {
            AsistenciaExportService.ExportResult resultado = asistenciaExportService.exportarAsistencias(
                    getClubId(), sedeId, nivel, fechaDesde, fechaHasta
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(resultado.getContentType()));
            headers.setContentDispositionFormData("attachment", resultado.getFilename());
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
            return new ResponseEntity<>(resultado.getData(), headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** No se permiten "asistencias futuras": la fecha (si viene) debe ser hoy o anterior. Null/vacío = hoy, siempre válido. */
    private ResponseEntity<?> validarFechaNoFutura(String fecha) {
        if (fecha == null || fecha.isBlank()) return null;
        try {
            LocalDate parsed = LocalDate.parse(fecha.length() >= 10 ? fecha.substring(0, 10) : fecha);
            if (parsed.isAfter(LocalDate.now())) {
                return ResponseEntity.badRequest().body("No se puede registrar una asistencia con fecha futura. Usa la fecha de hoy o una anterior.");
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body("Formato de fecha inválido.");
        }
        return null;
    }
}
