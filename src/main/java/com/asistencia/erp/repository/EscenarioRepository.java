package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Escenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EscenarioRepository extends JpaRepository<Escenario, Long> {

    @Query("SELECT e FROM Escenario e WHERE e.clubId = :clubId ORDER BY e.orden ASC, e.id ASC")
    List<Escenario> findByClubId(@Param("clubId") Long clubId);

    Optional<Escenario> findByNombreAndClubId(String nombre, Long clubId);

    /** Cuántas sedes siguen apuntando a este escenario (para no desactivarlo dejando sedes huérfanas). */
    @Query("SELECT COUNT(s) FROM Sede s WHERE s.escenario.id = :escenarioId")
    long contarSedesQueLoUsan(@Param("escenarioId") Long escenarioId);

    /** Cuántos cupos de plan siguen apuntando a este escenario. */
    @Query("SELECT COUNT(c) FROM PlanCupo c WHERE c.escenario.id = :escenarioId")
    long contarCuposQueLoUsan(@Param("escenarioId") Long escenarioId);
}
