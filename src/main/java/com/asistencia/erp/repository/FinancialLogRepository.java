package com.asistencia.erp.repository;

import com.asistencia.erp.entity.FinancialLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface FinancialLogRepository extends JpaRepository<FinancialLog, Long > {
    List<FinancialLog> findByParentIdOrderByFechaDesc(Long parentId);

    List<FinancialLog> findByParentIdAndTipoMovimientoOrderByFechaDesc(Long parentId, FinancialLog.MovementType tipoMovimiento);

    List<FinancialLog> findByParentIdAndTipoMovimiento(Long parentId, FinancialLog.MovementType tipoMovimiento);
}
