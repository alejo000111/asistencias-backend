package com.asistencia.erp.repository;

import com.asistencia.erp.entity.CompraPaquete;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompraPaqueteRepository extends JpaRepository<CompraPaquete, Long> {
    List<CompraPaquete> findByParentIdIn(List<Long> parentIds);
}
