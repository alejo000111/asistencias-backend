package com.asistencia.erp.service;

import com.asistencia.erp.dto.CompraPaqueteRequest;
import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.CompraPaquete;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Paquete;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.CompraPaqueteRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.PaqueteRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.StudentComplementoRepository;
import com.asistencia.erp.service.billing.AttendanceBillingService;
import com.asistencia.erp.service.billing.BillingSupportUtils;
import com.asistencia.erp.service.billing.CashAndPaymentAllocationService;
import com.asistencia.erp.service.billing.ClubConfigResolver;
import com.asistencia.erp.service.billing.MonthlyBillingService;
import com.asistencia.erp.service.billing.PackagePurchaseService;
import com.asistencia.erp.service.billing.ParentLockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialServiceConcurrencyTest {

    @Mock
    private ParentRepository parentRepository;
    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private FinancialLogRepository financialLogRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private SedeRepository sedeRepository;
    @Mock
    private PaqueteRepository paqueteRepository;
    @Mock
    private CompraPaqueteRepository compraPaqueteRepository;
    @Mock
    private ClubConfigResolver clubConfigResolver;
    @Mock
    private MonthlyBillingService monthlyBillingService;
    @Mock
    private ParentLockManager parentLockManager;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private EnrollmentRepository enrollmentRepository;
    @Mock
    private StudentComplementoRepository studentComplementoRepository;

    private AttendanceBillingService attendanceBillingService;
    private CashAndPaymentAllocationService cashService;
    private PackagePurchaseService packagePurchaseService;

    @BeforeEach
    void setUp() {
        attendanceBillingService = new AttendanceBillingService(
            studentRepository,
            sedeRepository,
            attendanceRepository,
            clubConfigResolver,
            monthlyBillingService,
            new BillingSupportUtils(),
            appUserRepository,
            enrollmentRepository,
            studentComplementoRepository,
            financialLogRepository
        );
        ReflectionTestUtils.setField(attendanceBillingService, "precioGrupal", new BigDecimal("40000"));
        ReflectionTestUtils.setField(attendanceBillingService, "precioPersonalizada", new BigDecimal("50000"));

        cashService = new CashAndPaymentAllocationService(
            parentRepository,
            studentRepository,
            attendanceRepository,
            financialLogRepository,
            clubConfigResolver,
            monthlyBillingService,
            parentLockManager,
            enrollmentRepository
        );

        packagePurchaseService = new PackagePurchaseService(
            parentRepository,
            paqueteRepository,
            compraPaqueteRepository,
            studentRepository,
            sedeRepository,
            financialLogRepository,
            clubConfigResolver,
            parentLockManager
        );

        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(parentLockManager).executeWithLock(any(Long.class), any(Runnable.class));
    }

    @Test
    void mensualidadNoTocaFlujoDePaquetes() {
        Parent parent = new Parent();
        parent.setId(10L);

        Student student = new Student();
        student.setId(1L);
        student.setParent(parent);
        student.setClasesDisponibles(8);
        student.setNombreCompleto("Alumno Mensual");

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(new BigDecimal("120000"));

        attendanceBillingService.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, null, null);

        verify(monthlyBillingService).resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config));
        verify(studentRepository, never()).save(student);
    }

    @Test
    void asistenciaEnPaqueteNoUsaLogicaMensualidad() {
        Parent parent = new Parent();
        parent.setId(11L);

        Student student = new Student();
        student.setId(2L);
        student.setParent(parent);
        student.setClasesDisponibles(3);
        student.setNombreCompleto("Alumno Paquete");

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.PAQUETE);

        when(studentRepository.findById(2L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(studentRepository.save(any(Student.class))).thenAnswer(a -> a.getArgument(0));

        attendanceBillingService.registrarAsistencia(2L, FinancialService.TipoClase.GRUPAL, "B", null, null, null, null);

        verify(monthlyBillingService, never()).resolveAttendanceMonthlyChargeForEnrollment(any(), any(), any(), any());
        verify(studentRepository, atLeast(1)).save(any(Student.class));
    }

    @Test
    void compraPaqueteAcreditaClasesYNoAfectaAsistencias() {
        Parent parent = new Parent();
        parent.setId(20L);
        parent.setNombreCompleto("Padre Paquete");
        parent.setClubId(200L);

        Student student = new Student();
        student.setId(30L);
        student.setParent(parent);
        student.setClasesDisponibles(1);

        // El paqueteId ya no se resuelve contra la tabla legacy `paquetes` (colisionaba con los
        // ids "virtuales" que PaqueteController genera desde el JSON de configuración del club):
        // nombrePaquete/clasesIncluidas/precio, tal como los envía siempre el frontend real, son
        // ahora la única fuente de verdad.
        CompraPaqueteRequest req = new CompraPaqueteRequest();
        req.setParentId(20L);
        req.setNombrePaquete("Pack 8");
        req.setClasesIncluidas(8);
        req.setPrecio(new BigDecimal("180000"));
        req.setMetodoPago("TRANSFERENCIA");

        when(parentRepository.findById(20L)).thenReturn(Optional.of(parent));
        when(studentRepository.findByParentId(20L)).thenReturn(List.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(a -> a.getArgument(0));
        when(compraPaqueteRepository.save(any(CompraPaquete.class))).thenAnswer(a -> a.getArgument(0));
        when(financialLogRepository.save(any(FinancialLog.class))).thenAnswer(a -> a.getArgument(0));

        packagePurchaseService.registrarCompraPaquete(req);

        assertEquals(9, student.getClasesDisponibles());
        verify(attendanceRepository, never()).save(any());

        ArgumentCaptor<FinancialLog> logCaptor = ArgumentCaptor.forClass(FinancialLog.class);
        verify(financialLogRepository).save(logCaptor.capture());
        assertTrue(logCaptor.getValue().getConcepto().contains("Pack 8"));
    }

    @Test
    void abonosCajaAplicaATodosLosFlujosYGuardaMetodoPago() {
        Parent parent = new Parent();
        parent.setId(42L);
        parent.setNombreCompleto("Padre Caja");
        parent.setClubId(420L);
        parent.setSaldoAbono(BigDecimal.ZERO);

        Attendance deudaAsistencia = new Attendance();
        deudaAsistencia.setId(1L);
        deudaAsistencia.setPrecioCobrado(new BigDecimal("50"));
        deudaAsistencia.setClasePaga(false);
        deudaAsistencia.setFecha(LocalDateTime.now().minusDays(2));

        FinancialLog cargoExtra = new FinancialLog();
        cargoExtra.setId(2L);
        cargoExtra.setFecha(LocalDateTime.now().minusDays(1));
        cargoExtra.setMonto(new BigDecimal("20"));
        cargoExtra.setConcepto("Paquete: Pack 4");
        cargoExtra.setTipoMovimiento(FinancialLog.MovementType.CARGO_EXTRA);

        when(parentRepository.findById(42L)).thenReturn(Optional.of(parent));
        when(attendanceRepository.findUnpaidAttendancesByParentIdFIFO(42L)).thenReturn(List.of(deudaAsistencia));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(a -> a.getArgument(0));
        when(financialLogRepository.findByParentIdAndTipoMovimiento(eq(42L), eq(FinancialLog.MovementType.CARGO_EXTRA)))
            .thenReturn(new ArrayList<>(List.of(cargoExtra)));
        when(financialLogRepository.save(any(FinancialLog.class))).thenAnswer(a -> a.getArgument(0));

        cashService.registrarAbono(42L, new BigDecimal("70"), FinancialLog.PaymentMethod.TRANSFERENCIA, LocalDate.now());

        assertTrue(deudaAsistencia.getClasePaga());
        assertEquals(0, parent.getSaldoAbono().compareTo(BigDecimal.ZERO));
        verify(financialLogRepository).delete(cargoExtra);

        ArgumentCaptor<FinancialLog> logCaptor = ArgumentCaptor.forClass(FinancialLog.class);
        verify(financialLogRepository, atLeast(3)).save(logCaptor.capture());
        List<FinancialLog> savedLogs = logCaptor.getAllValues();

        boolean ingresoConMetodo = savedLogs.stream().anyMatch(log ->
            log.getTipoMovimiento() == FinancialLog.MovementType.INGRESO_ABONO
                && log.getMetodoPago() == FinancialLog.PaymentMethod.TRANSFERENCIA
        );
        boolean usoAbonoAsistencia = savedLogs.stream().anyMatch(log ->
            log.getTipoMovimiento() == FinancialLog.MovementType.USO_ABONO_CLASE
                && log.getMetodoPago() == FinancialLog.PaymentMethod.ABONO
        );
        boolean pagoDirectoCargo = savedLogs.stream().anyMatch(log ->
            log.getTipoMovimiento() == FinancialLog.MovementType.PAGO_DIRECTO
                && log.getMetodoPago() == FinancialLog.PaymentMethod.ABONO
                && log.getConcepto() != null
                && log.getConcepto().contains("Paquete")
        );

        assertTrue(ingresoConMetodo);
        assertTrue(usoAbonoAsistencia);
        assertTrue(pagoDirectoCargo);
        assertFalse(savedLogs.isEmpty());
    }
}
