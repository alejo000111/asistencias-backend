package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Complemento;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.Escenario;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.PlanCupo;
import com.asistencia.erp.entity.PlanMensualidad;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.entity.StudentComplemento;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentComplementoRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import com.asistencia.erp.service.FinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceBillingService {

    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final AttendanceRepository attendanceRepository;
    private final ClubConfigResolver clubConfigResolver;
    private final MonthlyBillingService monthlyBillingService;
    private final BillingSupportUtils billingSupportUtils;
    private final AppUserRepository appUserRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentComplementoRepository studentComplementoRepository;
    private final FinancialLogRepository financialLogRepository;

    @Value("${precios.grupal}")
    private BigDecimal precioGrupal;

    @Value("${precios.personalizada}")
    private BigDecimal precioPersonalizada;

    public Attendance registrarAsistencia(Long studentId,
                                    FinancialService.TipoClase tipoClase,
                                    String nivel,
                                    String fecha,
                                    BigDecimal precioPersonalizado,
                                    Long sedeId,
                                    Boolean esClaseSuelta) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new RuntimeException("Estudiante no encontrado"));

        ClubConfig config = clubConfigResolver.resolveForStudent(student);
        LocalDateTime fechaClaseBase = (fecha != null && !fecha.isEmpty())
            ? LocalDate.parse(fecha).atStartOfDay()
            : LocalDateTime.now();

        BigDecimal precioFinal;
        String fueraDePlanPaqueteMotivo = null;
        String avisoTarifaNoConfigurada = null;
        if (precioPersonalizado != null && precioPersonalizado.compareTo(BigDecimal.ZERO) > 0) {
            precioFinal = precioPersonalizado;
        } else if (config == null) {
            precioFinal = (tipoClase == FinancialService.TipoClase.PERSONALIZADA) ? precioPersonalizada : precioGrupal;
        } else if (Boolean.TRUE.equals(esClaseSuelta) || config.getEsquemaCobro() == ClubConfig.EsquemaCobro.POR_CLASE) {
            Long targetSedeId = billingSupportUtils.resolveTargetSedeId(student, sedeId);
            precioFinal = billingSupportUtils.resolverValorClaseExtra(config, targetSedeId, tipoClase);
            if (precioFinal.compareTo(BigDecimal.ZERO) == 0 && !billingSupportUtils.esTarifaClaseSueltaConfigurada(config, tipoClase)) {
                avisoTarifaNoConfigurada = "Esta clase se registró en $0 porque tu club aún no tiene configurada la "
                    + "\"Tarifa Clase Suelta\". Ve a Ajustes de Cobros → Tarifas por Clase para configurarla.";
            }
        } else if (config.getEsquemaCobro() == ClubConfig.EsquemaCobro.MENSUALIDAD) {
            Long targetSedeId = billingSupportUtils.resolveTargetSedeId(student, sedeId);
            Enrollment matriculaSede = resolveEnrollmentParaMensualidad(student, targetSedeId);
            precioFinal = monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(student, matriculaSede, fechaClaseBase, config);
        } else if (config.getEsquemaCobro() == ClubConfig.EsquemaCobro.PAQUETE) {
            // El esquema PAQUETE se contabiliza en clases, no en dinero: cada asistencia
            // descuenta una clase disponible, incluso si el saldo queda en negativo (deuda
            // de clases). La próxima compra de paquete se netea automáticamente contra ese
            // negativo (ver PackagePurchaseService, que suma clasesACreditar al valor actual).
            int actuales = student.getClasesDisponibles() != null ? student.getClasesDisponibles() : 0;
            boolean excedioPaquete = actuales <= 0;
            student.setClasesDisponibles(actuales - 1);
            studentRepository.save(student);
            precioFinal = BigDecimal.ZERO;

            // PAQUETE se contabiliza EXCLUSIVAMENTE en clases (nunca en dinero): dejar que
            // clasesDisponibles quede negativo es la única "deuda" de este esquema. Generar
            // aquí un CARGO_EXTRA en dinero era el "precio de respaldo" legacy que la
            // documentación del producto marca como eliminado — cobraba dos veces (clase
            // negativa + cargo monetario) por la misma clase excedida.
            if (excedioPaquete && sedeId != null) {
                fueraDePlanPaqueteMotivo = "Sin clases disponibles en tu paquete (saldo de clases quedó en "
                    + (actuales - 1) + ").";
            }
        } else {
            precioFinal = (tipoClase == FinancialService.TipoClase.PERSONALIZADA) ? precioPersonalizada : precioGrupal;
        }

        LocalDateTime fechaFinal = (fecha != null && !fecha.isEmpty())
            ? LocalDate.parse(fecha).atTime(LocalTime.now())
            : LocalDateTime.now();

        Attendance asistencia = new Attendance();
        asistencia.setStudent(student);
        asistencia.setFecha(fechaFinal);
        asistencia.setPrecioCobrado(precioFinal);
        asistencia.setClasePaga(precioFinal.compareTo(BigDecimal.ZERO) == 0);
        asistencia.setNivel(nivel);
        asistencia.setNombreEstudianteHistorico(student.getNombreCompleto());
        asistencia.setTipoClase(tipoClase.name());
        asistencia.setClubId(student.getClubId());

        // FASE 4 — Nómina: registrar quién dictó/tomó la asistencia
        var currentUser = SecurityUtils.getCurrentUser();
        if (currentUser != null) {
            asistencia.setRegistradoPorId(currentUser.getUserId());
            AppUser registrador = appUserRepository.findById(currentUser.getUserId()).orElse(null);
            asistencia.setRegistradoPorNombre(registrador != null && registrador.getNombreCompleto() != null
                    ? registrador.getNombreCompleto() : currentUser.getUsername());
        }

        if (sedeId != null) {
            Sede sede = sedeRepository.findById(sedeId)
                .orElseThrow(() -> new RuntimeException("Sede no encontrada con ID: " + sedeId));
            asistencia.setSede(sede);

            if (!Boolean.TRUE.equals(esClaseSuelta)) {
                CuotaResultado resultado = evaluarCuota(student, sede, nivel, fechaFinal.toLocalDate());
                String motivoCuota = resultado.motivo();

                if (resultado.excedida() && config != null && config.getEsquemaCobro() == ClubConfig.EsquemaCobro.MENSUALIDAD) {
                    BigDecimal montoExtra = crearCargoPorClaseExtra(student, config, sedeId, tipoClase, motivoCuota);
                    if (montoExtra != null && montoExtra.compareTo(BigDecimal.ZERO) > 0) {
                        motivoCuota = motivoCuota + " Se generará un cobro extra de $" + montoExtra.toPlainString()
                            + " al perfil del padre, aparte de la mensualidad.";
                    } else if (!billingSupportUtils.esTarifaClaseSueltaConfigurada(config, tipoClase)) {
                        avisoTarifaNoConfigurada = "Este deportista se pasó de su cupo, pero no se generó el cobro "
                            + "extra porque tu club aún no tiene configurada la \"Tarifa Clase Suelta\". "
                            + "Ve a Ajustes de Cobros → Tarifas por Clase para configurarla.";
                    }
                }

                if (resultado.excedida() || fueraDePlanPaqueteMotivo != null) {
                    asistencia.setFueraDePlan(true);
                    asistencia.setComplementoEvaluadoId(resultado.complementoId());
                    String motivoFinal = resultado.excedida() ? motivoCuota : null;
                    if (fueraDePlanPaqueteMotivo != null) {
                        motivoFinal = (motivoFinal == null) ? fueraDePlanPaqueteMotivo : motivoFinal + " " + fueraDePlanPaqueteMotivo;
                    }
                    asistencia.setMotivoFueraDePlan(motivoFinal);
                }
            } else {
                asistencia.setMotivoFueraDePlan("Valor x Clase (suelta)");
                asistencia.setFueraDePlan(false);
            }
        }

        asistencia.setAvisoTarifaNoConfigurada(avisoTarifaNoConfigurada);
        return attendanceRepository.save(asistencia);
    }

    /**
     * Resuelve qué matrícula (sede + PlanMensualidad) usar para calcular la mensualidad de
     * un estudiante: prioriza la marcada como sede principal (Enrollment.esPrincipal), y si
     * ninguna lo está (datos legados), cae a la matrícula de la sede donde se tomó la clase.
     */
    private Enrollment resolveEnrollmentParaMensualidad(Student student, Long targetSedeId) {
        if (student.getMatriculas() == null) {
            return null;
        }
        return student.getMatriculas().stream()
            .filter(e -> Boolean.TRUE.equals(e.getEsPrincipal()))
            .findFirst()
            .orElseGet(() -> student.getMatriculas().stream()
                .filter(e -> e.getSede() != null && e.getSede().getId().equals(targetSedeId))
                .findFirst().orElse(null));
    }

    /**
     * Genera un CARGO_EXTRA en el perfil del padre por una clase que excedió el cupo/paquete
     * del estudiante. Devuelve el monto cobrado, o null si no había config/padre o el valor
     * de clase extra es cero (nada que cobrar).
     */
    private BigDecimal crearCargoPorClaseExtra(Student student, ClubConfig config, Long sedeId,
                                                FinancialService.TipoClase tipoClase, String motivo) {
        if (config == null || student.getParent() == null) {
            return null;
        }
        BigDecimal monto = billingSupportUtils.resolverValorClaseExtra(config, sedeId, tipoClase);
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        FinancialLog log = new FinancialLog();
        log.setParent(student.getParent());
        log.setNombreClienteRespaldo(student.getParent().getNombreCompleto());
        log.setFecha(LocalDateTime.now());
        log.setMonto(monto);
        log.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
        log.setConcepto("Clase extra fuera de plan - " + (motivo != null ? motivo : "") + " - " + student.getNombreCompleto());
        log.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
        log.setClubId(student.getClubId());
        financialLogRepository.save(log);
        return monto;
    }

    /** Resultado de evaluar la cuota de un estudiante contra su plan/complementos al registrar una asistencia. */
    private record CuotaResultado(boolean excedida, Long complementoId, String motivo) {
        static CuotaResultado dentroDeCuota() {
            return new CuotaResultado(false, null, null);
        }
    }


    /**
     * Evalúa si registrar esta asistencia supera la cuota del estudiante en el ESCENARIO
     * de la sede donde se toma (y, cuando aplica, el grupo/nivel). Combina dos cubetas,
     * ambas evaluadas — la primera que se exceda define el motivo devuelto:
     *
     * 1) Cubeta de escenario: cupos que el/los PlanMensualidad de sus matrículas incluyen
     *    de ese escenario + Complementos activos del escenario SIN grupo asignado.
     * 2) Cubeta de grupo: Complementos activos del escenario CON grupoNombre igual al nivel
     *    de esta asistencia — su cuota se cuenta solo contra asistencias de ese mismo grupo.
     *
     * El conteo agrega TODAS las sedes que comparten el escenario, de modo que un club con
     * dos pistas no deja pasar el doble de días. La ventana (semana ISO lunes-domingo o mes
     * calendario) la define Escenario.periodo, así que plan y complementos del mismo espacio
     * nunca quedan con ventanas contradictorias.
     *
     * Devuelve "dentro de cuota" si la sede no tiene escenario (club que no usa escenarios)
     * o si alguna regla de la cubeta no tiene tope definido.
     */
    private CuotaResultado evaluarCuota(Student student, Sede sede, String nivel, LocalDate diaClase) {
        Escenario escenario = sede != null ? sede.getEscenario() : null;
        if (escenario == null) {
            return CuotaResultado.dentroDeCuota(); // club sin escenarios: sin control de cuota
        }

        Long escenarioId = escenario.getId();
        boolean esMensual = escenario.getPeriodo() == Escenario.Periodo.MENSUAL;
        String unidad = esMensual ? "mensual" : "semanal";
        String porUnidad = esMensual ? "por mes" : "por semana";

        LocalDateTime desde;
        LocalDateTime hasta;
        if (esMensual) {
            LocalDate primerDiaMes = diaClase.withDayOfMonth(1);
            desde = primerDiaMes.atStartOfDay();
            hasta = primerDiaMes.plusMonths(1).atStartOfDay();
        } else {
            LocalDate lunes = diaClase.minusDays(diaClase.getDayOfWeek().getValue() - 1);
            desde = lunes.atStartOfDay();
            hasta = lunes.plusDays(7).atStartOfDay();
        }

        // ─── Cubeta 1: escenario completo (cupos del plan + complementos sin grupo) ───
        boolean hayReglaEscenario = false;
        boolean escenarioIlimitado = false;
        int topeEscenario = 0;

        for (Enrollment e : enrollmentRepository.findByStudentIdWithPlan(student.getId())) {
            PlanMensualidad plan = e.getPlanMensualidad();
            if (plan == null || plan.getCupos() == null) continue;
            for (PlanCupo cupo : plan.getCupos()) {
                if (cupo.getEscenario() != null && cupo.getEscenario().getId().equals(escenarioId)) {
                    hayReglaEscenario = true;
                    topeEscenario += cupo.getCantidad() != null ? cupo.getCantidad() : 0;
                }
            }
        }

        // ─── Cubeta 2: grupo específico dentro del escenario ───
        boolean hayReglaGrupo = false;
        boolean grupoIlimitado = false;
        int topeGrupo = 0;
        Long complementoGrupoId = null;
        String complementoGrupoNombre = null;

        for (StudentComplemento sc : studentComplementoRepository.findActivosByStudentIdAndEscenarioId(student.getId(), escenarioId)) {
            Complemento c = sc.getComplemento();
            boolean atadoAGrupo = c.getGrupoNombre() != null && !c.getGrupoNombre().isBlank();

            if (atadoAGrupo) {
                if (nivel == null || !c.getGrupoNombre().equalsIgnoreCase(nivel)) continue; // no aplica a este grupo
                hayReglaGrupo = true;
                if (c.getVecesPorPeriodo() == null) {
                    grupoIlimitado = true;
                } else {
                    topeGrupo += c.getVecesPorPeriodo();
                    if (complementoGrupoId == null) {
                        complementoGrupoId = c.getId();
                        complementoGrupoNombre = c.getNombre();
                    }
                }
            } else {
                hayReglaEscenario = true;
                if (c.getVecesPorPeriodo() == null) {
                    escenarioIlimitado = true;
                } else {
                    topeEscenario += c.getVecesPorPeriodo();
                }
            }
        }

        // La cubeta más específica (grupo) se evalúa primero para que el motivo nombre
        // el complemento exacto que se superó, en vez del tope general del escenario.
        if (hayReglaGrupo && !grupoIlimitado) {
            long yaRegistradas = attendanceRepository.countByStudentIdAndEscenarioIdAndNivelAndFechaBetween(
                student.getId(), escenarioId, nivel, desde, hasta);
            if (yaRegistradas + 1 > topeGrupo) {
                return new CuotaResultado(true, complementoGrupoId,
                    "Superó la cuota " + unidad + " de '" + complementoGrupoNombre + "' (" + topeGrupo + " veces " + porUnidad + ").");
            }
        }

        if (hayReglaEscenario && !escenarioIlimitado) {
            long yaRegistradas = attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(
                student.getId(), escenarioId, desde, hasta);
            if (yaRegistradas + 1 > topeEscenario) {
                return new CuotaResultado(true, null,
                    "Superó su cuota " + unidad + " de " + escenario.getNombre() + ": lleva " + (yaRegistradas + 1)
                    + " de " + topeEscenario + " incluidas en su plan/complementos.");
            }
        }

        return CuotaResultado.dentroDeCuota();
    }
}
