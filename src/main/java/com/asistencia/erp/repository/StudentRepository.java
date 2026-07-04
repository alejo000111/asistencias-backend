package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Filtra estudiantes por sede y nivel opcionales.
     * Si sedeId es null, retorna todos (sin filtro de sede).
     * Si nivel es null, retorna todos los de la(s) sede(s) (sin filtro de nivel).
     * Usa LEFT JOIN para incluir estudiantes sin matrículas.
     */
    @Query("SELECT DISTINCT s FROM Student s " +
           "LEFT JOIN FETCH s.matriculas m " +
           "LEFT JOIN FETCH m.sede " +
           "LEFT JOIN FETCH s.parent " +
           "WHERE (:sedeId IS NULL OR m.sede.id = :sedeId) " +
           "AND (:nivel IS NULL OR m.nivel = :nivel)")
    List<Student> filtrarEstudiantes(@Param("sedeId") Long sedeId, @Param("nivel") String nivel);
}
