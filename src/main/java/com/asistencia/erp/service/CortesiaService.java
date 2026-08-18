package com.asistencia.erp.service;

import com.asistencia.erp.dto.ConvertirCortesiaRequest;
import com.asistencia.erp.dto.CortesiaRequest;
import com.asistencia.erp.entity.*;
import com.asistencia.erp.repository.*;
import com.asistencia.erp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * FASE 3 — Servicio de Clases de Cortesía.
 *
 * Gestiona el registro de visitantes/prospectos que asisten a una clase de prueba.
 * A diferencia de una asistencia regular, la cortesía SÍ crea de inmediato el perfil
 * completo del cliente (Acudiente + Deportista + Matrícula), pero con el deportista
 * en estado CORTESIA — así aparece en "Clientes > Cortesías" y se puede editar/activar
 * más adelante sin tener que volver a inscribirlo desde cero. Si el teléfono del
 * acudiente ya pertenece a una familia existente, el deportista se agrega a esa
 * familia (caso frecuente: un hermano de un cliente ya registrado toma la cortesía).
 */
@Service
@RequiredArgsConstructor
public class CortesiaService {

    private final AttendanceRepository attendanceRepository;
    private final SedeRepository sedeRepository;
    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AppUserRepository appUserRepository;

    @Value("${precios.grupal}")
    private BigDecimal precioGrupal;

    /**
     * Registra una clase de cortesía para un visitante/prospecto.
     * Crea (o reutiliza) el Acudiente por teléfono, crea el Deportista con estado
     * CORTESIA, lo matricula si se indicó nivel, y registra la asistencia vinculada.
     * El precio cobrado es 0 (la cortesía no genera cobro).
     */
    @Transactional
    public Attendance registrarCortesia(CortesiaRequest req) {
        if (req.getNombreDeportista() == null || req.getNombreDeportista().isBlank()) {
            throw new IllegalArgumentException("El nombre del deportista es obligatorio");
        }
        if (req.getNombreAcudiente() == null || req.getNombreAcudiente().isBlank()) {
            throw new IllegalArgumentException("El nombre del acudiente es obligatorio");
        }
        if (req.getTelefonoAcudiente() == null || req.getTelefonoAcudiente().isBlank()) {
            throw new IllegalArgumentException("El teléfono del acudiente es obligatorio");
        }
        if (req.getSedeId() == null) {
            throw new IllegalArgumentException("La sede es obligatoria");
        }

        Sede sede = sedeRepository.findById(req.getSedeId())
                .orElseThrow(() -> new RuntimeException("Sede no encontrada con ID: " + req.getSedeId()));

        Long clubId = SecurityUtils.getClubId();
        String telLimpio = req.getTelefonoAcudiente().trim();

        // 1. Buscar o crear el acudiente por teléfono (aislado por club)
        Parent parent = parentRepository.findByTelefonoAndClubId(telLimpio, clubId);
        if (parent == null) {
            parent = new Parent();
            parent.setNombreCompleto(req.getNombreAcudiente().trim());
            parent.setTelefono(telLimpio);
            parent.setSaldoAbono(BigDecimal.ZERO);
            parent.setEstado("ACTIVO");
            parent.setClubId(clubId);
            parent = parentRepository.save(parent);
        }

        // 2. Crear el deportista en estado CORTESIA (prospecto, no cuenta contra el límite del plan)
        Student student = new Student();
        student.setNombreCompleto(req.getNombreDeportista().trim());
        student.setParent(parent);
        student.setEstado(Student.StudentStatus.CORTESIA);
        student.setClubId(clubId);
        student = studentRepository.save(student);

        // 3. Matricular si se indicó nivel/grupo
        String nivelFinal = req.getNivel() != null ? req.getNivel().trim() : "";
        if (!nivelFinal.isBlank()) {
            Enrollment enrollment = new Enrollment();
            enrollment.setStudent(student);
            enrollment.setSede(sede);
            enrollment.setNivel(nivelFinal);
            enrollmentRepository.save(enrollment);
        }

        LocalDateTime fechaFinal = (req.getFecha() != null && !req.getFecha().isBlank())
                ? LocalDate.parse(req.getFecha()).atTime(LocalTime.now())
                : LocalDateTime.now();

        // 4. Registrar la asistencia de cortesía, ya vinculada al deportista real
        Attendance cortesia = new Attendance();
        cortesia.setStudent(student);
        cortesia.setNombreEstudianteHistorico(student.getNombreCompleto());
        cortesia.setTelefonoAcudienteCortesia(telLimpio);
        cortesia.setFecha(fechaFinal);
        cortesia.setPrecioCobrado(BigDecimal.ZERO);
        cortesia.setClasePaga(true); // Sin cobro → marcada como pagada
        cortesia.setNivel(nivelFinal.isBlank() ? null : nivelFinal);
        cortesia.setTipoClase("GRUPAL");
        cortesia.setEsCortesia(true);
        cortesia.setSede(sede);
        cortesia.setClubId(clubId);

        // FASE 4 — Nómina: registrar quién dictó la cortesía
        var currentUser = SecurityUtils.getCurrentUser();
        if (currentUser != null) {
            cortesia.setRegistradoPorId(currentUser.getUserId());
            AppUser registrador = appUserRepository.findById(currentUser.getUserId()).orElse(null);
            cortesia.setRegistradoPorNombre(registrador != null && registrador.getNombreCompleto() != null
                    ? registrador.getNombreCompleto() : currentUser.getUsername());
        }

        return attendanceRepository.save(cortesia);
    }

