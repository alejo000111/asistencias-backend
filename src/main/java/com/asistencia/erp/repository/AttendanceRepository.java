package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
