package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Paquete;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaqueteRepository extends JpaRepository<Paquete, Long> {
    // Additional query methods can be added as needed
}
