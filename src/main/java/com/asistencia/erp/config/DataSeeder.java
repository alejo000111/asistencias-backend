package com.asistencia.erp.config;

import com.asistencia.erp.entity.*;
import com.asistencia.erp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final AppUserRepository appUserRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Crear sede por defecto si no existe
        Sede sedePrincipal = sedeRepository.findByNombre("Sede Principal")
                .orElseGet(() -> {
                    Sede s = new Sede();
                    s.setNombre("Sede Principal");
                    return sedeRepository.save(s);
                });
        log.info("Sede por defecto: {} (ID={})", sedePrincipal.getNombre(), sedePrincipal.getId());

        // 2. Asegurar grupos en Sede Principal (forzar si estan vacios o legacy)
        boolean necesitaMigrar = sedePrincipal.getGrupos() == null
            || sedePrincipal.getGrupos().isEmpty()
            || sedePrincipal.getGrupos().get(0).getNombre() == null
            || sedePrincipal.getGrupos().get(0).getNombre().isBlank();
        if (necesitaMigrar) {
            sedePrincipal.getGrupos().clear();
            sedePrincipal.setGrupos(new ArrayList<>(Arrays.asList(
                new GrupoSede("Iniciación", "🌱", "#059669"),
                new GrupoSede("Avanzado", "🔥", "#ea580c")
            )));
            sedeRepository.save(sedePrincipal);
            log.info("Grupos con objetos GrupoSede agregados/actualizados en Sede Principal");
        } else {
            log.info("Grupos ya tienen objetos GrupoSede, saltando actualizacion");
        }

        // 3. Migrar estudiantes sin matriculas: crear Enrollment hacia Sede Principal
        List<Student> estudiantesSinMatricula = studentRepository.findByMatriculasIsEmpty();
        if (!estudiantesSinMatricula.isEmpty()) {
            log.info("Creando Enrollment para {} estudiantes sin matricula...", estudiantesSinMatricula.size());
            for (Student s : estudiantesSinMatricula) {
                Enrollment e = new Enrollment();
                e.setStudent(s);
                e.setSede(sedePrincipal);
                e.setNivel("🌱 Iniciación");
                s.getMatriculas().add(e);
            }
            studentRepository.saveAll(estudiantesSinMatricula);
        }

        // 3b. Migrar enrollments existentes con nivel antiguo a version con emoji
        List<Enrollment> sinEmoji = enrollmentRepository.findByNivel("Iniciacion");
        if (!sinEmoji.isEmpty()) {
            log.info("Actualizando {} enrollments 'Iniciacion' -> '🌱 Iniciación'...", sinEmoji.size());
            for (Enrollment e : sinEmoji) {
                e.setNivel("🌱 Iniciación");
            }
            enrollmentRepository.saveAll(sinEmoji);
        }
        List<Enrollment> sinEmoji2 = enrollmentRepository.findByNivel("Avanzado");
        if (!sinEmoji2.isEmpty()) {
            log.info("Actualizando {} enrollments 'Avanzado' -> '🔥 Avanzado'...", sinEmoji2.size());
            for (Enrollment e : sinEmoji2) {
                e.setNivel("🔥 Avanzado");
            }
            enrollmentRepository.saveAll(sinEmoji2);
        }

        // 4. Crear usuario ADMIN si no existe
        if (appUserRepository.findByUsername("admin").isEmpty()) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("GOAT"));
            admin.setRole(AppUser.Role.ADMIN);
            admin.setSedesAutorizadas(Set.of(sedePrincipal));
            appUserRepository.save(admin);
            log.info("Usuario ADMIN creado: admin / GOAT");
        }

        // 5. Datos demo si BD vacia
        long parentCount = parentRepository.count();
        if (parentCount == 0) {
            log.info("Insertando datos semilla...");

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
            demoStudent.setFechaNacimiento(LocalDate.of(2016, 3, 15));
            demoStudent.setEstado(Student.StudentStatus.ACTIVO);
            Enrollment e1 = new Enrollment();
            e1.setStudent(demoStudent);
            e1.setSede(sedePrincipal);
            e1.setNivel("🌱 Iniciación");
            demoStudent.setMatriculas(new ArrayList<>(java.util.List.of(e1)));
            studentRepository.save(demoStudent);

            Student demoStudent2 = new Student();
            demoStudent2.setParent(demoParent);
            demoStudent2.setNombreCompleto("Valentina Perez");
            demoStudent2.setEdad(12);
            demoStudent2.setFechaNacimiento(LocalDate.of(2014, 7, 22));
            demoStudent2.setEstado(Student.StudentStatus.ACTIVO);
            Enrollment e2 = new Enrollment();
            e2.setStudent(demoStudent2);
            e2.setSede(sedePrincipal);
            e2.setNivel("🔥 Avanzado");
            demoStudent2.setMatriculas(new ArrayList<>(java.util.List.of(e2)));
            studentRepository.save(demoStudent2);

            log.info("Datos semilla insertados: 1 padre, 2 deportistas.");
        } else {
            log.info("BD con {} registros existentes.", parentCount);
        }
    }
}
