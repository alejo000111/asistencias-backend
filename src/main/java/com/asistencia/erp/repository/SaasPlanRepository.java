package com.asistencia.erp.repository;

import com.asistencia.erp.entity.SaasPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SaasPlanRepository extends JpaRepository<SaasPlan, Long> {

    /**
     * Encuentra el tramo que cubre la cantidad dada de deportistas activos.
     * El tramo con limiteSuperior NULL es el tramo abierto (más alto).
     */
    @Query("SELECT p FROM SaasPlan p " +
           "WHERE p.limiteInferior <= :cantidad " +
           "AND (p.limiteSuperior IS NULL OR p.limiteSuperior >= :cantidad) " +
           "ORDER BY p.limiteInferior ASC")
    Optional<SaasPlan> findPlanParaCantidad(@Param("cantidad") int cantidad);
}
