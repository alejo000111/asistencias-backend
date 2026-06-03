package com.asistencia.erp.config;

import com.asistencia.erp.entity.AppUser;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.AppUserRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

//@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // 1. Crear sede por defecto si no existe
        Sede sedePrincipal = sedeRepository.findByNombre("Sede Principal")
                .orElseGet(() -> {
                    Sede s = new Sede();
                    s.setNombre("Sede Principal");
                    return sedeRepository.save(s);
                });
        log.info("Sede por defecto: {} (ID={})", sedePrincipal.getNombre(), sedePrincipal.getId());

        // 2. Asignar todos los estudiantes existentes a la sede principal (evitar nulos)
        List<Student> estudiantesSinSede = studentRepository.findBySedeIsNull();
        if (!estudiantesSinSede.isEmpty()) {
            log.info("Asignando {} estudiantes a la Sede Principal...", estudiantesSinSede.size());
            for (Student s : estudiantesSinSede) {
                s.setSede(sedePrincipal);
            }
            studentRepository.saveAll(estudiantesSinSede);
        }

        // 3. Crear usuario ADMIN por defecto si no existe
        if (appUserRepository.findByUsername("admin").isEmpty()) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("GOAT"));
            admin.setRole(AppUser.Role.ADMIN);
            admin.setSedesAutorizadas(Set.of(sedePrincipal));
            appUserRepository.save(admin);
            log.info("Usuario ADMIN creado: admin / GOAT");
        }

        // 4. Datos demo (padres/estudiantes) si la BD está vacía
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
            demoStudent.setSede(sedePrincipal);
            studentRepository.save(demoStudent);

            Student demoStudent2 = new Student();
            demoStudent2.setParent(demoParent);
            demoStudent2.setNombreCompleto("Valentina Perez");
            demoStudent2.setEdad(12);
            demoStudent2.setNivel("AVANZADO");
            demoStudent2.setEstado(Student.StudentStatus.ACTIVO);
            demoStudent2.setSede(sedePrincipal);
            studentRepository.save(demoStudent2);

            log.info("Datos semilla insertados: 1 padre, 2 deportistas.");
            log.info("Token del portal: {}", demoParent.getSecretToken());
            log.info("============================================");
        } else {
            log.info("BD con {} registros existentes -- se omite DataSeeder.", parentCount);
        }
    }
}
