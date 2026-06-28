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
    //Consulta clave para el Motor Contable FIFO:
    //Busca clases no pagadas (clasePaga = false) de una familia ordenadas por fecha (más antiguas primero)
    //y desempata por orden alfabético del niño
    @Query("SELECT a FROM Attendance a "+
            "JOIN a.student s " +
            "WHERE s.parent.id = :parentId AND a.clasePaga = false " +
            "ORDER BY a.fecha ASC, s.nombreCompleto ASC")
    List<Attendance> findUnpaidAttendancesByParentIdFIFO(@Param("parentId") Long parentId);

    List<Attendance> findByStudentId(Long studentId);

    @Query("SELECT a FROM Attendance a " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId AND a.clasePaga = false " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findUnpaidByParentIdOrderByFechaDesc(@Param("parentId") Long parentId);

    // Filtra asistencias por lista de IDs de sedes
    @Query("SELECT a FROM Attendance a WHERE a.sede.id IN :sedesIds")
    List<Attendance> findBySedeIdIn(@Param("sedesIds") List<Long> sedesIds);

    // Obtener últimas asistencias (pagas o no) de una familia, ordenadas por fecha descendente
    @Query("SELECT a FROM Attendance a " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId " +
           "ORDER BY a.fecha DESC")
    List<Attendance> findRecentByParentId(@Param("parentId") Long parentId);

    // Todas las asistencias de un padre (para recalculo FIFO)
    @Query("SELECT a FROM Attendance a " +
           "JOIN a.student s " +
           "WHERE s.parent.id = :parentId")
    List<Attendance> findAllByParentId(@Param("parentId") Long parentId);



    // Orfanar asistencias de un estudiante (desvincula sin borrar el historial)
    @Modifying
    @Query(value = "UPDATE attendances SET student_id = NULL, nombre_estudiante_historico = COALESCE(nombre_estudiante_historico, (SELECT nombre_completo FROM students WHERE id = :studentId)) WHERE student_id = :studentId", nativeQuery = true)
    void orphanAttendancesByStudentId(@Param("studentId") Long studentId);
}
