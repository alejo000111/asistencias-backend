package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    List<Student> findByParentIdAndEstado(Long parentId, Student.StudentStatus estado);
    List<Student> findByMatriculasIsEmpty();

    // Limpieza manual de la tabla legada student_sedes antes de borrar un estudiante
    @Modifying
    @Query(value = "DELETE FROM student_sedes WHERE student_id = :studentId", nativeQuery = true)
    void deleteFromStudentSedes(@Param("studentId") Long studentId);
}
