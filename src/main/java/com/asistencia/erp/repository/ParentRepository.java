package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Parent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ParentRepository extends JpaRepository<Parent, Long> {
    //Consulta padre por teléfono
    Parent findByTelefono(String telefono);
}
