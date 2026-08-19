package com.asistencia.erp.service;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class FinancialService {
    //Herramientas(repositorios) que el servicio va a usar
    private final ParentRepository parentRepository;
    private final AttendanceRepository attendanceRepository;
    private final FinancialLogRepository financialLogRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;

    //PRECIOS CONFIGURABLES (desde application.properties)
    @Value("${precios.grupal}")
    private BigDecimal PRECIO_GRUPAL;
    @Value("${precios.personalizada}")
    private BigDecimal PRECIO_PERSONALIZADA;

    //Para evitar errores al escribir, los tipos de clases van en un Enum
    public enum TipoClase {
        GRUPAL, PERSONALIZADA
    }

    // ══════════════════════════════════════════════════════════════════
    // LOCK REGISTRY — Caffeine Cache con auto-limpieza (PERF-FIFO-01)
    // ══════════════════════════════════════════════════════════════════
    //
    // Anteriormente: ConcurrentHashMap<Long, ReentrantLock>
    //   → Fuga de memoria: los locks NUNCA se eliminaban del mapa
    //   → Starvation: lock() plano sin timeout
    //
    // Ahora: Caffeine Cache con:
    //   - expireAfterAccess(5, MINUTES): lock sin uso por 5 min → se limpia
    //   - maximumSize(10_000): límite duro de seguridad (10,000 padres)
    //   - get(key, k -> new ReentrantLock()): creación atómica thread-safe
    //
    // Además, se usa tryLock(5, SECONDS) en lugar de lock() plano
    // para evitar starvation (PERF-FIFO-02).
    // ══════════════════════════════════════════════════════════════════
    private static final long LOCK_TIMEOUT_SECONDS = 5;

    /**
     * Registro de locks por padre usando Caffeine Cache + weakValues().
     *
     * ═══ POR QUÉ weakValues() Y NO expireAfterAccess ═══
     * expireAfterAccess puede expulsar la entrada del cache mientras
     * un hilo aún retiene el lock. Si eso ocurre, el siguiente hilo
     * recibe una instancia DIFERENTE de ReentrantLock, rompiendo la
     * exclusión mutua → race condition financiera.
     *
     * Con weakValues(), la entrada se elimina cuando la JVM recolecta
     * el lock — y el lock solo es recolectable cuando NINGÚN hilo lo
     * tiene adquirido ni referenciado. Es el patrón seguro.
     * ═══════════════════════════════════════════════════════════
     */
    private final Cache<Long, ReentrantLock> lockRegistry = Caffeine.newBuilder()
        .maximumSize(10_000)
        .weakValues()
        .build();

    private ReentrantLock getLock(Long parentId) {
        return lockRegistry.get(parentId, k -> new ReentrantLock());
    }

    /**
     * Adquiere el lock del padre con timeout.
     * Lanza RuntimeException si no se puede adquirir en LOCK_TIMEOUT_SECONDS.
     * Esto evita starvation por hilos colgados (PERF-FIFO-02).
     */
    private void acquireLock(ReentrantLock lock, Long parentId) {
        try {
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException(
                    "No se pudo adquirir el lock para el padre " + parentId +
                    " después de " + LOCK_TIMEOUT_SECONDS + " segundos. " +
                    "Posible starvation detectado. Reintente la operación."
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operación interrumpida al adquirir lock para el padre " + parentId, e);
        }
    }

    @Transactional
    public void procesarPagosPendientes(Long parentId) {
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            Parent parent = parentRepository.findById(parentId)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            if (parent.getSaldoAbono().compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }

            List<Attendance> clasesPendientes = attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parentId);

            for (Attendance clase : clasesPendientes) {
                if (parent.getSaldoAbono().compareTo(clase.getPrecioCobrado()) >= 0) {
                    parent.setSaldoAbono(parent.getSaldoAbono().subtract(clase.getPrecioCobrado()));
                    clase.setClasePaga(true);
                    attendanceRepository.save(clase);

                    FinancialLog log = new FinancialLog();
                    log.setParent(parent);
                    log.setNombreClienteRespaldo(parent.getNombreCompleto());
                    log.setFecha(LocalDateTime.now());
                    log.setMonto(clase.getPrecioCobrado());
                    log.setTipoMovimiento(FinancialLog.MovementType.USO_ABONO_CLASE);
                    log.setMetodoPago(FinancialLog.PaymentMethod.ABONO);
                    financialLogRepository.save(log);
                }
            }
            parentRepository.save(parent);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void registrarAbono(Long parentId, BigDecimal monto, FinancialLog.PaymentMethod metodoPago, java.time.LocalDate fechaAbono) {
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            if (monto.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("El monto debe ser mayor a cero");
            }

            Parent parent = parentRepository.findById(parentId)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            parent.setSaldoAbono(parent.getSaldoAbono().add(monto));

            FinancialLog log = new FinancialLog();
            log.setParent(parent);
            log.setNombreClienteRespaldo(parent.getNombreCompleto());
            log.setFecha(fechaAbono.atTime(java.time.LocalTime.now()));
            log.setMonto(monto);
            log.setTipoMovimiento(FinancialLog.MovementType.INGRESO_ABONO);
            log.setMetodoPago(metodoPago);

            financialLogRepository.save(log);
            parentRepository.save(parent);

            procesarPagosPendientes(parentId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void registrarAsistencia(Long studentId, TipoClase tipoClase, String nivel, String fecha, BigDecimal precioPersonalizado, Long sedeId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Estudiante no encontrado"));

        BigDecimal precioFinal;
        if (precioPersonalizado != null) {
            precioFinal = precioPersonalizado;
        } else if (tipoClase == TipoClase.PERSONALIZADA) {
            precioFinal = PRECIO_PERSONALIZADA;
        } else {
            precioFinal = PRECIO_GRUPAL;
        }

        java.time.LocalDateTime fechaFinal;
        if (fecha != null && !fecha.isEmpty()) {
            fechaFinal = java.time.LocalDate.parse(fecha).atTime(java.time.LocalTime.now());
        } else {
            fechaFinal = java.time.LocalDateTime.now();
        }

        Attendance asistencia = new Attendance();
        asistencia.setStudent(student);
        asistencia.setFecha(fechaFinal);
        asistencia.setPrecioCobrado(precioFinal);
        asistencia.setClasePaga(false);
        asistencia.setNivel(nivel);
        asistencia.setNombreEstudianteHistorico(student.getNombreCompleto());
        asistencia.setTipoClase(tipoClase.name());

        if (sedeId != null) {
            Sede sede = sedeRepository.findById(sedeId)
                    .orElseThrow(() -> new RuntimeException("Sede no encontrada con ID: " + sedeId));
            asistencia.setSede(sede);
        }
        attendanceRepository.save(asistencia);

        Long parentId = student.getParent().getId();
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            procesarPagosPendientes(parentId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void eliminarFamilia(Long parentId) {
        // Adquirir lock del padre para evitar inconsistencias con operaciones FIFO concurrentes
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            Parent parent = parentRepository.findById(parentId)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + parentId));

            // Seguridad: solo permitir borrar familias INACTIVAS
            if (!"INACTIVO".equals(parent.getEstado())) {
                throw new RuntimeException("Debes marcar la familia como INACTIVA antes de poder eliminarla.");
            }

        if (parent.getStudents() != null) {
            for (Student student : parent.getStudents()) {
                Long sid = student.getId();

                // 1. Limpiar enrolamientos vía JPA (orphanRemoval=true elimina de enrollments)
                student.getMatriculas().clear();

                // 2. Orfanar asistencias vía SQL nativo (conserva historial)
                attendanceRepository.orphanAttendancesByStudentId(sid);

                studentRepository.save(student);
            }
        }

        // 4. Orfanar financial_logs del padre (guardando respaldo del nombre)
        List<FinancialLog> logs = financialLogRepository.findByParentIdOrderByFechaDesc(parentId);
        for (FinancialLog log : logs) {
            if (log.getNombreClienteRespaldo() == null || log.getNombreClienteRespaldo().isBlank()) {
                log.setNombreClienteRespaldo(parent.getNombreCompleto());
            }
            log.setParent(null);
            financialLogRepository.save(log);
        }

        // Flush para asegurar que los DELETE/UPDATE se ejecuten antes del borrado final
        studentRepository.flush();

        // 5. Eliminar estudiantes (orphanRemoval=true arrastra enrollments)
        if (parent.getStudents() != null) {
            for (Student student : parent.getStudents()) {
                studentRepository.delete(student);
            }
        }

        // 6. Eliminar padre
        parentRepository.delete(parent);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Elimina un deportista, orfanando sus asistencias (conserva el historial
     * con el nombre del estudiante como respaldo) y luego borra la entidad.
     */
    @Transactional
    public void eliminarDeportista(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Deportista no encontrado"));

        // Orfanar asistencias: desvincula student pero conserva el historial
        attendanceRepository.orphanAttendancesByStudentId(studentId);

        // Eliminar matrículas vía JPQL (más seguro que native query)
        student.getMatriculas().clear();

        studentRepository.delete(student);
    }

    @Transactional
    public void eliminarAbono(Long logId) {
        FinancialLog log = financialLogRepository.findById(logId)
                .orElseThrow(() -> new RuntimeException("Registro financiero no encontrado"));

        if (log.getTipoMovimiento() != FinancialLog.MovementType.INGRESO_ABONO) {
            throw new RuntimeException("Solo se pueden eliminar abonos de ingreso directo");
        }

        Long parentId = log.getParent().getId();
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            // 1. Resetear todas las asistencias del padre a "impaga"
            List<Attendance> todasLasAsistencias = attendanceRepository.findAllByParentId(parentId);
            for (Attendance a : todasLasAsistencias) {
                a.setClasePaga(false);
            }
            attendanceRepository.saveAll(todasLasAsistencias);

            // 2. Eliminar los registros USO_ABONO_CLASE de la bitácora (se regenerarán en FIFO)
            List<FinancialLog> usosAbono = financialLogRepository
                    .findByParentIdAndTipoMovimiento(parentId, FinancialLog.MovementType.USO_ABONO_CLASE);
            financialLogRepository.deleteAll(usosAbono);

            // 3. Eliminar el abono específico
            financialLogRepository.delete(log);

            // 4. Recalcular saldo REAL basado únicamente en INGRESO_ABONOS restantes
            List<FinancialLog> ingresosRestantes = financialLogRepository
                    .findByParentIdAndTipoMovimiento(parentId, FinancialLog.MovementType.INGRESO_ABONO);
            BigDecimal saldoReal = ingresosRestantes.stream()
                    .map(FinancialLog::getMonto)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Parent parent = parentRepository.findById(parentId)
                    .orElseThrow(() -> new RuntimeException("Padre no encontrado"));
            parent.setSaldoAbono(saldoReal);
            parentRepository.save(parent);

            // 5. Re-ejecutar FIFO desde cero con el saldo correcto
            procesarPagosPendientes(parentId);
        } finally {
            lock.unlock();
        }
    }
}
