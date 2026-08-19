package com.asistencia.erp.repository;

import com.asistencia.erp.entity.ImportBatchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportBatchLogRepository extends JpaRepository<ImportBatchLog, String> {
    Optional<ImportBatchLog> findTopByClubIdAndRevertedFalseOrderByTimestampDesc(Long clubId);
    Optional<ImportBatchLog> findTopByRevertedFalseOrderByTimestampDesc();
    List<ImportBatchLog> findByClubId(Long clubId);
}
