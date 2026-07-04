package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Sede;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SedeRepository extends JpaRepository<Sede, Long> {
    Optional<Sede> findByNombre(String nombre);

    // Carga sedes con grupos en UNA SOLA consulta (LEFT JOIN FETCH)
    // para los endpoints que necesitan los grupos (PERF-N1-02)
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos")
    List<Sede> findAllWithGrupos();

    // Carga sedes activas con grupos en una sola consulta
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos WHERE s.activa = true")
    List<Sede> findAllActivasWithGrupos();

    // Carga sedes por IDs con grupos en una sola consulta (para EMPLEADO)
    @Query("SELECT DISTINCT s FROM Sede s LEFT JOIN FETCH s.grupos WHERE s.id IN :ids")
    List<Sede> findByIdInWithGrupos(@Param("ids") List<Long> ids);
}
