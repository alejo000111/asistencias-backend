package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    List<Student> findByParentIdAndEstado(Long parentId, Student.StudentStatus estado);
    List<Student> findByMatriculasIsEmpty();

    /**
     * Trae estudiantes con todas sus colecciones en UNA SOLA consulta (JOIN FETCH).
     * Elimina el N+1 que ocurría al serializar Student con matriculas y parent (PERF-N1-01).
     */
    @Query("SELECT DISTINCT s FROM Student s " +
           "LEFT JOIN FETCH s.matriculas m " +
           "LEFT JOIN FETCH m.sede " +
           "LEFT JOIN FETCH s.parent")
    List<Student> findAllWithFetch();
}
