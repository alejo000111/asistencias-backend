package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Sede;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SedeRepository extends JpaRepository<Sede, Long> {
    Optional<Sede> findByNombre(String nombre);
    Optional<Sede> findByNombreAndClubId(String nombre, Long clubId);

    Optional<Sede> findByIdAndClubId(Long id, Long clubId);
    List<Sede> findByClubId(Long clubId);
    List<Sede> findByClubIdIsNull();

    // SEC: se filtra estrictamente por s.clubId. Antes había un "OR s.id IN (sedesAutorizadas del admin)"
    // que filtraba por la tabla join app_user_sedes en lugar del club_id real de la sede — si esa tabla
    // tenía datos contaminados de otro club (por bugs previos), la sede ajena aparecía en el listado.
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos LEFT JOIN FETCH s.escenario WHERE (:clubId IS NULL OR s.clubId = :clubId) ORDER BY s.id ASC")
    List<Sede> findAllWithGrupos(@Param("clubId") Long clubId);

    // Carga sedes activas con grupos en una sola consulta
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos LEFT JOIN FETCH s.escenario WHERE s.activa = true AND (:clubId IS NULL OR s.clubId = :clubId) ORDER BY s.id ASC")
    List<Sede> findAllActivasWithGrupos(@Param("clubId") Long clubId);

    // Carga sedes por IDs con grupos en una sola consulta (para EMPLEADO)
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos LEFT JOIN FETCH s.escenario WHERE s.id IN :ids AND (:clubId IS NULL OR s.clubId = :clubId) ORDER BY s.id ASC")
    List<Sede> findByIdInWithGrupos(@Param("ids") List<Long> ids, @Param("clubId") Long clubId);

    /** Todas las sedes de un escenario — la cuota se cuenta agregando todas ellas. */
    @Query("SELECT s.id FROM Sede s WHERE s.escenario.id = :escenarioId")
    List<Long> findIdsByEscenarioId(@Param("escenarioId") Long escenarioId);
}
