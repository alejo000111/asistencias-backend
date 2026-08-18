package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    // ═══ PERF-N1-01: JOIN FETCH en todas las consultas de Attendance ═══
    // JOIN FETCH a.sede asegura que la sede se cargue en la misma query,
    // eliminando el N+1 que ocurría al serializar AttendanceDTO.fromEntity().
    // ====================================================================

    //Consulta clave para el Motor Contable FIFO:
    //Busca clases no pagadas (clasePaga = false) de una familia ordenadas por fecha (más antiguas primero)
    //y desempata por orden alfabético del niño
    @Query("SELECT a FROM Attendance a "+
            "JOIN FETCH a.sede " +
            "JOIN a.student s " +
            "WHERE s.parent.id = :parentId AND a.clasePaga = false " +
            "ORDER BY a.fecha ASC, s.nombreCompleto ASC")
    List<Attendance> findUnpaidAttendancesByParentIdFIFO(@Param("parentId") Long parentId);

    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede WHERE a.student.id = :studentId")
    List<Attendance> findByStudentId(@Param("studentId") Long studentId);

    List<Attendance> findByClubIdIsNull();

    @Query("SELECT a FROM Attendance a " +
           "JOIN FETCH a.sede " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId AND a.clasePaga = false " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findUnpaidByParentIdOrderByFechaDesc(@Param("parentId") Long parentId);

    // Filtra asistencias por lista de IDs de sedes (con JOIN FETCH para sede)
    // Student no necesita FETCH porque ya es EAGER en la entidad
    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede WHERE a.sede.id IN :sedesIds AND (:clubId IS NULL OR a.clubId = :clubId)")
    List<Attendance> findBySedeIdIn(@Param("sedesIds") List<Long> sedesIds, @Param("clubId") Long clubId);

    // Todas las asistencias (con JOIN FETCH para sede)
    // Student no necesita FETCH porque ya es EAGER en la entidad
    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede WHERE (:clubId IS NULL OR a.clubId = :clubId)")
    List<Attendance> findAllWithFetch(@Param("clubId") Long clubId);

    // Obtener últimas asistencias (pagas o no) de una familia, ordenadas por fecha descendente
    @Query("SELECT a FROM Attendance a " +
           "LEFT JOIN FETCH a.sede " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findRecentByParentId(@Param("parentId") Long parentId);

    // Todas las asistencias de un padre (para recalculo FIFO)
    @Query("SELECT a FROM Attendance a " +
           "LEFT JOIN FETCH a.sede " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId")
    List<Attendance> findAllByParentId(@Param("parentId") Long parentId);



    // Orfanar asistencias de un estudiante (desvincula sin borrar el historial)
    @Modifying
    @Query(value = "UPDATE attendances SET student_id = NULL, nombre_estudiante_historico = COALESCE(nombre_estudiante_historico, (SELECT nombre_completo FROM students WHERE id = :studentId)) WHERE student_id = :studentId", nativeQuery = true)
    void orphanAttendancesByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT a FROM Attendance a WHERE a.sede.id = :sedeId")
    List<Attendance> findBySedeId(@Param("sedeId") Long sedeId);

    @Query("SELECT COUNT(a) > 0 FROM Attendance a WHERE a.student.id = :studentId AND FUNCTION('YEAR', a.fecha) = :year AND FUNCTION('MONTH', a.fecha) = :month AND a.precioCobrado > 0")
    boolean existsByStudentIdAndYearAndMonth(@Param("studentId") Long studentId, @Param("year") int year, @Param("month") int month);

    /**
     * La(s) asistencia(s) que representan el cobro de mensualidad del mes (precioCobrado > 0,
     * ya que solo la primera clase del mes se cobra — ver MonthlyBillingService). Se usa para
     * reconciliar la diferencia cuando un admin cambia el PlanMensualidad de una matrícula a
     * mitad de mes (ver PlanMensualidadController).
     */
    @Query("SELECT a FROM Attendance a WHERE a.student.id = :studentId AND FUNCTION('YEAR', a.fecha) = :year AND FUNCTION('MONTH', a.fecha) = :month AND a.precioCobrado > 0 ORDER BY a.fecha ASC")
    List<Attendance> findChargedThisMonth(@Param("studentId") Long studentId, @Param("year") int year, @Param("month") int month);

    // Cuota por ESCENARIO: cuenta las asistencias del estudiante en TODAS las sedes que
    // comparten el mismo escenario dentro del rango. Es lo que hace que un club con dos
    // pistas controle bien el tope de "2 días de pista", aunque el deportista alterne
    // entre ambas — contar por sede individual dejaba pasar el doble.
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId AND a.sede.escenario.id = :escenarioId " +
           "AND a.fecha >= :desde AND a.fecha < :hasta")
    long countByStudentIdAndEscenarioIdAndFechaBetween(@Param("studentId") Long studentId, @Param("escenarioId") Long escenarioId,
            @Param("desde") java.time.LocalDateTime desde, @Param("hasta") java.time.LocalDateTime hasta);

    // Igual que la anterior pero acotada a un grupo/nivel, para complementos que atan su
    // tope a un grupo específico en vez de a todo el escenario.
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId AND a.sede.escenario.id = :escenarioId " +
           "AND a.nivel = :nivel AND a.fecha >= :desde AND a.fecha < :hasta")
    long countByStudentIdAndEscenarioIdAndNivelAndFechaBetween(@Param("studentId") Long studentId, @Param("escenarioId") Long escenarioId,
            @Param("nivel") String nivel, @Param("desde") java.time.LocalDateTime desde, @Param("hasta") java.time.LocalDateTime hasta);

    // FASE 3 — Exportación de planillas: filtros opcionales por sede, nivel y rango de fechas
    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede " +
           "WHERE (:clubId IS NULL OR a.clubId = :clubId) " +
           "AND (:sedeId IS NULL OR (a.sede IS NOT NULL AND a.sede.id = :sedeId)) " +
           "AND (:nivel IS NULL OR a.nivel = :nivel) " +
           "AND (:fechaDesde IS NULL OR a.fecha >= :fechaDesde) " +
           "AND (:fechaHasta IS NULL OR a.fecha <= :fechaHasta) " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findForExport(
            @Param("clubId") Long clubId,
            @Param("sedeId") Long sedeId,
            @Param("nivel") String nivel,
            @Param("fechaDesde") java.time.LocalDateTime fechaDesde,
            @Param("fechaHasta") java.time.LocalDateTime fechaHasta);

    // FASE 3 — Buscar cortesías de un club para el historial
    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede " +
           "WHERE (:clubId IS NULL OR a.clubId = :clubId) AND a.esCortesia = true " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findCortesiasByClubId(@Param("clubId") Long clubId);

    // FASE 4 — Nómina: asistencias registradas por empleados/admins dentro de un rango de fechas,
    // con filtros opcionales por empleado, sede y estado de pago.
    @Query("SELECT a FROM Attendance a LEFT JOIN FETCH a.sede " +
           "WHERE a.clubId = :clubId AND a.registradoPorId IS NOT NULL " +
           "AND a.fecha >= :fechaDesde AND a.fecha <= :fechaHasta " +
           "AND (:empleadoId IS NULL OR a.registradoPorId = :empleadoId) " +
           "AND (:sedeId IS NULL OR (a.sede IS NOT NULL AND a.sede.id = :sedeId)) " +
           "AND (:soloPagado IS NULL OR a.pagadoNomina = :soloPagado) " +
           "ORDER BY a.registradoPorId ASC, a.fecha ASC")
    List<Attendance> findForNomina(
            @Param("clubId") Long clubId,
            @Param("fechaDesde") java.time.LocalDateTime fechaDesde,
            @Param("fechaHasta") java.time.LocalDateTime fechaHasta,
            @Param("empleadoId") Long empleadoId,
            @Param("sedeId") Long sedeId,
            @Param("soloPagado") Boolean soloPagado);
}
