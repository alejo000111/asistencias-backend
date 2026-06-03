package com.asistencia.erp.config;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

//@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;

    @Override
    public void run(String... args) {
        long parentCount = parentRepository.count();

        if (parentCount == 0) {
            log.info("============================================");
            log.info("BASE DE DATOS VACIA -- Insertando datos semilla...");
            log.info("============================================");

            Parent demoParent = new Parent();
            demoParent.setNombreCompleto("Carlos Perez (Demo)");
            demoParent.setTelefono("3001112233");
            demoParent.setEstado("ACTIVO");
            demoParent.setSaldoAbono(BigDecimal.ZERO);
            demoParent.setSecretToken(UUID.randomUUID().toString());
            parentRepository.save(demoParent);

            Student demoStudent = new Student();
            demoStudent.setParent(demoParent);
            demoStudent.setNombreCompleto("Santiago Perez");
            demoStudent.setEdad(10);
            demoStudent.setNivel("INICIACION");
            demoStudent.setEstado(Student.StudentStatus.ACTIVO);
            studentRepository.save(demoStudent);

            Student demoStudent2 = new Student();
            demoStudent2.setParent(demoParent);
            demoStudent2.setNombreCompleto("Valentina Perez");
            demoStudent2.setEdad(12);
            demoStudent2.setNivel("AVANZADO");
            demoStudent2.setEstado(Student.StudentStatus.ACTIVO);
            studentRepository.save(demoStudent2);

            log.info("Datos semilla insertados: 1 padre, 2 deportistas.");
            log.info("Token del portal: {}", demoParent.getSecretToken());
            log.info("============================================");
        } else {
            log.info("BD con {} registros existentes -- se omite DataSeeder.", parentCount);
        }
    }
}
