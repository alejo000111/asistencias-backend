package com.asistencia.erp.service;

import com.asistencia.erp.dto.CompraPaqueteRequest;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.PlanMensualidad;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentComplementoRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.service.billing.AttendanceBillingService;
import com.asistencia.erp.service.billing.CashAndPaymentAllocationService;
import com.asistencia.erp.service.billing.ClubConfigResolver;
import com.asistencia.erp.service.billing.MonthlyBillingService;
import com.asistencia.erp.service.billing.PackagePurchaseService;
import com.asistencia.erp.service.billing.ParentLockManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FinancialService {

    private final ParentRepository parentRepository;
    private final AttendanceRepository attendanceRepository;
    private final FinancialLogRepository financialLogRepository;
    private final StudentRepository studentRepository;

    private final AttendanceBillingService attendanceBillingService;
    private final MonthlyBillingService monthlyBillingService;
    private final PackagePurchaseService packagePurchaseService;
    private final CashAndPaymentAllocationService cashAndPaymentAllocationService;
    private final ClubConfigResolver clubConfigResolver;
    private final ParentLockManager parentLockManager;
    private final StudentComplementoRepository studentComplementoRepository;

    public enum TipoClase {
        GRUPAL,
        PERSONALIZADA
    }

    @Transactional
    public void procesarPagosPendientes(Long parentId) {
        cashAndPaymentAllocationService.procesarPagosPendientes(parentId);
    }

    @Transactional
    public Long registrarAbono(Long parentId,
                               BigDecimal monto,
                               FinancialLog.PaymentMethod metodoPago,
                               java.time.LocalDate fechaAbono) {
        return cashAndPaymentAllocationService.registrarAbono(parentId, monto, metodoPago, fechaAbono);
    }

    @Transactional
    public void registrarCompraPaquete(CompraPaqueteRequest req) {
        packagePurchaseService.registrarCompraPaquete(req);
    }

    @Transactional
    public com.asistencia.erp.entity.Attendance registrarAsistencia(Long studentId,
                                                                    TipoClase tipoClase,
                                                                    String nivel,
                                                                    String fecha,
                                                                    BigDecimal precioPersonalizado,
                                                                    Long sedeId,
                                                                    Boolean esClaseSuelta) {
        com.asistencia.erp.entity.Attendance asistencia = attendanceBillingService.registrarAsistencia(
            studentId,
            tipoClase,
            nivel,
            fecha,
            precioPersonalizado,
            sedeId,
            esClaseSuelta
        );
        Long parentId = asistencia.getStudent() != null && asistencia.getStudent().getParent() != null
                ? asistencia.getStudent().getParent().getId() : null;
        if (parentId != null) {
            cashAndPaymentAllocationService.procesarPagosPendientes(parentId);
        }
        return asistencia;
    }

    @Transactional
    public void eliminarFamilia(Long parentId) {
        parentLockManager.executeWithLock(parentId, () -> {
            Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + parentId));

            if (!"INACTIVO".equals(parent.getEstado())) {
                throw new RuntimeException("Debes marcar la familia como INACTIVA antes de poder eliminarla.");
            }

            if (parent.getStudents() != null) {
                for (Student student : parent.getStudents()) {
                    Long sid = student.getId();
                    student.getMatriculas().clear();
                    attendanceRepository.orphanAttendancesByStudentId(sid);
                    studentRepository.save(student);
                }
            }

            List<FinancialLog> logs = financialLogRepository.findByParentIdOrderByFechaDesc(parentId);
            for (FinancialLog log : logs) {
                if (log.getNombreClienteRespaldo() == null || log.getNombreClienteRespaldo().isBlank()) {
                    log.setNombreClienteRespaldo(parent.getNombreCompleto());
                }
                log.setParent(null);
                financialLogRepository.save(log);
            }

            studentRepository.flush();

            if (parent.getStudents() != null) {
                for (Student student : parent.getStudents()) {
                    studentRepository.delete(student);
                }
            }

            parentRepository.delete(parent);
        });
    }

    @Transactional
    public void eliminarDeportista(Long studentId) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));

        attendanceRepository.orphanAttendancesByStudentId(studentId);
        student.getMatriculas().clear();
        studentRepository.delete(student);
    }

    /**
     * Elimina un prospecto de cortesía (estado CORTESIA) por completo, incluida su
     * asistencia de cortesía (cascada vía Student.attendances). A diferencia de
     * eliminarDeportista, no requiere marcar la familia como INACTIVA primero — un
     * prospecto que no se va a inscribir se descarta directamente. Si el acudiente
     * se quedó sin más deportistas, también se elimina (se creó solo para esta cortesía).
     */
    @Transactional
    public void eliminarCortesia(Long studentId) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));

        Long clubActual = com.asistencia.erp.security.SecurityUtils.getClubId();
        if (student.getClubId() == null || !student.getClubId().equals(clubActual)) {
            throw new RuntimeException("Acceso denegado a este deportista");
        }
        if (student.getEstado() != Student.StudentStatus.CORTESIA) {
            throw new RuntimeException("Solo se pueden eliminar así los prospectos de cortesía sin matricular.");
        }

        Parent parent = student.getParent();
        studentRepository.delete(student);

        if (parent != null) {
            long restantes = studentRepository.findByParentId(parent.getId()).size();
            if (restantes == 0) {
                parentRepository.delete(parent);
            }
        }
    }

    @Transactional
    public void procesarCargosParaTodosLosEstudiantes(ClubConfig config) {
        if (config == null || config.getAdmin() == null) {
            return;
        }

        List<Student> estudiantes = studentRepository.findAllWithFetch(config.getAdmin().getClubId());
        for (Student estudiante : estudiantes) {
            if (estudiante.getEstado() == Student.StudentStatus.ACTIVO) {
                aplicarCargosAEstudiante(estudiante, config);
            }
        }
    }

    @Transactional
    public void procesarCargosObligatoriosYExtras(Student student) {
        if (student == null || student.getId() == null) {
            return;
        }

        Student persisted = studentRepository.findById(student.getId()).orElse(null);
        if (persisted == null || persisted.getParent() == null) {
            return;
        }

        ClubConfig config = resolverConfigParaEstudiante(persisted);
        if (config == null) {
            return;
        }

        aplicarCargosAEstudiante(persisted, config);
    }

    public List<FinancialLog> obtenerCargosExtrasPendientes(Long parentId) {
        return cashAndPaymentAllocationService.obtenerCargosExtrasPendientes(parentId);
    }

    @Transactional
    void aplicarCargosAEstudiante(Student student, ClubConfig config) {
        if (student == null || student.getParent() == null || config == null) return;
        Parent parent = student.getParent();
        if (parent.getId() == null) return;

        String conceptoMatricula = "Matrícula Anual - " + student.getNombreCompleto();

        if (Boolean.TRUE.equals(config.getCobraMatricula())) {
            boolean debeCobrarMatricula = Boolean.TRUE.equals(config.getMatriculaObligatoria()) ||
                    Boolean.TRUE.equals(student.getAdquiereMatricula());

            if (debeCobrarMatricula) {
                boolean vigenciaOk = true;
                if (config.getFechaVigenciaMatricula() != null && !config.getFechaVigenciaMatricula().trim().isEmpty()) {
                    try {
                        java.time.LocalDate f = java.time.LocalDate.parse(config.getFechaVigenciaMatricula().trim());
                        if (java.time.LocalDate.now().isBefore(f)) vigenciaOk = false;
                    } catch (Exception ignored) {}
                }

                if (vigenciaOk && config.getMontoMatricula() != null && config.getMontoMatricula().compareTo(BigDecimal.ZERO) > 0) {
                    boolean yaExiste = !financialLogRepository
                        .findByParentIdAndTipoMovimientoAndConceptoContaining(
                            parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, conceptoMatricula)
                        .isEmpty();

                    if (!yaExiste) {
                        FinancialLog logMat = new FinancialLog();
                        logMat.setParent(parent);
                        logMat.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
                        logMat.setMonto(config.getMontoMatricula());
                        logMat.setConcepto(conceptoMatricula);
                        logMat.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
                        logMat.setFecha(LocalDateTime.now());
                        logMat.setClubId(parent.getClubId());
                        financialLogRepository.save(logMat);
                    }
                }
            } else {
                financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), conceptoMatricula);
            }
        } else {
            financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), conceptoMatricula);
        }

        String conceptoSeguro = "Seguro Deportivo - " + student.getNombreCompleto();

        if (Boolean.TRUE.equals(config.getCobraSeguro())) {
            boolean debeCobrarSeguro = Boolean.TRUE.equals(config.getSeguroObligatorio()) ||
                    Boolean.TRUE.equals(student.getAdquiereSeguro());

            if (debeCobrarSeguro) {
                boolean vigenciaOk = true;
                if (config.getFechaVigenciaSeguro() != null && !config.getFechaVigenciaSeguro().trim().isEmpty()) {
                    try {
                        java.time.LocalDate f = java.time.LocalDate.parse(config.getFechaVigenciaSeguro().trim());
                        if (java.time.LocalDate.now().isBefore(f)) vigenciaOk = false;
                    } catch (Exception ignored) {}
                }

                if (vigenciaOk && config.getMontoSeguro() != null && config.getMontoSeguro().compareTo(BigDecimal.ZERO) > 0) {
                    boolean yaExiste = !financialLogRepository
                        .findByParentIdAndTipoMovimientoAndConceptoContaining(
                            parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, conceptoSeguro)
                        .isEmpty();

                    if (!yaExiste) {
                        FinancialLog logSeg = new FinancialLog();
                        logSeg.setParent(parent);
                        logSeg.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
                        logSeg.setMonto(config.getMontoSeguro());
                        logSeg.setConcepto(conceptoSeguro);
                        logSeg.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
                        logSeg.setFecha(LocalDateTime.now());
                        logSeg.setClubId(parent.getClubId());
                        financialLogRepository.save(logSeg);
                    }
                }
            } else {
                financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), conceptoSeguro);
            }
        } else {
            financialLogRepository.eliminarCargoExtraPorConcepto(parent.getId(), conceptoSeguro);
        }

        aplicarCargosComplementosAEstudiante(student, parent);
    }

    /**
     * Genera un cargo mensual itemizado por cada Complemento activo del estudiante
     * (Gym Virtual, Pista Adicional, etc.), idempotente por mes (el concepto incluye
     * mes y año, igual que la mensualidad se cobra una vez por mes vía asistencia).
     */
    private void aplicarCargosComplementosAEstudiante(Student student, Parent parent) {
        List<com.asistencia.erp.entity.StudentComplemento> asignaciones =
            studentComplementoRepository.findActivosByStudentId(student.getId());
        if (asignaciones.isEmpty()) return;

        String mesAnio = monthlyBillingService.mesActualEnEspanol() + " " + LocalDateTime.now().getYear();

        for (com.asistencia.erp.entity.StudentComplemento asignacion : asignaciones) {
            String concepto = asignacion.getComplemento().getNombre() + " - " + mesAnio + " - " + student.getNombreCompleto();
            boolean yaExiste = !financialLogRepository
                .findByParentIdAndTipoMovimientoAndConceptoContaining(parent.getId(), FinancialLog.MovementType.CARGO_EXTRA, concepto)
                .isEmpty();
            if (yaExiste) continue;

            BigDecimal precio = monthlyBillingService.resolverPrecioComplemento(asignacion);
            if (precio == null || precio.compareTo(BigDecimal.ZERO) <= 0) continue;

            FinancialLog log = new FinancialLog();
            log.setParent(parent);
            log.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
            log.setMonto(precio);
            log.setConcepto(concepto);
            log.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);
            log.setFecha(LocalDateTime.now());
            log.setClubId(parent.getClubId());
            financialLogRepository.save(log);
        }
    }

    @Transactional
    public void eliminarAbono(Long logId) {
        cashAndPaymentAllocationService.eliminarAbono(logId);
    }

    public ClubConfig resolverConfigParaPadre(Parent parent) {
        return clubConfigResolver.resolveForParent(parent);
    }

    public BigDecimal calcularPrecioMensualidad(ClubConfig config, Long sedeId, LocalDateTime fechaClase) {
        return monthlyBillingService.calculateMonthlyPrice(config, sedeId, fechaClase);
    }

    /** Igual a calcularPrecioMensualidad, pero usa el PlanMensualidad propio de la matrícula si tiene uno asignado. */
    public BigDecimal calcularPrecioMensualidadPorMatricula(Enrollment enrollment, ClubConfig config, LocalDateTime fechaClase) {
        return monthlyBillingService.calculateMonthlyPriceForEnrollment(enrollment, config, fechaClase);
    }

    /**
     * Recalcula la tarifa "vigente hoy" de una deuda de clase (asistencia MENSUALIDAD no
     * pagada), usando el PlanMensualidad propio de la sede del estudiante si tiene uno
     * asignado (fallback al esquema legado ClubConfig/TarifaSede si no). Se usa para
     * mostrar a admin/padres cuánto deben pagar HOY por una clase pendiente — no modifica
     * el registro histórico de Attendance.precioCobrado en base de datos.
     */
    public BigDecimal calcularPrecioMensualidadDeudaVigente(Student student, ClubConfig config, com.asistencia.erp.entity.Attendance attendance) {
        Long sedeId = attendance.getSede() != null ? attendance.getSede().getId() : null;
        Enrollment enrollment = (student != null && student.getMatriculas() != null && sedeId != null)
                ? student.getMatriculas().stream()
                    .filter(m -> m.getSede() != null && m.getSede().getId().equals(sedeId))
                    .findFirst().orElse(null)
                : null;
        // Solo se usa la ruta "por matrícula" cuando hay un plan propio asignado; si no,
        // se conserva el fallback legado (ClubConfig/TarifaSede) resuelto por sedeId, para
        // no perder el precio diferenciado por sede de un estudiante sin plan asignado.
        if (enrollment != null && enrollment.getPlanMensualidad() != null) {
            return calcularPrecioMensualidadPorMatricula(enrollment, config, attendance.getFecha());
        }
        return calcularPrecioMensualidad(config, sedeId, attendance.getFecha());
    }

    private ClubConfig resolverConfigParaEstudiante(Student student) {
        return clubConfigResolver.resolveForStudent(student);
    }

    /**
     * Regla de negocio (cada mes es independiente y completo) al cambiar el PlanMensualidad de
     * la matrícula PRINCIPAL de un deportista (la que gobierna su cobro mensual):
     * - Si la mensualidad del mes en curso ya se pagó bajo el plan anterior y el nuevo plan es
     *   más caro, se genera un CARGO_EXTRA por la diferencia (ya pagó una parte, debe completar
     *   hasta el valor del plan nuevo).
     * - Si el nuevo plan es más barato, la diferencia queda como saldo a favor (INGRESO_ABONO),
     *   utilizable de inmediato contra cualquier otra deuda del padre.
     * - Si la mensualidad del mes AÚN NO se ha pagado, no hace falta ajuste aquí: tanto la
     *   pantalla de "Deudas" como la aplicación real de un abono (FIFO) ya recalculan el precio
     *   contra el plan VIGENTE de la matrícula al momento de cobrar/pagar.
     * - No aplica a matrículas secundarias (solo la principal factura la mensualidad).
     *
     * @return true si se generó un cargo o abono de ajuste.
     */
    @Transactional
    public boolean reconciliarCambioDePlanMensualidad(Enrollment enrollment, PlanMensualidad planAnterior) {
        if (enrollment == null || !Boolean.TRUE.equals(enrollment.getEsPrincipal())) {
            return false;
        }
        Student student = enrollment.getStudent();
        if (student == null || student.getParent() == null) {
            return false;
        }
        PlanMensualidad planNuevo = enrollment.getPlanMensualidad();
        if (java.util.Objects.equals(planAnterior != null ? planAnterior.getId() : null, planNuevo != null ? planNuevo.getId() : null)) {
            return false; // el plan no cambió realmente
        }

        ClubConfig config = clubConfigResolver.resolveForStudent(student);
        if (config == null || config.getEsquemaCobro() != ClubConfig.EsquemaCobro.MENSUALIDAD) {
            return false;
        }

        LocalDateTime ahora = LocalDateTime.now();
        List<Attendance> cobradasEsteMes = attendanceRepository.findChargedThisMonth(student.getId(), ahora.getYear(), ahora.getMonthValue());
        if (cobradasEsteMes.isEmpty()) {
            return false; // aún no se ha cobrado nada este mes — nada que reconciliar
        }

        Attendance cargoDelMes = cobradasEsteMes.get(0);
        if (!Boolean.TRUE.equals(cargoDelMes.getClasePaga())) {
            return false; // sigue pendiente: el recálculo de deuda/FIFO ya usará el plan nuevo automáticamente
        }

        BigDecimal precioYaPagado = cargoDelMes.getPrecioCobrado();
        BigDecimal precioNuevo = monthlyBillingService.calculateMonthlyPriceForEnrollment(enrollment, config, ahora);
        if (precioYaPagado == null || precioNuevo == null) {
            return false;
        }
        BigDecimal diferencia = precioNuevo.subtract(precioYaPagado);
        if (diferencia.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }

        Parent parent = student.getParent();
        String nombrePlanAnterior = planAnterior != null ? planAnterior.getNombre() : "Tarifa general";
        String nombrePlanNuevo = planNuevo != null ? planNuevo.getNombre() : "Tarifa general";
        String descripcionCambio = nombrePlanAnterior + " → " + nombrePlanNuevo;

        FinancialLog log = new FinancialLog();
        log.setParent(parent);
        log.setNombreClienteRespaldo(parent.getNombreCompleto());
        log.setFecha(ahora);
        log.setClubId(student.getClubId());
        log.setMetodoPago(FinancialLog.PaymentMethod.EFECTIVO);

        if (diferencia.compareTo(BigDecimal.ZERO) > 0) {
            log.setMonto(diferencia);
            log.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);
            log.setConcepto("Ajuste por cambio de plan (" + descripcionCambio + ") - " + student.getNombreCompleto());
            financialLogRepository.save(log);
        } else {
            BigDecimal credito = diferencia.abs();
            log.setMonto(credito);
            log.setTipoMovimiento(FinancialLog.MovementType.INGRESO_ABONO);
            log.setConcepto("Saldo a favor por cambio de plan (" + descripcionCambio + ") - " + student.getNombreCompleto());
            financialLogRepository.save(log);

            parent.setSaldoAbono((parent.getSaldoAbono() != null ? parent.getSaldoAbono() : BigDecimal.ZERO).add(credito));
            parentRepository.save(parent);
        }

        return true;
    }
}
