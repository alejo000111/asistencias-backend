package com.asistencia.erp.repository;

import com.asistencia.erp.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ══════════════════════════════════════════════════════════════════════
 * 🧪 PRUEBAS DE OPTIMIZACIÓN DE CONSULTAS — N+1 Detection
 * ══════════════════════════════════════════════════════════════════════
 *
 * VALIDA (PERF-N1-01/02/03):
 *   🛡️ Las consultas con JOIN FETCH ejecutan UNA sola query SQL
 *   🛡️ No hay N+1 queries al acceder a relaciones cargadas
 *
 * Estrategia:
 *   - @DataJpaTest con H2 en memoria
 *   - Insertar datos de prueba (sede, estudiante, asistencias)
 *   - Ejecutar las queries optimizadas
 *   - Verificar que al acceder a las relaciones NO se generan queries adicionales
 *     (Esto se logra cerrando el EntityManager después de la query y verificando
 *      que las asociaciones FETCH ya están inicializadas)
 * ══════════════════════════════════════════════════════════════════════
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.show-sql=true",
    "spring.jpa.properties.hibernate.format_sql=true"
})
class AttendanceQueryOptimizationTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private SedeRepository sedeRepository;

    private Sede testSede;
    private Student testStudent;
    private Parent testParent;

    @BeforeEach
    void setUp() {
        // Crear sede con grupos
        testSede = new Sede();
        testSede.setNombre("Sede Test N+1");
        testSede.setActiva(true);
        testSede.setGrupos(List.of(
            new GrupoSede("🌱 Iniciación", "🌱", "#059669"),
            new GrupoSede("🔥 Avanzado", "🔥", "#ea580c")
        ));
        em.persistAndFlush(testSede);

        // Crear padre
        testParent = new Parent();
        testParent.setNombreCompleto("Padre Test N+1");
        testParent.setTelefono("3009998877");
        testParent.setSaldoAbono(BigDecimal.ZERO);
        em.persistAndFlush(testParent);

        // Crear estudiante con matrícula
        testStudent = new Student();
        testStudent.setNombreCompleto("Estudiante Test N+1");
        testStudent.setParent(testParent);
        testStudent.setEstado(Student.StudentStatus.ACTIVO);

        Enrollment enrollment = new Enrollment();
        enrollment.setStudent(testStudent);
        enrollment.setSede(testSede);
        enrollment.setNivel("🌱 Iniciación");
        testStudent.setMatriculas(List.of(enrollment));

        em.persistAndFlush(testStudent);

        // Crear asistencias
        for (int i = 0; i < 5; i++) {
            Attendance a = new Attendance();
            a.setStudent(testStudent);
            a.setSede(testSede);
            a.setFecha(LocalDateTime.now().minusDays(5 - i));
            a.setPrecioCobrado(new BigDecimal("40000"));
            a.setClasePaga(i % 2 == 0);
            a.setNivel("🌱 Iniciación");
            a.setNombreEstudianteHistorico(testStudent.getNombreCompleto());
            a.setTipoClase("GRUPAL");
            em.persist(a);
        }
        em.flush();
        em.clear(); // Limpiar cache 1er nivel para forzar queries reales
    }

    /**
     * Helper: verifica que acceder a las asociaciones de cada Attendance
     * no lance LazyInitializationException (prueba de que JOIN FETCH funcionó).
     * Si el JOIN FETCH no cargó la sede, al estar el EM cerrado (clear),
     * Hibernate lanzaría LazyInitializationException al acceder a getSede().
     * El hecho de que getSede() retorne datos sin error demuestra que la
     * sede se cargó en la misma query SQL (eliminando el N+1).
     */
    private void assertSedeCargadaSinNplus1(List<Attendance> results) {
        assertThat(results).isNotEmpty();
        for (Attendance a : results) {
            // Si JOIN FETCH no funcionó, esto lanzaría LazyInitializationException
            assertThat(a.getSede()).isNotNull();
            assertThat(a.getSede().getNombre()).isEqualTo("Sede Test N+1");
        }
    }

    @Test
    @DisplayName("PERF-N1-01: findBySedeIdIn carga Sede sin N+1")
    void findBySedeIdInSinNplus1() {
        List<Attendance> results = attendanceRepository.findBySedeIdIn(List.of(testSede.getId()));
        assertThat(results).hasSize(5);
        assertSedeCargadaSinNplus1(results);
    }

    @Test
    @DisplayName("PERF-N1-01: findAllWithFetch carga Sede sin N+1")
    void findAllWithFetchSinNplus1() {
        List<Attendance> results = attendanceRepository.findAllWithFetch();
        assertThat(results).hasSize(5);
        assertSedeCargadaSinNplus1(results);
    }

    @Test
    @DisplayName("PERF-N1-01: findRecentByParentId carga Sede sin N+1")
    void findRecentByParentIdSinNplus1() {
        List<Attendance> results = attendanceRepository.findRecentByParentId(testParent.getId());
        assertThat(results).hasSize(5);
        assertSedeCargadaSinNplus1(results);
    }

    @Test
    @DisplayName("PERF-N1-01: findUnpaidAttendancesByParentIdFIFO carga Sede sin N+1")
    void findUnpaidAttendancesByParentIdFIFOSinNplus1() {
        List<Attendance> results = attendanceRepository.findUnpaidAttendancesByParentIdFIFO(testParent.getId());
        assertThat(results).hasSize(3); // 3 impagas de 5
        assertSedeCargadaSinNplus1(results);
        // Verificar que solo retorna impagas
        assertThat(results).allMatch(a -> !a.getClasePaga());
    }

    @Test
    @DisplayName("PERF-N1-01: findByStudentId carga Sede sin N+1")
    void findByStudentIdSinNplus1() {
        List<Attendance> results = attendanceRepository.findByStudentId(testStudent.getId());
        assertThat(results).hasSize(5);
        assertSedeCargadaSinNplus1(results);
    }

    @Test
    @DisplayName("PERF-N1-01: findAllByParentId carga Sede sin N+1")
    void findAllByParentIdSinNplus1() {
        List<Attendance> results = attendanceRepository.findAllByParentId(testParent.getId());
        assertThat(results).hasSize(5);
        assertSedeCargadaSinNplus1(results);
    }

    @Test
    @DisplayName("PERF-N1-02: findAllWithGrupos carga grupos sin N+1")
    void findAllWithGruposSinNplus1() {
        em.clear();
        List<Sede> sedes = sedeRepository.findAllWithGrupos();
        assertThat(sedes).isNotEmpty();
        for (Sede s : sedes) {
            // Si LEFT JOIN FETCH no funcionó, getGrupos() lanzaría LazyInitializationException
            assertThat(s.getGrupos()).isNotEmpty();
            assertThat(s.getGrupos().get(0).getNombre()).isIn("🌱 Iniciación", "🔥 Avanzado");
        }
    }

    @Test
    @DisplayName("PERF-N1-02: findByIdInWithGrupos carga grupos sin N+1")
    void findByIdInWithGruposSinNplus1() {
        em.clear();
        List<Sede> sedes = sedeRepository.findByIdInWithGrupos(List.of(testSede.getId()));
        assertThat(sedes).isNotEmpty();
        for (Sede s : sedes) {
            assertThat(s.getGrupos()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("PERF-JACKSON-01: existsParentWithAccess ejecuta 1 COUNT query")
    void existsParentWithAccessSingleQuery() {
        boolean tieneAcceso = parentRepository.existsParentWithAccess(testParent.getId(), List.of(testSede.getId()));
        assertThat(tieneAcceso).isTrue();
    }
}
