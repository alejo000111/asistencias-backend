package com.asistencia.erp.repository;

import com.asistencia.erp.entity.ImportBatchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportBatchLogRepository extends JpaRepository<ImportBatchLog, String> {
}
