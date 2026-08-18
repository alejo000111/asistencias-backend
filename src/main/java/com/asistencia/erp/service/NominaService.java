package com.asistencia.erp.service;

import com.asistencia.erp.dto.ClaseNominaDTO;
import com.asistencia.erp.dto.NominaDTO;
import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FASE 4 — Motor de Nómina.
 *
 * Consolida, para un rango de fechas, las asistencias que cada entrenador/empleado
 * registró en campo y calcula el monto a pagar según su tarifa configurada.
 *
 * Regla de unificación (FASE 4.3): si un mismo empleado registra varias asistencias el
 * mismo día en la misma sede (p.ej. dictó dos grupos distintos), esas asistencias se
 * tratan como UNA sola "sesión" pagable — el club le paga una vez por esa jornada, no
 * una vez por cada grupo.
 */
@Service
@RequiredArgsConstructor
public class NominaService {

    private final AttendanceRepository attendanceRepository;
    private final AppUserRepository appUserRepository;

    /** Agrupación interna: una jornada pagable de un empleado (mismo día + misma sede). */
    private record Sesion(Long empleadoId, LocalDate fecha, Long sedeId, String sedeNombre, List<Attendance> asistencias) {
        boolean pagada() {
            return asistencias.stream().allMatch(a -> Boolean.TRUE.equals(a.getPagadoNomina()));
        }
        LocalDate fechaPago() {
            return pagada() ? asistencias.get(0).getFechaPagoNomina() : null;
        }
        String metodoPago() {
            return pagada() ? asistencias.get(0).getMetodoPagoNomina() : null;
        }
    }

