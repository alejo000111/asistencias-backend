package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Parent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ParentRepository extends JpaRepository<Parent, Long> {
    //Consulta padre por teléfono
    Parent findByTelefono(String telefono);

    // SEC: variante aislada por club, para evitar fusionar/editar acudientes de otro club (importación Excel)
    Parent findByTelefonoAndClubId(String telefono, Long clubId);

    @Query("SELECT DISTINCT p FROM Parent p WHERE (:clubId IS NULL OR p.clubId = :clubId)")
    List<Parent> findByClubId(@Param("clubId") Long clubId);

    List<Parent> findByClubIdIsNull();

    Optional<Parent> findBySecretToken(String secretToken);

    // Busca padres cuyos estudiantes estén matriculados O tengan asistencias previas
    // en alguna de las sedes autorizadas para el empleado.
    @Query(value = "SELECT DISTINCT p FROM Parent p JOIN p.students s " +
           "WHERE (:clubId IS NULL OR p.clubId = :clubId) AND (EXISTS (SELECT 1 FROM Enrollment e WHERE e.student.id = s.id AND e.sede.id IN :sedesIds) " +
           "OR EXISTS (SELECT 1 FROM Attendance a WHERE a.student.id = s.id AND a.sede.id IN :sedesIds))")
    List<Parent> findParentsBySedes(@Param("sedesIds") List<Long> sedesIds, @Param("clubId") Long clubId);

    // ═══ PERF-N1-03: Verificación de acceso EMPLEADO a padre en UNA CONSULTA ═══
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Parent p " +
           "JOIN p.students s " +
           "JOIN s.matriculas m " +
           "WHERE p.id = :parentId AND (:clubId IS NULL OR p.clubId = :clubId) AND m.sede.id IN :sedesIds")
    boolean existsParentWithAccess(@Param("parentId") Long parentId,
                                    @Param("sedesIds") List<Long> sedesIds,
                                    @Param("clubId") Long clubId);
}
