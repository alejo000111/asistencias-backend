package com.asistencia.erp.repository;

import com.asistencia.erp.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    //Buscar a los hijos de un padre especifico dependiendo de su estado (ACTIVO/RETIRADO)
    List<Student> findByParentIdAndEstado(Long parentId, Student.StudentStatus estado);
}