    /**
     * Activa un deportista que tomó una clase de cortesía, dejándolo como matriculado regular.
     *
     * Caso normal (cortesías creadas con el flujo actual): el deportista y su acudiente
     * ya existen desde el registro de la cortesía — solo se pasa su estado de CORTESIA a
     * ACTIVO y se confirma/crea la matrícula si se indicó sede/nivel. No hace falta volver
     * a pedir nombre ni teléfono.
     *
     * Compatibilidad con cortesías antiguas: si la asistencia no tiene deportista asociado
     * (creadas antes de este cambio), se crea el acudiente/deportista con los datos del
     * request, igual que el flujo original.
     *
     * @return mapa con ids del parent y student
     */
    @Transactional
    public Map<String, Object> convertirCortesia(ConvertirCortesiaRequest req) {
        if (req.getCortesiaId() == null) {
            throw new IllegalArgumentException("cortesiaId es obligatorio");
        }

        Attendance cortesia = attendanceRepository.findById(req.getCortesiaId())
                .orElseThrow(() -> new RuntimeException("Cortesía no encontrada con ID: " + req.getCortesiaId()));

        if (!Boolean.TRUE.equals(cortesia.getEsCortesia())) {
            throw new IllegalArgumentException("La asistencia indicada no es una clase de cortesía");
        }

        Long clubId = SecurityUtils.getClubId();
        // SEC-BOLA: la cortesía debe pertenecer al club del ADMIN autenticado — antes un ADMIN
        // de cualquier club podía convertir (matricular) el prospecto de otro club adivinando el id.
        if (cortesia.getClubId() == null || clubId == null || !cortesia.getClubId().equals(clubId)) {
            throw new SecurityException("Acceso denegado a esta cortesía");
        }
        Student student = cortesia.getStudent();
        Parent parent;

        if (student == null) {
            // Compatibilidad con cortesías antiguas sin deportista asociado
            if (req.getNombreAcudiente() == null || req.getNombreAcudiente().isBlank()) {
                throw new IllegalArgumentException("El nombre del acudiente es obligatorio");
            }
            if (req.getTelefonoAcudiente() == null || req.getTelefonoAcudiente().isBlank()) {
                throw new IllegalArgumentException("El teléfono del acudiente es obligatorio");
            }
            if (req.getNombreDeportista() == null || req.getNombreDeportista().isBlank()) {
                throw new IllegalArgumentException("El nombre del deportista es obligatorio");
            }

            String telLimpio = req.getTelefonoAcudiente().trim();
            parent = parentRepository.findByTelefonoAndClubId(telLimpio, clubId);
            if (parent == null) {
                parent = new Parent();
                parent.setNombreCompleto(req.getNombreAcudiente().trim());
                parent.setTelefono(telLimpio);
                parent.setSaldoAbono(BigDecimal.ZERO);
                parent.setEstado("ACTIVO");
                parent.setClubId(clubId);
                parent = parentRepository.save(parent);
            }

            student = new Student();
            student.setNombreCompleto(req.getNombreDeportista().trim());
            student.setParent(parent);
            student.setEstado(Student.StudentStatus.ACTIVO);
            student.setClubId(clubId);
            student = studentRepository.save(student);

            cortesia.setStudent(student);
            cortesia.setNombreEstudianteHistorico(student.getNombreCompleto());
        } else {
            parent = student.getParent();
            student.setEstado(Student.StudentStatus.ACTIVO);
            studentRepository.save(student);
        }

        // Confirmar/crear matrícula si se indicó sede (o usar la de la cortesía como fallback)
        Long sedeIdFinal = req.getSedeId() != null ? req.getSedeId()
                : (cortesia.getSede() != null ? cortesia.getSede().getId() : null);
        if (sedeIdFinal != null) {
            Sede sede = sedeRepository.findById(sedeIdFinal)
                    .orElseThrow(() -> new RuntimeException("Sede no encontrada con ID: " + sedeIdFinal));
            String nivelFinal = (req.getNivel() != null && !req.getNivel().isBlank()) ? req.getNivel().trim()
                    : (cortesia.getNivel() != null && !cortesia.getNivel().isBlank() ? cortesia.getNivel().trim() : "Sin nivel");

            final Student targetStudent = student;
            boolean yaMatriculado = targetStudent.getMatriculas() != null && targetStudent.getMatriculas().stream().anyMatch(m ->
                    m.getSede() != null && m.getSede().getId().equals(sede.getId())
                            && m.getNivel() != null && m.getNivel().equalsIgnoreCase(nivelFinal));

            if (!yaMatriculado) {
                Enrollment enrollment = new Enrollment();
                enrollment.setStudent(student);
                enrollment.setSede(sede);
                enrollment.setNivel(nivelFinal);
                enrollmentRepository.save(enrollment);
            }
        }

        // Nota: NO se cambia esCortesia — el historial debe conservar para siempre
        // el hecho de que ese día se tomó una clase de cortesía, sin importar que
        // el deportista ya esté matriculado hoy.

        return Map.of(
                "parentId", parent.getId(),
                "studentId", student.getId(),
                "mensaje", "Deportista activado y matriculado exitosamente"
        );
    }

    /**
     * Matricula (activa) un deportista que estaba en estado CORTESIA — acción explícita
     * desde "Clientes", sin necesidad de volver a inscribirlo. A propósito NO modifica el
     * flag esCortesia de sus asistencias: el historial debe seguir mostrando para siempre
     * que ese día tomó una clase de cortesía, sin importar que hoy ya esté matriculado.
     */
    @Transactional
    public void matricularCortesia(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));

        Long clubActual = SecurityUtils.getClubId();
        if (student.getClubId() == null || !student.getClubId().equals(clubActual)) {
            throw new RuntimeException("Acceso denegado a este deportista");
        }
        if (student.getEstado() != Student.StudentStatus.CORTESIA) {
            throw new RuntimeException("Este deportista ya está matriculado.");
        }

        student.setEstado(Student.StudentStatus.ACTIVO);
        studentRepository.save(student);
    }
}
