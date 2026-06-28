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

    Optional<Parent> findBySecretToken(String secretToken);

    // Busca padres cuyos estudiantes estén matriculados O tengan asistencias previas
    // en alguna de las sedes autorizadas para el empleado.
    // Usamos EXISTS con subconsultas para evitar NULL navigation de LEFT JOINs
    // y garantizar compatibilidad con cualquier proveedor JPA.
    @Query(value = "SELECT DISTINCT p FROM Parent p JOIN p.students s " +
           "WHERE EXISTS (SELECT 1 FROM Enrollment e WHERE e.student.id = s.id AND e.sede.id IN :sedesIds) " +
           "OR EXISTS (SELECT 1 FROM Attendance a WHERE a.student.id = s.id AND a.sede.id IN :sedesIds)")
    List<Parent> findParentsBySedes(@Param("sedesIds") List<Long> sedesIds);
}
