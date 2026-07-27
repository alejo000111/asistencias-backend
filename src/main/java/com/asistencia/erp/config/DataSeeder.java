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
import java.util.*;

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
        // 1. Crear o asegurar Sede Principal con sus grupos si no existen
        Sede sedePrincipal = sedeRepository.findByNombre("Sede Principal")
                .orElseGet(() -> {
                    Sede s = new Sede();
                    s.setNombre("Sede Principal");
                    s.setActiva(true);
                    return sedeRepository.save(s);
                });

        if (sedePrincipal.getGrupos() == null || sedePrincipal.getGrupos().isEmpty()) {
            sedePrincipal.setGrupos(new ArrayList<>(Arrays.asList(
                    new GrupoSede("Iniciación", "🌱", "#059669"),
                    new GrupoSede("Avanzado", "🔥", "#ea580c")
            )));
            sedeRepository.save(sedePrincipal);
            log.info("Grupos creados en Sede Principal.");
        }

        // 1b. Crear o asegurar Sede Norte si no existe
        Sede sedeNorte = sedeRepository.findByNombre("Sede Norte")
                .orElseGet(() -> {
                    Sede s = new Sede();
                    s.setNombre("Sede Norte");
                    s.setActiva(true);
                    return sedeRepository.save(s);
                });

        if (sedeNorte.getGrupos() == null || sedeNorte.getGrupos().isEmpty()) {
            sedeNorte.setGrupos(new ArrayList<>(Arrays.asList(
                    new GrupoSede("Iniciación", "🌱", "#059669"),
                    new GrupoSede("Avanzado", "🔥", "#ea580c")
            )));
            sedeRepository.save(sedeNorte);
            log.info("Grupos creados en Sede Norte.");
        }

        // 1c. Crear o asegurar Colina si no existe
        Sede sedeColina = sedeRepository.findByNombre("Colina")
                .orElseGet(() -> {
                    Sede s = new Sede();
                    s.setNombre("Colina");
                    s.setActiva(true);
                    return sedeRepository.save(s);
                });

        if (sedeColina.getGrupos() == null || sedeColina.getGrupos().isEmpty()) {
            sedeColina.setGrupos(new ArrayList<>(Arrays.asList(
                    new GrupoSede("Iniciación", "🌱", "#059669"),
                    new GrupoSede("Grandes", "🦉", "#2563eb")
            )));
            sedeRepository.save(sedeColina);
            log.info("Grupos creados en Sede Colina.");
        }

        // 2. Migrar estudiantes sin matriculas (solo si aplica)
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

        // 3. Migrar enrollments existentes con nivel antiguo a versión con emoji
        List<Enrollment> sinEmoji = enrollmentRepository.findByNivel("Iniciacion");
        if (!sinEmoji.isEmpty()) {
            for (Enrollment e : sinEmoji) {
                e.setNivel("🌱 Iniciación");
            }
            enrollmentRepository.saveAll(sinEmoji);
        }
        List<Enrollment> sinEmoji2 = enrollmentRepository.findByNivel("Avanzado");
        if (!sinEmoji2.isEmpty()) {
            for (Enrollment e : sinEmoji2) {
                e.setNivel("🔥 Avanzado");
            }
            enrollmentRepository.saveAll(sinEmoji2);
        }

        // 3b. Migrar estudiantes existentes sin fecha de nacimiento asignándoles una fecha calculada según su edad
        List<Student> estudiantesSinFecha = studentRepository.findAll().stream()
                .filter(s -> s.getFechaNacimiento() == null)
                .toList();

        if (!estudiantesSinFecha.isEmpty()) {
            log.info("Asignando fechaNacimiento a {} estudiantes existentes con fecha nula...", estudiantesSinFecha.size());
            for (Student s : estudiantesSinFecha) {
                int edad = (s.getEdad() != null && s.getEdad() > 0) ? s.getEdad() : 10;
                int anioNacimiento = LocalDate.now().getYear() - edad;
                s.setFechaNacimiento(LocalDate.of(anioNacimiento, 5, 15));
                s.setEdad(edad);
            }
            studentRepository.saveAll(estudiantesSinFecha);
        }

        // 4. Crear/Actualizar usuario ADMIN si no existe y vincular sedes autorizadas
        List<Sede> todasLasSedes = sedeRepository.findAll();
        AppUser admin = appUserRepository.findByUsername("admin").orElseGet(() -> {
            AppUser a = new AppUser();
            a.setUsername("admin");
            a.setPasswordHash(passwordEncoder.encode("GOAT"));
            a.setRole(AppUser.Role.ADMIN);
            return a;
        });

        admin.setSedesAutorizadas(new HashSet<>(todasLasSedes));
        appUserRepository.save(admin);
        log.info("Usuario ADMIN garantizado con {} sedes autorizadas.", todasLasSedes.size());

        // 4b. Crear usuario SUPERADMIN si no existe (contraseña: SUPERGOAT)
        appUserRepository.findByUsername("superadmin").orElseGet(() -> {
            AppUser superAdmin = new AppUser();
            superAdmin.setUsername("superadmin");
            superAdmin.setPasswordHash(passwordEncoder.encode("SUPERGOAT"));
            superAdmin.setRole(AppUser.Role.SUPERADMIN);
            superAdmin.setClubEstado(AppUser.ClubEstado.ACTIVO);
            superAdmin.setSedesAutorizadas(new HashSet<>());  // SUPERADMIN no tiene sedes propias
            AppUser saved = appUserRepository.save(superAdmin);
            log.info("Usuario SUPERADMIN creado. Contraseña inicial: SUPERGOAT — ¡cambiar en producción!");
            return saved;
        });

        // 5. Insertar datos demo ÚNICAMENTE si la base de datos está vacía (0 padres)
        long parentCount = parentRepository.count();
        if (parentCount == 0) {
            log.info("Base de datos vacía. Insertando datos semilla demo...");

            Parent demoParent = new Parent();
            demoParent.setNombreCompleto("Carlos Pérez (Demo)");
            demoParent.setTelefono("3001112233");
            demoParent.setEstado("ACTIVO");
            demoParent.setSaldoAbono(BigDecimal.ZERO);
            demoParent.setSecretToken(UUID.randomUUID().toString());
            parentRepository.save(demoParent);

            Student demoStudent = new Student();
            demoStudent.setParent(demoParent);
            demoStudent.setNombreCompleto("Santiago Pérez");
            demoStudent.setEdad(10);
            demoStudent.setFechaNacimiento(LocalDate.of(2016, 3, 15));
            demoStudent.setEstado(Student.StudentStatus.ACTIVO);
            Enrollment e1 = new Enrollment();
            e1.setStudent(demoStudent);
            e1.setSede(sedePrincipal);
            e1.setNivel("🌱 Iniciación");
            demoStudent.setMatriculas(new ArrayList<>(List.of(e1)));
            studentRepository.save(demoStudent);

            Student demoStudent2 = new Student();
            demoStudent2.setParent(demoParent);
            demoStudent2.setNombreCompleto("Valentina Pérez");
            demoStudent2.setEdad(12);
            demoStudent2.setFechaNacimiento(LocalDate.of(2014, 7, 22));
            demoStudent2.setEstado(Student.StudentStatus.ACTIVO);
            Enrollment e2 = new Enrollment();
            e2.setStudent(demoStudent2);
            e2.setSede(sedePrincipal);
            e2.setNivel("🔥 Avanzado");
            demoStudent2.setMatriculas(new ArrayList<>(List.of(e2)));
            studentRepository.save(demoStudent2);

            log.info("Datos semilla demo insertados correctamente.");
        }
    }
}
