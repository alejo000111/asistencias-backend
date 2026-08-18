package com.asistencia.erp.repository;

import com.asistencia.erp.entity.StudentComplemento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StudentComplementoRepository extends JpaRepository<StudentComplemento, Long> {

    @Query("SELECT sc FROM StudentComplemento sc WHERE sc.student.id = :studentId AND sc.activo = true")
    List<StudentComplemento> findActivosByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT sc FROM StudentComplemento sc " +
           "WHERE sc.student.id = :studentId AND sc.activo = true AND sc.complemento.escenario.id = :escenarioId")
    List<StudentComplemento> findActivosByStudentIdAndEscenarioId(
            @Param("studentId") Long studentId, @Param("escenarioId") Long escenarioId);
}
