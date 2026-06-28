package com.asistencia.erp.service;

import com.asistencia.erp.entity.Attendance;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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

    // Lock registry for per-parent concurrency control
    private final ConcurrentHashMap<Long, ReentrantLock> lockRegistry = new ConcurrentHashMap<>();

    private ReentrantLock getLock(Long parentId) {
        return lockRegistry.computeIfAbsent(parentId, k -> new ReentrantLock());
    }

    @Transactional
    public void procesarPagosPendientes(Long parentId) {
        ReentrantLock lock = getLock(parentId);
        lock.lock();
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
        lock.lock();
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
        lock.lock();
        try {
            procesarPagosPendientes(parentId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void eliminarFamilia(Long parentId) {
        Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado con ID: " + parentId));

        // Seguridad: solo permitir borrar familias INACTIVAS
        if (!"INACTIVO".equals(parent.getEstado())) {
            throw new RuntimeException("Debes marcar la familia como INACTIVA antes de poder eliminarla.");
        }

        if (parent.getStudents() != null) {
            for (Student student : parent.getStudents()) {
                Long sid = student.getId();

                // 1. Forzar DELETE en tabla legada student_sedes (FK constraint) vía SQL nativo
                studentRepository.deleteFromStudentSedes(sid);

                // 2. Limpiar enrolamientos (tabla enrollments)
                student.getMatriculas().clear();

                // 3. Orfanar asistencias vía SQL nativo (conserva historial)
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
        lock.lock();
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
