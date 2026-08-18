package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CashAndPaymentAllocationService {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final AttendanceRepository attendanceRepository;
    private final FinancialLogRepository financialLogRepository;
    private final ClubConfigResolver clubConfigResolver;
    private final MonthlyBillingService monthlyBillingService;
    private final ParentLockManager parentLockManager;
    private final EnrollmentRepository enrollmentRepository;

    private static class DebtItem {
        private final Object entity;
        private final LocalDateTime fecha;
        private final BigDecimal monto;

        private DebtItem(Object entity, LocalDateTime fecha, BigDecimal monto) {
            this.entity = entity;
            this.fecha = fecha;
            this.monto = monto;
        }
    }

    @Transactional
    public void procesarPagosPendientes(Long parentId) {
        procesarPagosPendientes(parentId, LocalDate.now());
    }

    /**
     * @param fechaReferenciaPago fecha real en la que se hizo/registra el pago que dispara este
     *                            reproceso — determina qué tarifa (Preferencial/Actual/Mora) aplica
     *                            a las deudas de MENSUALIDAD recalculadas, en vez de usar siempre
     *                            "hoy" del servidor (bug: un abono digitado días después de recibido,
     *                            con fecha retroactiva, terminaba cobrando la tarifa vigente el día
     *                            en que el admin lo digitó, no el día en que el padre realmente pagó).
     */
    @Transactional
    public void procesarPagosPendientes(Long parentId, LocalDate fechaReferenciaPago) {
        parentLockManager.executeWithLock(parentId, () -> {
            Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            if (parent.getSaldoAbono() == null || parent.getSaldoAbono().compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }

            ClubConfig config = clubConfigResolver.resolveForParent(parent);

            List<Attendance> clasesPendientes = attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parentId);
            List<FinancialLog> cargosExtras = financialLogRepository.findByParentIdAndTipoMovimiento(
                parentId,
                FinancialLog.MovementType.CARGO_EXTRA
            );

            List<DebtItem> debts = new ArrayList<>();
            for (Attendance attendance : clasesPendientes) {
                BigDecimal monto = attendance.getPrecioCobrado();
                if (config != null
                    && config.getEsquemaCobro() == ClubConfig.EsquemaCobro.MENSUALIDAD
                    && attendance.getPrecioCobrado().compareTo(BigDecimal.ZERO) > 0) {
                    // Recalcular el precio para el FIFO según la fecha real del pago (con posible
                    // mora) y respetando el PlanMensualidad de la matrícula si tiene uno asignado
                    // — antes se usaba siempre la tarifa genérica de ClubConfig/Sede, divergiendo
                    // del monto que la pantalla de "deudas" sí mostraba correctamente por plan.
                    // No se modifica precioCobrado histórico (inmutabilidad de Attendance).
                    Enrollment enrollment = resolveEnrollmentParaMensualidad(attendance);
                    monto = monthlyBillingService.calculateMonthlyPriceForEnrollment(
                        enrollment, config, attendance.getFecha(), fechaReferenciaPago
                    );
                }
                debts.add(new DebtItem(attendance, attendance.getFecha(), monto));
            }

            for (FinancialLog cargoExtra : cargosExtras) {
                debts.add(new DebtItem(cargoExtra, cargoExtra.getFecha(), cargoExtra.getMonto()));
            }

            debts.sort((left, right) -> left.fecha.compareTo(right.fecha));

            for (DebtItem debt : debts) {
                if (parent.getSaldoAbono().compareTo(debt.monto) < 0) {
                    break;
                }

                parent.setSaldoAbono(parent.getSaldoAbono().subtract(debt.monto));
                if (debt.entity instanceof Attendance attendance) {
                    attendance.setClasePaga(true);
                    attendanceRepository.save(attendance);

                    FinancialLog log = new FinancialLog();
                    log.setParent(parent);
                    log.setNombreClienteRespaldo(parent.getNombreCompleto());
                    log.setFecha(LocalDateTime.now());
                    log.setMonto(debt.monto);
                    log.setTipoMovimiento(FinancialLog.MovementType.USO_ABONO_CLASE);
                    log.setMetodoPago(FinancialLog.PaymentMethod.ABONO);
                    log.setClubId(parent.getClubId());
                    financialLogRepository.save(log);
                } else if (debt.entity instanceof FinancialLog cargo) {
                    financialLogRepository.delete(cargo);

                    FinancialLog log = new FinancialLog();
                    log.setParent(parent);
                    log.setNombreClienteRespaldo(parent.getNombreCompleto());
                    log.setFecha(LocalDateTime.now());
                    log.setMonto(debt.monto);
                    log.setTipoMovimiento(FinancialLog.MovementType.PAGO_DIRECTO);
                    log.setConcepto("Uso abono: Pago de " + (cargo.getConcepto() != null ? cargo.getConcepto() : "Cargo Extra"));
                    log.setMetodoPago(FinancialLog.PaymentMethod.ABONO);
                    log.setClubId(parent.getClubId());
                    financialLogRepository.save(log);
                }
            }

            parentRepository.save(parent);
        });
    }

    /**
     * Resuelve qué matrícula (sede + PlanMensualidad) usar para repriciar una clase impaga:
     * prioriza la marcada como sede principal (Enrollment.esPrincipal), y si ninguna lo está
     * cae a la matrícula de la sede donde se tomó esa clase. Réplica de la misma resolución que
     * usa AttendanceBillingService al cobrar la clase la primera vez, para que el monto que el
     * FIFO aplica nunca diverja del que se le mostró al admin como "deuda".
     */
    private Enrollment resolveEnrollmentParaMensualidad(Attendance attendance) {
        Student student = attendance.getStudent();
        if (student == null) {
            return null;
        }
        Long sedeId = attendance.getSede() != null ? attendance.getSede().getId() : null;
        List<Enrollment> matriculasConPlan = enrollmentRepository.findByStudentIdWithPlan(student.getId());
        return matriculasConPlan.stream()
            .filter(e -> Boolean.TRUE.equals(e.getEsPrincipal()))
            .findFirst()
            .orElseGet(() -> matriculasConPlan.stream()
                .filter(e -> e.getSede() != null && sedeId != null && e.getSede().getId().equals(sedeId))
                .findFirst().orElse(null));
    }

    @Transactional
    public Long registrarAbono(Long parentId, BigDecimal monto, FinancialLog.PaymentMethod metodoPago, LocalDate fechaAbono) {
        Long[] logIdHolder = new Long[1];

        parentLockManager.executeWithLock(parentId, () -> {
            if (monto.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("El monto debe ser mayor a cero");
            }

            Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            parent.setSaldoAbono(parent.getSaldoAbono().add(monto));

            FinancialLog log = new FinancialLog();
            log.setParent(parent);
            log.setNombreClienteRespaldo(parent.getNombreCompleto());
            log.setFecha(fechaAbono.atTime(LocalTime.now()));
            log.setMonto(monto);
            log.setTipoMovimiento(FinancialLog.MovementType.INGRESO_ABONO);
            log.setMetodoPago(metodoPago);
            log.setClubId(parent.getClubId());

            log = financialLogRepository.save(log);
            logIdHolder[0] = log.getId();
            parentRepository.save(parent);
        });

        // Usar la fecha REAL del pago (posiblemente retroactiva) para decidir qué tarifa
        // (Preferencial/Actual/Mora) aplica al reconciliar la deuda de MENSUALIDAD — no "hoy".
        procesarPagosPendientes(parentId, fechaAbono);
        return logIdHolder[0];
    }

    @Transactional
    public void eliminarAbono(Long logId) {
        FinancialLog log = financialLogRepository.findById(logId)
            .orElseThrow(() -> new RuntimeException("Registro financiero no encontrado"));

        // SEC-BOLA: el registro financiero debe pertenecer al club del ADMIN autenticado —
        // antes cualquier ADMIN podía borrar el abono/pago de cualquier club adivinando el id.
        Long clubActual = SecurityUtils.getClubId();
        if (clubActual != null && (log.getClubId() == null || !log.getClubId().equals(clubActual))) {
            throw new SecurityException("Acceso denegado a este registro financiero");
        }

        if (log.getTipoMovimiento() != FinancialLog.MovementType.INGRESO_ABONO
                && log.getTipoMovimiento() != FinancialLog.MovementType.PAGO_DIRECTO) {
            throw new RuntimeException("Solo se pueden eliminar abonos o pagos directos registrados");
        }

        Long parentId = log.getParent() != null ? log.getParent().getId() : null;

        if (log.getTipoMovimiento() == FinancialLog.MovementType.PAGO_DIRECTO) {
            if (parentId != null) {
                parentLockManager.executeWithLock(parentId, () -> {
                    // Revertir exactamente la asignación de clases por estudiante que se registró
                    // en la compra (ver PackagePurchaseService), en vez de descontarle a TODOS los
                    // hermanos del padre por igual — bug que multiplicaba la deducción con 2+ hijos
                    // y podía quitarle clases a un hermano que nunca las recibió.
                    String detalles = log.getDetalles();
                    if (detalles != null && detalles.startsWith("PKG_ALLOC:")) {
                        String asignacionesTexto = detalles.substring("PKG_ALLOC:".length());
                        for (String par : asignacionesTexto.split(",")) {
                            if (par.isBlank()) continue;
                            String[] partes = par.split("=");
                            if (partes.length != 2) continue;
                            try {
                                Long studentId = Long.parseLong(partes[0].trim());
                                int clasesAQuitar = Integer.parseInt(partes[1].trim());
                                Student s = studentRepository.findById(studentId).orElse(null);
                                if (s != null) {
                                    int actuales = s.getClasesDisponibles() != null ? s.getClasesDisponibles() : 0;
                                    // Se permite negativo (deuda de clases), simétrico con el
                                    // acreditado original — solo se revierte lo realmente otorgado.
                                    s.setClasesDisponibles(actuales - clasesAQuitar);
                                    studentRepository.save(s);
                                }
                            } catch (NumberFormatException ignored) {
                                // fila corrupta puntual, no debe abortar la reversión del pago
                            }
                        }
                    }
                    financialLogRepository.delete(log);
                });
            } else {
                financialLogRepository.delete(log);
            }
            return;
        }

        if (parentId == null) {
            financialLogRepository.delete(log);
            return;
        }

        parentLockManager.executeWithLock(parentId, () -> {
            List<Attendance> asistencias = attendanceRepository.findAllByParentId(parentId);
            for (Attendance attendance : asistencias) {
                attendance.setClasePaga(false);
            }
            attendanceRepository.saveAll(asistencias);

            List<FinancialLog> usosAbono = financialLogRepository.findByParentIdAndTipoMovimiento(
                parentId,
                FinancialLog.MovementType.USO_ABONO_CLASE
            );
            financialLogRepository.deleteAll(usosAbono);

            // Los cargos extras (matrícula, seguro, clase excedente, etc.) que ya fueron pagados
            // con abono NO se marcan "pagados" en sitio — se BORRAN y se reemplazan por un
            // PAGO_DIRECTO (ver procesarPagosPendientes más abajo). Si no se revierte ese cambio
            // aquí, al eliminar el abono la deuda queda perdida para siempre en vez de volver a
            // quedar pendiente. Se restauran ANTES de recalcular el saldo, para que el reproceso
            // de FIFO decida de nuevo (con el saldo ya reducido) cuáles quedan pagas.
            String prefijoUsoAbono = "Uso abono: Pago de ";
            List<FinancialLog> pagosDirectosPorAbono = financialLogRepository.findByParentIdAndTipoMovimiento(
                parentId,
                FinancialLog.MovementType.PAGO_DIRECTO
            ).stream().filter(p -> p.getConcepto() != null && p.getConcepto().startsWith(prefijoUsoAbono)).toList();

            for (FinancialLog pago : pagosDirectosPorAbono) {
                FinancialLog cargoRestaurado = new FinancialLog();
                cargoRestaurado.setParent(pago.getParent());
                cargoRestaurado.setNombreClienteRespaldo(pago.getNombreClienteRespaldo());
                cargoRestaurado.setFecha(pago.getFecha());
                cargoRestaurado.setMonto(pago.getMonto());
                cargoRestaurado.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
                cargoRestaurado.setConcepto(pago.getConcepto().substring(prefijoUsoAbono.length()));
                cargoRestaurado.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
                cargoRestaurado.setClubId(pago.getClubId());
                financialLogRepository.save(cargoRestaurado);
                financialLogRepository.delete(pago);
            }

            financialLogRepository.delete(log);

            List<FinancialLog> ingresosRestantes = financialLogRepository.findByParentIdAndTipoMovimiento(
                parentId,
                FinancialLog.MovementType.INGRESO_ABONO
            );

            BigDecimal saldoReal = ingresosRestantes.stream()
                .map(FinancialLog::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));
            parent.setSaldoAbono(saldoReal);
            parentRepository.save(parent);
        });

        procesarPagosPendientes(parentId);
    }

    public List<FinancialLog> obtenerCargosExtrasPendientes(Long parentId) {
        return financialLogRepository.findByParentIdAndTipoMovimiento(
            parentId,
            FinancialLog.MovementType.CARGO_EXTRA
        );
    }
}
