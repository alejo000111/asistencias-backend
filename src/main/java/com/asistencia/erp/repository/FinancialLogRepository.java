package com.asistencia.erp.repository;

import com.asistencia.erp.entity.FinancialLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface FinancialLogRepository extends JpaRepository<FinancialLog, Long > {
    //Trae el historial de pagos y abonos de un padre, del más reciente al más antiguo
    List<FinancialLog> findByParentIdOrderByFechaDesc(Long parentId);
}
