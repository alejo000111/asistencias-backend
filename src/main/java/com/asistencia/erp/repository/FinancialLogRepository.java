package com.asistencia.erp.repository;

import com.asistencia.erp.entity.FinancialLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface FinancialLogRepository extends JpaRepository<FinancialLog, Long> {
    List<FinancialLog> findByParentIdOrderByFechaDesc(Long parentId);

    List<FinancialLog> findByParentIdAndTipoMovimientoOrderByFechaDesc(Long parentId, FinancialLog.MovementType tipoMovimiento);

    List<FinancialLog> findByParentIdAndTipoMovimiento(Long parentId, FinancialLog.MovementType tipoMovimiento);

    @Query("SELECT fl FROM FinancialLog fl WHERE fl.parent.id = :parentId AND fl.tipoMovimiento IN ('INGRESO_ABONO', 'PAGO_DIRECTO') ORDER BY fl.fecha DESC")
    List<FinancialLog> findMovimientosPagosByParentId(@Param("parentId") Long parentId);

    /** Busca CARGO_EXTRA cuyo concepto contiene el texto indicado (para deduplicación y eliminación). */
    List<FinancialLog> findByParentIdAndTipoMovimientoAndConceptoContaining(
        Long parentId,
        FinancialLog.MovementType tipoMovimiento,
        String conceptoParcial
    );

    /** Elimina todos los CARGO_EXTRA de un padre cuyo concepto contiene el texto dado. */
    @Modifying
    @Query("DELETE FROM FinancialLog fl WHERE fl.parent.id = :parentId " +
           "AND fl.tipoMovimiento = 'CARGO_EXTRA' " +
           "AND fl.concepto LIKE %:conceptoParcial%")
    int eliminarCargoExtraPorConcepto(@Param("parentId") Long parentId,
                                       @Param("conceptoParcial") String conceptoParcial);

    @Query("SELECT fl FROM FinancialLog fl WHERE (:clubId IS NULL OR fl.clubId = :clubId) ORDER BY fl.fecha DESC")
    List<FinancialLog> findByClubId(@Param("clubId") Long clubId);

    List<FinancialLog> findByClubIdIsNull();
}
