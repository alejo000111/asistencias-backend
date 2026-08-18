package com.asistencia.erp.repository;

import com.asistencia.erp.entity.ClubConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClubConfigRepository extends JpaRepository<ClubConfig, Long> {

    /** Obtiene la configuración de cobro del admin propietario. */
    Optional<ClubConfig> findByAdminId(Long adminId);
}
