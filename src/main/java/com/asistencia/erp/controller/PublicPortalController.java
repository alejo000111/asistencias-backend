package com.asistencia.erp.controller;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.GrupoSede;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicPortalController {

    private final ParentRepository parentRepository;
    private final FinancialLogRepository financialLogRepository;
    private final AttendanceRepository attendanceRepository;
    private final SedeRepository sedeRepository;
    private final com.asistencia.erp.repository.ClubConfigRepository clubConfigRepository;
    private final com.asistencia.erp.service.FinancialService financialService;
    private final com.asistencia.erp.service.billing.MonthlyBillingService monthlyBillingService;
    private final com.asistencia.erp.repository.StudentComplementoRepository studentComplementoRepository;
    private final com.asistencia.erp.repository.AppUserRepository appUserRepository;

    @Value("${precios.grupal}")
    private BigDecimal precioGrupal;

    @Value("${precios.personalizada}")
    private BigDecimal precioPersonalizada;

    @GetMapping("/precios")
    public Map<String, BigDecimal> obtenerPrecios() {
        return Map.of(
            "grupal", precioGrupal,
            "personalizada", precioPersonalizada
        );
    }

    @GetMapping("/portal/{secretToken}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> obtenerPortal(@PathVariable String secretToken) {
        Parent parent = parentRepository.findBySecretToken(secretToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token inválido"));

        Long clubId = parent.getClubId();

        // Si el club del padre está suspendido por mora (plazo vencido), el portal no debe
        // mostrar ninguna información financiera — se informa con un mensaje genérico que no
        // expone el motivo interno de la suspensión.
        if (clubId != null) {
            AppUser clubAdmin = appUserRepository.findById(clubId).orElse(null);
            if (clubAdmin != null && clubAdmin.getRole() == AppUser.Role.ADMIN
                    && clubAdmin.getClubEstado() == AppUser.ClubEstado.SUSPENDIDO_POR_MORA) {
                Map<String, Object> body = new HashMap<>();
                body.put("error", "Servicio no disponible");
                body.put("mensaje", "Ocurrió un error en la plataforma, por favor contacte al administrador del club o escuela deportiva.");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
            }
        }
        com.asistencia.erp.entity.ClubConfig config = (clubId != null) 
                ? clubConfigRepository.findByAdminId(clubId).orElse(null)
                : null;

        // Deudas de clases (asistencias no pagadas)
        // En esquema PAQUETE, las clases no representan deudas por clase individual.
        // Se filtran también las de $0: una clase con precioCobrado=0 nunca es una deuda real
        // (ya sea porque ese mes ya se cobró la mensualidad, o por datos históricos con
        // clase_paga=false que quedaron así por un bug ya corregido) — mostrarla como "deuda
        // pendiente $0" solo confunde al padre.
        List<Attendance> deudasClases = (config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.PAQUETE)
                ? List.of()
                : attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parent.getId()).stream()
                    .filter(a -> a.getPrecioCobrado() != null && a.getPrecioCobrado().compareTo(BigDecimal.ZERO) > 0)
                    .toList();

        BigDecimal deudaClasesTotal = BigDecimal.ZERO;
        if (deudasClases != null) {
            for (Attendance att : deudasClases) {
                if (config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.MENSUALIDAD && att.getPrecioCobrado().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal precioHoy = financialService.calcularPrecioMensualidadDeudaVigente(att.getStudent(), config, att);
                    att.setPrecioCobrado(precioHoy);
                }
                deudaClasesTotal = deudaClasesTotal.add(att.getPrecioCobrado());
            }
        }

        // Cargos extras pendientes (matrícula, seguro)
        // Se excluyen paquetes (ya que los paquetes son compras inmediatas / PAGO_DIRECTO)
        List<FinancialLog> cargosExtras = financialLogRepository
                .findByParentIdAndTipoMovimiento(parent.getId(), FinancialLog.MovementType.CARGO_EXTRA)
                .stream()
                .filter(cargo -> cargo.getConcepto() == null || !cargo.getConcepto().toLowerCase().startsWith("paquete"))
                .toList();

        BigDecimal deudaExtrasTotal = BigDecimal.ZERO;
        if (cargosExtras != null) {
            for (FinancialLog cargo : cargosExtras) {
                deudaExtrasTotal = deudaExtrasTotal.add(cargo.getMonto());
            }
        }

        BigDecimal deudaTotal = deudaClasesTotal.add(deudaExtrasTotal);

        // Movimientos financieros (incluye Abonos e Ingresos Directos como Compra de Paquetes)
        List<FinancialLog> logs = financialLogRepository
                .findMovimientosPagosByParentId(parent.getId())
                .stream()
                .limit(10)
                .toList();

        // Últimas clases asistidas (pagas o no). Sin límite para que el fallback de niveles
        // pueda cubrir a todos los estudiantes del padre. El frontend ya hace .slice(0, 3) para mostrar.
        List<Attendance> ultimasClases = attendanceRepository.findRecentByParentId(parent.getId());

        // Construir el DTO del padre explícitamente como Map para:
        // 1) Evitar problemas de lazy loading / serialización de proxies JPA
        // 2) Excluir información sensible de sedes
        // 3) Preservar los niveles activos de cada deportista
        Map<String, Object> parentDto = new HashMap<>();
        parentDto.put("id", parent.getId());
        parentDto.put("nombreCompleto", parent.getNombreCompleto());
        parentDto.put("telefono", parent.getTelefono());
        parentDto.put("estado", parent.getEstado());
        parentDto.put("saldoAbono", parent.getSaldoAbono());
        parentDto.put("secretToken", parent.getSecretToken());
        // Necesario para que el widget de pago Wompi identifique el club correcto — antes el DTO
        // no lo incluía y el frontend caía a un valor por defecto, mezclando pagos entre clubes.
        parentDto.put("clubId", parent.getClubId());

        // Construir un mapa de respaldo: para cada estudiante, extraer el nivel de la
        // asistencia o deuda más reciente (cuando no hay matrículas activas en la BD)
        Map<Long, String> nivelFallbackPorEstudiante = new HashMap<>();
        for (Attendance att : ultimasClases) {
            if (att.getStudent() != null && att.getNivel() != null) {
                nivelFallbackPorEstudiante.putIfAbsent(att.getStudent().getId(), att.getNivel());
            }
        }
        for (Attendance att : deudasClases) {
            if (att.getStudent() != null && att.getNivel() != null) {
                nivelFallbackPorEstudiante.putIfAbsent(att.getStudent().getId(), att.getNivel());
            }
        }

        // Construir la lista de estudiantes con sus niveles (sin información de sedes)
        List<Map<String, Object>> estudiantesDto = new ArrayList<>();
        if (parent.getStudents() != null) {
            for (Student s : parent.getStudents()) {
                Map<String, Object> estDto = new HashMap<>();
                estDto.put("id", s.getId());
                estDto.put("nombreCompleto", s.getNombreCompleto());
                estDto.put("edad", s.getEdad());
                estDto.put("fechaNacimiento", s.getFechaNacimiento());
                estDto.put("estado", s.getEstado());
                estDto.put("clasesDisponibles", s.getClasesDisponibles());

                // Construir matrículas con nivel intacto pero sede oculta
                List<Map<String, Object>> matriculasDto = new ArrayList<>();
                if (s.getMatriculas() != null) {
                    for (Enrollment m : s.getMatriculas()) {
                        Map<String, Object> matDto = new HashMap<>();
                        matDto.put("id", m.getId());
                        matDto.put("nivel", m.getNivel());
                        matDto.put("sede", null); // No exponer datos de sede
                        matDto.put("plan", m.getPlanMensualidad() != null ? m.getPlanMensualidad().getNombre() : null);
                        matriculasDto.add(matDto);
                    }
                }

                // Complementos activos del estudiante (Gym Virtual, Pista Adicional, etc.)
                List<Map<String, Object>> complementosDto = new ArrayList<>();
                for (var asignacion : studentComplementoRepository.findActivosByStudentId(s.getId())) {
                    Map<String, Object> compDto = new HashMap<>();
                    var complemento = asignacion.getComplemento();
                    compDto.put("nombre", complemento.getNombre());
                    compDto.put("vecesPorPeriodo", complemento.getVecesPorPeriodo());
                    // El periodo lo define el escenario del complemento (null si no controla asistencia)
                    compDto.put("periodoCuota", complemento.getEscenario() != null
                            ? complemento.getEscenario().getPeriodo().name() : null);
                    compDto.put("escenario", complemento.getEscenario() != null
                            ? complemento.getEscenario().getNombre() : null);
                    compDto.put("precio", monthlyBillingService.resolverPrecioComplemento(asignacion));
                    complementosDto.add(compDto);
                }
                estDto.put("complementos", complementosDto);

                // Fallback: si el estudiante no tiene matrículas activas, tomar el nivel
                // de su clase más reciente (disponible en deudas o últimas asistencias)
                if (matriculasDto.isEmpty()) {
                    String nivelDesdeAsistencia = nivelFallbackPorEstudiante.get(s.getId());
                    if (nivelDesdeAsistencia != null) {
                        Map<String, Object> matDto = new HashMap<>();
                        matDto.put("id", 0);
                        matDto.put("nivel", nivelDesdeAsistencia);
                        matDto.put("sede", null);
                        matriculasDto.add(matDto);
                    }
                }

                estDto.put("matriculas", matriculasDto);
                estudiantesDto.add(estDto);
            }
        }
        parentDto.put("students", estudiantesDto);

        // Construir diccionario de estilos de grupos desde todas las sedes activas
        // (nombre -> {emoji, colorHex}) para que el frontend pueda aplicar colores y emojis
        // sin depender del objeto sede (que se limpia por seguridad)
        // PERF-N1-02: findAllActivasWithGrupos() con LEFT JOIN FETCH elimina N+1 residual
        List<Sede> sedes = sedeRepository.findAllActivasWithGrupos(parent.getClubId());
        Map<String, Map<String, String>> estilosGrupos = new HashMap<>();
        for (Sede sede : sedes) {
            if (Boolean.TRUE.equals(sede.getActiva()) && sede.getGrupos() != null) {
                for (GrupoSede grupo : sede.getGrupos()) {
                    if (grupo.getNombre() != null && !estilosGrupos.containsKey(grupo.getNombre())) {
                        Map<String, String> estilo = new HashMap<>();
                        estilo.put("emoji", grupo.getEmoji());
                        estilo.put("colorHex", grupo.getColorHex());
                        estilosGrupos.put(grupo.getNombre(), estilo);
                    }
                }
            }
        }

        // Construir lista unificada de deudas: clases + cargos extras
        // Los cargos extras se mapean con un formato compatible con Attendance DTO
        List<Map<String, Object>> deudasUnificadas = new ArrayList<>();

        boolean esMensualidad = config != null && config.getEsquemaCobro() == com.asistencia.erp.entity.ClubConfig.EsquemaCobro.MENSUALIDAD;
        String conceptoMensualidad = esMensualidad
                ? "Mensualidad " + monthlyBillingService.mesActualEnEspanol() + " - Tarifa " + monthlyBillingService.resolveTariffLabel(config)
                : null;

        // Primero: deudas de clases (formato Attendance)
        for (Attendance att : deudasClases) {
            Map<String, Object> deudaMap = new HashMap<>();
            deudaMap.put("id", att.getId());
            deudaMap.put("tipo", "CLASE");
            deudaMap.put("concepto", esMensualidad ? conceptoMensualidad : (att.getNivel() != null ? att.getNivel() : "Clase"));
            deudaMap.put("fecha", att.getFecha());
            deudaMap.put("precioCobrado", att.getPrecioCobrado());
            deudaMap.put("nivel", att.getNivel());
            // Incluir nombre del estudiante
            if (att.getStudent() != null) {
                Map<String, Object> estudianteMap = new HashMap<>();
                estudianteMap.put("id", att.getStudent().getId());
                estudianteMap.put("nombreCompleto", att.getStudent().getNombreCompleto());
                deudaMap.put("student", estudianteMap);
            }
            deudaMap.put("nombreEstudianteHistorico", att.getNombreEstudianteHistorico());
            deudasUnificadas.add(deudaMap);
        }

        // Luego: cargos extras (matrícula, seguro)
        for (FinancialLog cargo : cargosExtras) {
            Map<String, Object> cargoMap = new HashMap<>();
            cargoMap.put("id", cargo.getId());
            cargoMap.put("tipo", "CARGO_EXTRA");
            cargoMap.put("concepto", cargo.getConcepto() != null ? cargo.getConcepto() : "Cargo Extra");
            cargoMap.put("fecha", cargo.getFecha());
            cargoMap.put("precioCobrado", cargo.getMonto());
            cargoMap.put("monto", cargo.getMonto());
            cargoMap.put("nivel", null);
            cargoMap.put("student", null);
            cargoMap.put("nombreEstudianteHistorico", null);
            deudasUnificadas.add(cargoMap);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("parent", parentDto);
        response.put("deudaTotal", deudaTotal);
        response.put("financialLogs", logs);
        response.put("deudas", deudasUnificadas);  // Lista unificada con clases + cargos extras
        response.put("ultimasClases", ultimasClases);
        response.put("estilosGrupos", estilosGrupos);

        // Incluir la configuración de cobro del club para Matrícula, Seguro y Esquema de cobro
        try {
            // SEC: nunca hacer fallback a "cualquier" ClubConfig de la base de datos — eso
            // exponía la configuración de cobro (tarifas, esquema) de OTRO club al portal
            // público de un padre cuyo propio club aún no tuviera config guardada.
            var clubConfigOpt = (clubId != null)
                    ? clubConfigRepository.findByAdminId(clubId)
                    : java.util.Optional.<com.asistencia.erp.entity.ClubConfig>empty();

            if (clubConfigOpt.isPresent()) {
                var cc = clubConfigOpt.get();
                response.put("clubConfig", Map.of(
                    "cobraMatricula", Boolean.TRUE.equals(cc.getCobraMatricula()),
                    "montoMatricula", cc.getMontoMatricula() != null ? cc.getMontoMatricula() : 0,
                    "matriculaObligatoria", Boolean.TRUE.equals(cc.getMatriculaObligatoria()),
                    "cobraSeguro", Boolean.TRUE.equals(cc.getCobraSeguro()),
                    "montoSeguro", cc.getMontoSeguro() != null ? cc.getMontoSeguro() : 0,
                    "seguroObligatorio", Boolean.TRUE.equals(cc.getSeguroObligatorio()),
                    "esquemaCobro", cc.getEsquemaCobro() != null ? cc.getEsquemaCobro().name() : "MENSUALIDAD"
                ));
            } else {
                response.put("clubConfig", Map.of(
                    "cobraMatricula", false,
                    "montoMatricula", 0,
                    "matriculaObligatoria", false,
                    "cobraSeguro", false,
                    "montoSeguro", 0,
                    "seguroObligatorio", false,
                    "esquemaCobro", "MENSUALIDAD"
                ));
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(response);
    }
}

