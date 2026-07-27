package com.asistencia.erp.repository;

import com.asistencia.erp.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    /** Lista todos los users con un rol específico, ordenados por username. */
    List<AppUser> findAllByRoleOrderByUsernameAsc(AppUser.Role role);

    /**
     * Cuenta cuántos deportistas ACTIVO tiene un club dado un conjunto de sedes autorizadas.
     * Recibe los IDs de sedes del admin para evitar join inverso no definido.
     * Usa DISTINCT para evitar duplicados por múltiples matrículas.
     */
    @Query("SELECT COUNT(DISTINCT s) FROM Student s " +
           "JOIN s.matriculas m " +
           "WHERE m.sede.id IN :sedeIds AND s.estado = 'ACTIVO'")
    long countDeportistasActivosBySedes(@Param("sedeIds") java.util.List<Long> sedeIds);
}
