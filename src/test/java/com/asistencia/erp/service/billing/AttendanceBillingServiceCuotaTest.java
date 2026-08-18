package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.Escenario;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.PlanCupo;
import com.asistencia.erp.entity.PlanMensualidad;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentComplementoRepository;
import com.asistencia.erp.repository.StudentRepository;
import com.asistencia.erp.service.FinancialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre la evaluación de cupos por Escenario (cancha/pista/gym) y la generación del
 * CARGO_EXTRA cuando un deportista supera su cuota (MENSUALIDAD) o se queda sin clases
 * disponibles (PAQUETE) — ver AttendanceBillingService.evaluarCuota / registrarAsistencia.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceBillingServiceCuotaTest {

    @Mock private StudentRepository studentRepository;
    @Mock private SedeRepository sedeRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private ClubConfigResolver clubConfigResolver;
    @Mock private MonthlyBillingService monthlyBillingService;
    @Mock private AppUserRepository appUserRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentComplementoRepository studentComplementoRepository;
    @Mock private FinancialLogRepository financialLogRepository;

    private AttendanceBillingService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceBillingService(
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
        ReflectionTestUtils.setField(service, "precioGrupal", new BigDecimal("40000"));
        ReflectionTestUtils.setField(service, "precioPersonalizada", new BigDecimal("50000"));

        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(a -> a.getArgument(0));
        lenient().when(studentRepository.save(any(Student.class))).thenAnswer(a -> a.getArgument(0));
        lenient().when(financialLogRepository.save(any(FinancialLog.class))).thenAnswer(a -> a.getArgument(0));
        lenient().when(studentComplementoRepository.findActivosByStudentIdAndEscenarioId(anyLong(), anyLong()))
            .thenReturn(List.of());
    }

    private Escenario escenario(Long id, String nombre) {
        Escenario e = new Escenario();
        e.setId(id);
        e.setClubId(1L);
        e.setNombre(nombre);
        e.setPeriodo(Escenario.Periodo.SEMANAL);
        e.setActivo(true);
        return e;
    }

    private Sede sedeConEscenario(Long sedeId, Escenario escenario) {
        Sede sede = new Sede();
        sede.setId(sedeId);
        sede.setClubId(1L);
        sede.setNombre("Sede " + sedeId);
        sede.setEscenario(escenario);
        return sede;
    }

    private Student estudianteConCupo(Sede sede, Escenario escenario, int cupo) {
        Parent parent = new Parent();
        parent.setId(500L);
        parent.setNombreCompleto("Padre Test");

        PlanCupo planCupo = new PlanCupo();
        planCupo.setEscenario(escenario);
        planCupo.setCantidad(cupo);

        PlanMensualidad plan = new PlanMensualidad();
        plan.setCupos(List.of(planCupo));

        Enrollment enrollment = new Enrollment();
        enrollment.setSede(sede);
        enrollment.setPlanMensualidad(plan);
        enrollment.setEsPrincipal(true);

        Student student = new Student();
        student.setId(1L);
        student.setParent(parent);
        student.setNombreCompleto("Deportista Test");
        student.setMatriculas(new ArrayList<>(List.of(enrollment)));
        student.setClubId(1L);

        lenient().when(enrollmentRepository.findByStudentIdWithPlan(1L)).thenReturn(List.of(enrollment));
        return student;
    }

    // ─── MENSUALIDAD: cupo de escenario (cancha/pista/gym) ───

    @Test
    void mensualidad_dentroDeCupo_noGeneraCargoNiFueraDePlan() {
        Escenario cancha = escenario(10L, "Cancha");
        Sede sede = sedeConEscenario(100L, cancha);
        Student student = estudianteConCupo(sede, cancha, 2);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(100L)).thenReturn(Optional.of(sede));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);
        // Ya lleva 1 asistencia esta semana en Cancha; cupo es 2, así que la 2ª sigue dentro.
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(eq(1L), eq(10L), any(), any()))
            .thenReturn(1L);

        Attendance asistencia = service.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, 100L, false);

        assertFalse(Boolean.TRUE.equals(asistencia.getFueraDePlan()));
        verify(financialLogRepository, never()).save(any(FinancialLog.class));
    }

    @Test
    void mensualidad_terceraClaseEnCancha_superaCupoDe2_generaCargoYFueraDePlan() {
        Escenario cancha = escenario(10L, "Cancha");
        Sede sede = sedeConEscenario(100L, cancha);
        Student student = estudianteConCupo(sede, cancha, 2);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);
        config.setPrecioClaseGrupal(new BigDecimal("15000"));

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(100L)).thenReturn(Optional.of(sede));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);
        // Ya lleva 2 asistencias esta semana en Cancha; la 3ª supera el cupo de 2.
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(eq(1L), eq(10L), any(), any()))
            .thenReturn(2L);

        Attendance asistencia = service.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, 100L, false);

        assertTrue(asistencia.getFueraDePlan());
        assertTrue(asistencia.getMotivoFueraDePlan().contains("Cancha"));
        assertTrue(asistencia.getMotivoFueraDePlan().contains("cobro extra"));

        ArgumentCaptor<FinancialLog> logCaptor = ArgumentCaptor.forClass(FinancialLog.class);
        verify(financialLogRepository).save(logCaptor.capture());
        FinancialLog log = logCaptor.getValue();
        assertEquals(FinancialLog.MovementType.CARGO_EXTRA, log.getTipoMovimiento());
        assertEquals(0, new BigDecimal("15000").compareTo(log.getMonto()));
    }

    @Test
    void mensualidad_terceraClaseEnPista_superaCupoDe2_generaCargo() {
        Escenario pista = escenario(11L, "Pista");
        Sede sede = sedeConEscenario(101L, pista);
        Student student = estudianteConCupo(sede, pista, 2);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);
        config.setPrecioClaseGrupal(new BigDecimal("15000"));

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(101L)).thenReturn(Optional.of(sede));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(eq(1L), eq(11L), any(), any()))
            .thenReturn(2L);

        Attendance asistencia = service.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, 101L, false);

        assertTrue(asistencia.getFueraDePlan());
        verify(financialLogRepository).save(any(FinancialLog.class));
    }

    @Test
    void mensualidad_terceraClaseEnGym_superaCupoDe2_generaCargo() {
        Escenario gym = escenario(12L, "Gimnasio");
        Sede sede = sedeConEscenario(102L, gym);
        Student student = estudianteConCupo(sede, gym, 2);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);
        config.setPrecioClaseGrupal(new BigDecimal("15000"));

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(102L)).thenReturn(Optional.of(sede));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(eq(1L), eq(12L), any(), any()))
            .thenReturn(2L);

        Attendance asistencia = service.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, 102L, false);

        assertTrue(asistencia.getFueraDePlan());
        verify(financialLogRepository).save(any(FinancialLog.class));
    }

    @Test
    void porClase_superaCupo_marcaFueraDePlanPeroNoGeneraCargoExtra() {
        // POR_CLASE ya cobra la clase completa (deuda = precioCobrado); no debe duplicarse
        // con un CARGO_EXTRA adicional al superar el cupo informativo del escenario.
        Escenario cancha = escenario(10L, "Cancha");
        Sede sede = sedeConEscenario(100L, cancha);
        Student student = estudianteConCupo(sede, cancha, 2);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.POR_CLASE);
        config.setPrecioClaseGrupal(new BigDecimal("15000"));

        when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(100L)).thenReturn(Optional.of(sede));
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndFechaBetween(eq(1L), eq(10L), any(), any()))
            .thenReturn(2L);

        Attendance asistencia = service.registrarAsistencia(1L, FinancialService.TipoClase.GRUPAL, "A", null, null, 100L, false);

        assertTrue(asistencia.getFueraDePlan());
        assertFalse(asistencia.getClasePaga()); // la clase misma ya queda como deuda completa
        verify(financialLogRepository, never()).save(any(FinancialLog.class));
    }

    // ─── Complemento atado a un grupo específico ───

    @Test
    void complementoDeGrupo_cuentaSoloContraAsistenciasDeEseGrupo() {
        Escenario pista = escenario(13L, "Pista");
        Sede sede = sedeConEscenario(103L, pista);

        Parent parent = new Parent();
        parent.setId(700L);

        Student student = new Student();
        student.setId(5L);
        student.setParent(parent);
        student.setNombreCompleto("Deportista Complemento");
        student.setMatriculas(new ArrayList<>());
        student.setClubId(1L);

        com.asistencia.erp.entity.Complemento complemento = new com.asistencia.erp.entity.Complemento();
        complemento.setId(900L);
        complemento.setNombre("Pista Selección");
        complemento.setEscenario(pista);
        complemento.setGrupoNombre("Selección");
        complemento.setVecesPorPeriodo(1);

        com.asistencia.erp.entity.StudentComplemento asignacion = new com.asistencia.erp.entity.StudentComplemento();
        asignacion.setComplemento(complemento);
        asignacion.setSedeOrigen(sede);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);
        config.setPrecioClaseGrupal(new BigDecimal("15000"));

        when(studentRepository.findById(5L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(103L)).thenReturn(Optional.of(sede));
        when(enrollmentRepository.findByStudentIdWithPlan(5L)).thenReturn(List.of());
        when(studentComplementoRepository.findActivosByStudentIdAndEscenarioId(5L, 13L)).thenReturn(List.of(asignacion));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);
        // Ya lleva 1 clase de "Selección" esta semana (tope 1); esta 2ª clase del mismo
        // grupo supera la cuota del complemento.
        when(attendanceRepository.countByStudentIdAndEscenarioIdAndNivelAndFechaBetween(eq(5L), eq(13L), eq("Selección"), any(), any()))
            .thenReturn(1L);

        Attendance asistencia = service.registrarAsistencia(5L, FinancialService.TipoClase.GRUPAL, "Selección", null, null, 103L, false);

        assertTrue(asistencia.getFueraDePlan());
        assertTrue(asistencia.getMotivoFueraDePlan().contains("Pista Selección"));
        verify(financialLogRepository).save(any(FinancialLog.class));
        // El conteo del escenario general (sin grupo) no debe consultarse: la única regla activa es la del grupo.
        verify(attendanceRepository, never()).countByStudentIdAndEscenarioIdAndFechaBetween(anyLong(), anyLong(), any(), any());
    }

    // ─── PAQUETE: sin clases disponibles ───

    @Test
    void paquete_conClasesDisponibles_noGeneraCargo() {
        Student student = new Student();
        student.setId(2L);
        student.setParent(new Parent());
        student.setClasesDisponibles(3);
        student.setNombreCompleto("Alumno Paquete");
        student.setClubId(1L);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.PAQUETE);

        when(studentRepository.findById(2L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);

        service.registrarAsistencia(2L, FinancialService.TipoClase.GRUPAL, "B", null, null, null, null);

        assertEquals(2, student.getClasesDisponibles());
        verify(financialLogRepository, never()).save(any(FinancialLog.class));
    }

    @Test
    void paquete_sinClasesDisponibles_soloQuedaEnDeudaDeClasesSinCargoMonetario() {
        // PAQUETE se contabiliza EXCLUSIVAMENTE en clases: exceder el saldo debe dejarlo
        // negativo (deuda de clases) SIN generar ningún CARGO_EXTRA en dinero — ese "precio de
        // respaldo" era el comportamiento legacy que la documentación del producto marca como
        // eliminado (cobraba dos veces la misma clase: negativo + cargo monetario).
        Sede sede = new Sede();
        sede.setId(200L);
        sede.setClubId(1L);
        sede.setNombre("Sede Única");

        Parent parent = new Parent();
        parent.setId(600L);

        Student student = new Student();
        student.setId(3L);
        student.setParent(parent);
        student.setClasesDisponibles(0);
        student.setNombreCompleto("Alumno Sin Paquete");
        student.setClubId(1L);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.PAQUETE);
        config.setPrecioClaseGrupal(new BigDecimal("12000"));

        when(studentRepository.findById(3L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        when(sedeRepository.findById(200L)).thenReturn(Optional.of(sede));

        Attendance asistencia = service.registrarAsistencia(3L, FinancialService.TipoClase.GRUPAL, "B", null, null, 200L, false);

        assertEquals(-1, student.getClasesDisponibles());
        assertTrue(asistencia.getFueraDePlan());
        assertTrue(asistencia.getMotivoFueraDePlan().contains("Sin clases disponibles"));
        assertEquals(0, BigDecimal.ZERO.compareTo(asistencia.getPrecioCobrado()));

        verify(financialLogRepository, never()).save(any(FinancialLog.class));
    }

    // ─── Sede principal ───

    @Test
    void mensualidad_usaPlanDeSedePrincipal_noImportaEnQueSedeSeRegistroLaClase() {
        Escenario cancha = escenario(10L, "Cancha");
        Sede sedePrincipal = sedeConEscenario(100L, cancha);
        Sede sedeSecundaria = sedeConEscenario(101L, cancha);

        PlanMensualidad planPrincipal = new PlanMensualidad();
        planPrincipal.setNombre("Plan Principal");
        planPrincipal.setCupos(List.of());

        PlanMensualidad planSecundario = new PlanMensualidad();
        planSecundario.setNombre("Plan Secundario");
        planSecundario.setCupos(List.of());

        Enrollment matriculaPrincipal = new Enrollment();
        matriculaPrincipal.setSede(sedePrincipal);
        matriculaPrincipal.setPlanMensualidad(planPrincipal);
        matriculaPrincipal.setEsPrincipal(true);

        Enrollment matriculaSecundaria = new Enrollment();
        matriculaSecundaria.setSede(sedeSecundaria);
        matriculaSecundaria.setPlanMensualidad(planSecundario);
        matriculaSecundaria.setEsPrincipal(false);

        Student student = new Student();
        student.setId(4L);
        student.setParent(new Parent());
        student.setNombreCompleto("Deportista Multi-Sede");
        student.setMatriculas(new ArrayList<>(List.of(matriculaPrincipal, matriculaSecundaria)));
        student.setClubId(1L);

        ClubConfig config = new ClubConfig();
        config.setEsquemaCobro(ClubConfig.EsquemaCobro.MENSUALIDAD);

        when(studentRepository.findById(4L)).thenReturn(Optional.of(student));
        when(clubConfigResolver.resolveForStudent(student)).thenReturn(config);
        // La clase se registra en la sede SECUNDARIA...
        when(sedeRepository.findById(101L)).thenReturn(Optional.of(sedeSecundaria));
        when(monthlyBillingService.resolveAttendanceMonthlyChargeForEnrollment(eq(student), any(), any(), eq(config)))
            .thenReturn(BigDecimal.ZERO);

        service.registrarAsistencia(4L, FinancialService.TipoClase.GRUPAL, "A", null, null, 101L, false);

        // ...pero la mensualidad debe calcularse con la matrícula PRINCIPAL, no con la de esa sede.
        ArgumentCaptor<Enrollment> enrollmentCaptor = ArgumentCaptor.forClass(Enrollment.class);
        verify(monthlyBillingService).resolveAttendanceMonthlyChargeForEnrollment(eq(student), enrollmentCaptor.capture(), any(), eq(config));
        assertEquals(planPrincipal, enrollmentCaptor.getValue().getPlanMensualidad());
    }
}