    @Transactional(readOnly = true)
    public List<NominaDTO> calcularNomina(Long clubId, String fechaDesde, String fechaHasta) {
        return calcularNomina(clubId, fechaDesde, fechaHasta, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<NominaDTO> calcularNomina(Long clubId, String fechaDesde, String fechaHasta,
                                           Long empleadoId, Long sedeId, String estadoPago) {
        List<Sesion> sesiones = obtenerSesiones(clubId, fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago);

        Map<Long, Long> conteoPorEmpleado = new LinkedHashMap<>();
        Map<Long, String> nombreHistoricoPorEmpleado = new LinkedHashMap<>();
        for (Sesion s : sesiones) {
            conteoPorEmpleado.merge(s.empleadoId(), 1L, Long::sum);
            nombreHistoricoPorEmpleado.putIfAbsent(s.empleadoId(), s.asistencias().get(0).getRegistradoPorNombre());
        }

        List<NominaDTO> resultado = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : conteoPorEmpleado.entrySet()) {
            Long empId = entry.getKey();
            long cantidadClases = entry.getValue();

            AppUser empleado = appUserRepository.findById(empId).orElse(null);

            // Un ADMIN (dueño del club) que registra clases en campo queda excluido de la
            // nómina por defecto (exentoNomina = true). Si él mismo desmarca la casilla,
            // sus asistencias se liquidan con su propia tarifaPorClase, igual que un entrenador.
            if (empleado != null && empleado.getRole() == AppUser.Role.ADMIN
                    && (empleado.getExentoNomina() == null || empleado.getExentoNomina())) {
                continue;
            }

            String nombre = empleado != null
                    ? (empleado.getNombreCompleto() != null ? empleado.getNombreCompleto() : empleado.getUsername())
                    : nombreHistoricoPorEmpleado.get(empId) + " (empleado eliminado)";
            String tipoTarifa = empleado != null && empleado.getTipoTarifa() != null
                    ? empleado.getTipoTarifa().name() : AppUser.TipoTarifa.POR_CLASE.name();
            BigDecimal tarifa = empleado != null && empleado.getTarifaPorClase() != null
                    ? empleado.getTarifaPorClase() : BigDecimal.ZERO;

            BigDecimal total = tarifa.multiply(BigDecimal.valueOf(cantidadClases));

            resultado.add(new NominaDTO(empId, nombre, tipoTarifa, tarifa, cantidadClases, total));
        }

        resultado.sort((a, b) -> b.getTotalPagar().compareTo(a.getTotalPagar()));
        return resultado;
    }

    /** FASE 4.2/4.3 — Lista de sesiones (jornadas) para que el admin las marque una por una como pagadas. */
    @Transactional(readOnly = true)
    public List<ClaseNominaDTO> listarClases(Long clubId, String fechaDesde, String fechaHasta,
                                              Long empleadoId, Long sedeId, String estadoPago) {
        List<Sesion> sesiones = obtenerSesiones(clubId, fechaDesde, fechaHasta, empleadoId, sedeId, estadoPago);

        Map<Long, String> nombrePorEmpleado = new LinkedHashMap<>();
        Map<Long, BigDecimal> tarifaPorEmpleado = new LinkedHashMap<>();
        for (Sesion s : sesiones) {
            if (nombrePorEmpleado.containsKey(s.empleadoId())) continue;
            AppUser empleado = appUserRepository.findById(s.empleadoId()).orElse(null);
            String nombre = empleado != null
                    ? (empleado.getNombreCompleto() != null ? empleado.getNombreCompleto() : empleado.getUsername())
                    : s.asistencias().get(0).getRegistradoPorNombre() + " (empleado eliminado)";
            nombrePorEmpleado.put(s.empleadoId(), nombre);
            tarifaPorEmpleado.put(s.empleadoId(),
                    empleado != null && empleado.getTarifaPorClase() != null ? empleado.getTarifaPorClase() : BigDecimal.ZERO);
        }

        return sesiones.stream()
                .map(s -> {
                    List<String> niveles = s.asistencias().stream()
                            .map(Attendance::getNivel)
                            .filter(n -> n != null && !n.isBlank())
                            .distinct()
                            .toList();
                    List<String> tipos = s.asistencias().stream()
                            .map(Attendance::getTipoClase)
                            .filter(t -> t != null && !t.isBlank())
                            .distinct()
                            .toList();
                    String tipoClase = tipos.size() == 1 ? tipos.get(0) : null;
                    List<Long> ids = s.asistencias().stream().map(Attendance::getId).toList();

                    return new ClaseNominaDTO(
                            ids,
                            s.empleadoId(),
                            nombrePorEmpleado.get(s.empleadoId()),
                            s.fecha(),
                            s.sedeNombre(),
                            niveles,
                            tipoClase,
                            tarifaPorEmpleado.get(s.empleadoId()),
                            s.pagada(),
                            s.fechaPago(),
                            s.metodoPago()
                    );
                })
                .sorted(Comparator.comparing(ClaseNominaDTO::getFecha).reversed())
                .toList();
    }

    /**
     * FASE 4.3 — Marca (o desmarca) como pagada toda una sesión (todas las asistencias que la componen).
     * Los IDs pueden venir de varias sesiones distintas a la vez (p.ej. varios entrenadores pagados
     * el mismo día con el mismo medio), ya que la operación es por asistencia individual.
     */
    @Transactional
    public int marcarPagoSesion(Long clubId, List<Long> attendanceIds, boolean pagado, LocalDate fechaPago, String metodoPago) {
        int actualizadas = 0;
        for (Long id : attendanceIds) {
            Attendance a = attendanceRepository.findById(id).orElse(null);
            if (a == null || a.getClubId() == null || !a.getClubId().equals(clubId)) {
                continue;
            }
            a.setPagadoNomina(pagado);
            a.setFechaPagoNomina(pagado ? fechaPago : null);
            a.setMetodoPagoNomina(pagado ? metodoPago : null);
            attendanceRepository.save(a);
            actualizadas++;
        }
        return actualizadas;
    }

    private List<Sesion> obtenerSesiones(Long clubId, String fechaDesde, String fechaHasta,
                                          Long empleadoId, Long sedeId, String estadoPago) {
        LocalDateTime desde = resolverDesde(fechaDesde);
        LocalDateTime hasta = resolverHasta(fechaHasta);
        Boolean soloPagado = resolverEstadoPago(estadoPago);

        // El filtro de estado de pago se aplica DESPUÉS de agrupar por sesión (no en la consulta SQL),
        // porque el estado de una sesión depende de TODAS sus asistencias, no de una sola.
        List<Attendance> asistencias = attendanceRepository.findForNomina(clubId, desde, hasta, empleadoId, sedeId, null);

        Map<String, List<Attendance>> agrupadas = new LinkedHashMap<>();
        for (Attendance a : asistencias) {
            Long sede = a.getSede() != null ? a.getSede().getId() : -1L;
            String clave = a.getRegistradoPorId() + "_" + a.getFecha().toLocalDate() + "_" + sede;
            agrupadas.computeIfAbsent(clave, k -> new ArrayList<>()).add(a);
        }

        List<Sesion> sesiones = agrupadas.values().stream()
                .map(lista -> {
                    Attendance primera = lista.get(0);
                    Long sede = primera.getSede() != null ? primera.getSede().getId() : null;
                    String sedeNombre = primera.getSede() != null ? primera.getSede().getNombre() : null;
                    return new Sesion(primera.getRegistradoPorId(), primera.getFecha().toLocalDate(), sede, sedeNombre, lista);
                })
                .collect(Collectors.toList());

        if (soloPagado != null) {
            sesiones = sesiones.stream().filter(s -> s.pagada() == soloPagado).toList();
        }

        return sesiones;
    }

    private LocalDateTime resolverDesde(String fechaDesde) {
        return (fechaDesde != null && !fechaDesde.isBlank())
                ? LocalDate.parse(fechaDesde).atStartOfDay()
                : LocalDate.now().withDayOfMonth(1).atStartOfDay();
    }

    private LocalDateTime resolverHasta(String fechaHasta) {
        return (fechaHasta != null && !fechaHasta.isBlank())
                ? LocalDate.parse(fechaHasta).atTime(LocalTime.MAX)
                : LocalDateTime.now();
    }

    private Boolean resolverEstadoPago(String estadoPago) {
        if (estadoPago == null || estadoPago.isBlank() || estadoPago.equalsIgnoreCase("TODOS")) {
            return null;
        }
        if (estadoPago.equalsIgnoreCase("PAGADO")) {
            return Boolean.TRUE;
        }
        if (estadoPago.equalsIgnoreCase("PENDIENTE")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
