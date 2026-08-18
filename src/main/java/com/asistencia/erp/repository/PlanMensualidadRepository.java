package com.asistencia.erp.repository;

import com.asistencia.erp.entity.PlanMensualidad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlanMensualidadRepository extends JpaRepository<PlanMensualidad, Long> {

    @Query("SELECT DISTINCT p FROM PlanMensualidad p LEFT JOIN FETCH p.cupos WHERE p.sede.id = :sedeId ORDER BY p.orden ASC, p.id ASC")
    List<PlanMensualidad> findBySedeIdWithCupos(@Param("sedeId") Long sedeId);

    @Query("SELECT DISTINCT p FROM PlanMensualidad p LEFT JOIN FETCH p.cupos WHERE p.sede.clubId = :clubId ORDER BY p.sede.id ASC, p.orden ASC, p.id ASC")
    List<PlanMensualidad> findByClubIdWithCupos(@Param("clubId") Long clubId);

    List<PlanMensualidad> findBySedeId(Long sedeId);
}
