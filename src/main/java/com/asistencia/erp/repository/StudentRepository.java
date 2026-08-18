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
    List<Student> findByParentId(Long parentId);
    List<Student> findByMatriculasIsEmpty();
    List<Student> findByClubIdIsNull();

    /**
     * Cuenta los deportistas ACTIVO de un club por su club_id directo — usado para el límite
     * de deportistas del plan SaaS (RegistroController.validarLimitePlan). Preferido sobre
     * contar por sedes autorizadas: es correcto sin importar cómo se creó cada sede (incluidas
     * las auto-creadas por la importación de Excel, que no quedan en AppUser.sedesAutorizadas).
     */
    long countByClubIdAndEstado(Long clubId, Student.StudentStatus estado);

    /**
     * Trae estudiantes con todas sus colecciones en UNA SOLA consulta (JOIN FETCH).
     * Elimina el N+1 que ocurría al serializar Student con matriculas y parent (PERF-N1-01).
     */
    @Query("SELECT DISTINCT s FROM Student s " +
           "LEFT JOIN FETCH s.matriculas m " +
           "LEFT JOIN FETCH m.sede " +
           "LEFT JOIN FETCH s.parent " +
           "WHERE (:clubId IS NULL OR s.clubId = :clubId)")
    List<Student> findAllWithFetch(@Param("clubId") Long clubId);

    @Query("SELECT DISTINCT s FROM Student s " +
           "LEFT JOIN FETCH s.matriculas m " +
           "LEFT JOIN FETCH m.sede " +
           "LEFT JOIN FETCH s.parent " +
           "WHERE (:clubId IS NULL OR s.clubId = :clubId) " +
           "AND (:sedeId IS NULL OR m.sede.id = :sedeId) " +
           "AND (:nivel IS NULL OR :nivel = '' OR LOWER(m.nivel) LIKE LOWER(CONCAT('%', :nivel, '%')) OR LOWER(:nivel) LIKE LOWER(CONCAT('%', m.nivel, '%')))")
    List<Student> filtrarEstudiantes(@Param("sedeId") Long sedeId, @Param("nivel") String nivel, @Param("clubId") Long clubId);
}
