package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Complemento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ComplementoRepository extends JpaRepository<Complemento, Long> {

    @Query("SELECT c FROM Complemento c LEFT JOIN FETCH c.preciosPorSede WHERE c.clubId = :clubId ORDER BY c.id ASC")
    List<Complemento> findByClubIdWithPrecios(@Param("clubId") Long clubId);

    List<Complemento> findByClubId(Long clubId);
}
