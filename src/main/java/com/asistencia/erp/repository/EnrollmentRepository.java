package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByNivel(String nivel);
    List<Enrollment> findBySedeId(Long sedeId);
}
