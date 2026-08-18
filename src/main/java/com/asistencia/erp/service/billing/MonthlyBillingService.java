package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.PlanMensualidad;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MonthlyBillingService {

    private static final BigDecimal DEFAULT_PRICE = BigDecimal.valueOf(5000);

    private final AttendanceRepository attendanceRepository;

    /**
     * Calcula la tarifa plana vigente HOY: Preferencial, Actual o Mora.
     * Las tres son montos planos e independientes definidos por el admin — la Mora
     * NO es un recargo que se sume a la Actual, es su propio precio final.
     * Un día de corte en 0 desactiva esa tarifa (ver ClubConfig.diaLimitePreferencial/diaCorteMora).
     * Úsese al registrar una asistencia nueva (el "día de referencia" es el momento en que se
     * genera el cargo). Para recalcular el precio de una deuda ya existente al aplicarle un pago
     * con fecha propia (posiblemente retroactiva), usar la sobrecarga con {@code fechaReferencia}.
     */
    public BigDecimal calculateMonthlyPrice(ClubConfig config, Long sedeId, LocalDateTime fechaClase) {
        return calculateMonthlyPrice(config, sedeId, fechaClase, LocalDate.now());
    }

    /**
     * Igual que {@link #calculateMonthlyPrice(ClubConfig, Long, LocalDateTime)}, pero permite
     * indicar explícitamente el día de referencia contra el que se evalúan Preferencial/Mora
     * (diaLimitePreferencial/diaCorteMora) — típicamente la fecha REAL en que el padre pagó
     * (posiblemente distinta a "hoy" si el admin digita el abono días después de recibido).
     */
    public BigDecimal calculateMonthlyPrice(ClubConfig config, Long sedeId, LocalDateTime fechaClase, LocalDate fechaReferencia) {
        BigDecimal montoEstandar = config.getMontoEstandar();
        BigDecimal montoPreferencial = config.getMontoPreferencial();
        BigDecimal montoMora = config.getMontoMora();
        Integer diaLimPref = config.getDiaLimitePreferencial();
        Integer diaCorteMora = config.getDiaCorteMora();

        if (Boolean.TRUE.equals(config.getPreciosDiferenciados()) && sedeId != null) {
            var tarifaSede = config.getTarifasSede().stream()
                .filter(t -> t.getSede().getId().equals(sedeId))
                .findFirst();

            if (tarifaSede.isPresent()) {
                montoEstandar = tarifaSede.get().getMontoEstandar();
                montoPreferencial = tarifaSede.get().getMontoPreferencial();
                montoMora = tarifaSede.get().getMontoMora();
            }
        }

        return resolverMontoPorCalendario(montoPreferencial, montoEstandar, montoMora, diaLimPref, diaCorteMora, fechaClase, fechaReferencia);
    }

    /**
     * Calcula la mensualidad de una matrícula usando su PlanMensualidad propio de sede,
     * si tiene uno asignado (misma lógica de calendario Preferencial/Actual/Mora, con los
     * montos del plan en vez de los de ClubConfig/TarifaSede). Si la matrícula no tiene
     * plan asignado, cae al esquema legado (ClubConfig/TarifaSede) para no romper clubes
     * que no adoptaron planes por sede.
     */
    public BigDecimal calculateMonthlyPriceForEnrollment(Enrollment enrollment, ClubConfig config, LocalDateTime fechaClase) {
        return calculateMonthlyPriceForEnrollment(enrollment, config, fechaClase, LocalDate.now());
    }

    /** Igual que {@link #calculateMonthlyPriceForEnrollment(Enrollment, ClubConfig, LocalDateTime)} con día de referencia explícito. */
    public BigDecimal calculateMonthlyPriceForEnrollment(Enrollment enrollment, ClubConfig config, LocalDateTime fechaClase, LocalDate fechaReferencia) {
        PlanMensualidad plan = enrollment != null ? enrollment.getPlanMensualidad() : null;
        if (plan == null) {
            Long sedeId = enrollment != null && enrollment.getSede() != null ? enrollment.getSede().getId() : null;
            return calculateMonthlyPrice(config, sedeId, fechaClase, fechaReferencia);
        }

        // El calendario (días de corte) sigue siendo global a nivel club; solo los montos vienen del plan.
        return resolverMontoPorCalendario(
            plan.getMontoPreferencial(), plan.getMontoEstandar(), plan.getMontoMora(),
            config.getDiaLimitePreferencial(), config.getDiaCorteMora(), fechaClase, fechaReferencia
        );
    }

    private BigDecimal resolverMontoPorCalendario(BigDecimal montoPreferencial, BigDecimal montoEstandar, BigDecimal montoMora,
            Integer diaLimPref, Integer diaCorteMora, LocalDateTime fechaClase, LocalDate fechaReferencia) {
        BigDecimal actual = montoEstandar != null ? montoEstandar : (montoPreferencial != null ? montoPreferencial : DEFAULT_PRICE);
        boolean preferencialActiva = montoPreferencial != null && diaLimPref != null && diaLimPref > 0;
        boolean moraActiva = montoMora != null && diaCorteMora != null && diaCorteMora > 0;

        LocalDate referencia = fechaReferencia != null ? fechaReferencia : LocalDate.now();
        boolean esMesPasado = fechaClase != null && (fechaClase.getYear() < referencia.getYear()
            || (fechaClase.getYear() == referencia.getYear() && fechaClase.getMonthValue() < referencia.getMonthValue()));

        if (esMesPasado) {
            return moraActiva ? montoMora : actual;
        }

        int diaReferencia = referencia.getDayOfMonth();
        if (preferencialActiva && diaReferencia <= diaLimPref) {
            // montoPreferencial ya fue sobrescrito con el valor de TarifaSede/Plan si aplica
            // Por tanto, siempre se devuelve montoPreferencial (sea global, por sede o por plan)
            return montoPreferencial;
        }

        if (moraActiva && diaReferencia > diaCorteMora) {
            return montoMora;
        }

        return actual;
    }

    private static final String[] MESES_ES = {
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    };

    /**
     * Determina la tarifa vigente hoy según el calendario de mensualidad del club:
     * Preferencial (día 1 al diaLimitePreferencial), Mora (después de diaCorteMora)
     * o Actual (el rango intermedio).
     */
    public String resolveTariffLabel(ClubConfig config) {
        Integer diaLimPref = config.getDiaLimitePreferencial();
        Integer diaCorteMora = config.getDiaCorteMora();
        int diaHoy = LocalDate.now().getDayOfMonth();

        if (diaLimPref != null && diaLimPref > 0 && diaHoy <= diaLimPref) {
            return "Preferencial";
        }
        if (diaCorteMora != null && diaCorteMora > 0 && diaHoy > diaCorteMora) {
            return "Mora";
        }
        return "Actual";
    }

    public String mesActualEnEspanol() {
        return MESES_ES[LocalDate.now().getMonthValue() - 1];
    }

    public BigDecimal resolveAttendanceMonthlyCharge(Student student, Long sedeId, LocalDateTime fechaClase, ClubConfig config) {
        boolean yaTieneCobroMes = attendanceRepository.existsByStudentIdAndYearAndMonth(
            student.getId(),
            fechaClase.getYear(),
            fechaClase.getMonthValue()
        );

        if (yaTieneCobroMes) {
            return BigDecimal.ZERO;
        }

        return calculateMonthlyPrice(config, sedeId, fechaClase);
    }

    /** Igual a resolveAttendanceMonthlyCharge, pero usa el PlanMensualidad de la matrícula si tiene uno asignado. */
    public BigDecimal resolveAttendanceMonthlyChargeForEnrollment(Student student, Enrollment enrollment, LocalDateTime fechaClase, ClubConfig config) {
        boolean yaTieneCobroMes = attendanceRepository.existsByStudentIdAndYearAndMonth(
            student.getId(),
            fechaClase.getYear(),
            fechaClase.getMonthValue()
        );

        if (yaTieneCobroMes) {
            return BigDecimal.ZERO;
        }

        return calculateMonthlyPriceForEnrollment(enrollment, config, fechaClase);
    }

    /**
     * Resuelve el precio mensual de un StudentComplemento: precioBase si el complemento
     * no está diferenciado por sede, o el precio de ComplementoSedePrecio para la
     * sedeOrigen de la asignación (fallback a precioBase si no hay override para esa sede).
     */
    public BigDecimal resolverPrecioComplemento(com.asistencia.erp.entity.StudentComplemento asignacion) {
        var complemento = asignacion.getComplemento();
        if (!Boolean.TRUE.equals(complemento.getPreciosDiferenciadosPorSede())) {
            return complemento.getPrecioBase() != null ? complemento.getPrecioBase() : BigDecimal.ZERO;
        }
        Long sedeOrigenId = asignacion.getSedeOrigen() != null ? asignacion.getSedeOrigen().getId() : null;
        return complemento.getPreciosPorSede().stream()
            .filter(p -> p.getSedeOrigen().getId().equals(sedeOrigenId))
            .map(com.asistencia.erp.entity.ComplementoSedePrecio::getPrecio)
            .findFirst()
            .orElse(complemento.getPrecioBase() != null ? complemento.getPrecioBase() : BigDecimal.ZERO);
    }
}
