package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByNivel(String nivel);
    List<Enrollment> findBySedeId(Long sedeId);

    @Query("SELECT e FROM Enrollment e WHERE e.student.id = :studentId AND e.planMensualidad IS NOT NULL")
    List<Enrollment> findByStudentIdWithPlan(@Param("studentId") Long studentId);
}
