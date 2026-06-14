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

    @Transactional
    public void procesarPagosPendientes(Long parentId) {
        //1. Buscamos al padre ne la base de datos por su ID
        //Si algo falla, lanzamos el error y detenemos proceso
        Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

        //2.Verificar si tiene saldo a favor
        //En vez de usar < o >, usaremos el compareTo
        //Si el saldo es menor o igual a cero, terminamos el proceso (return) porque no hay con qué pagar
        if(parent.getSaldoAbono().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        //3. Traer de los repositorios las clases sin pagar
        //Ya vienen ordenadas por más antigua y orden alfabético
        List<Attendance> clasesPendientes = attendanceRepository.findUnpaidAttendancesByParentIdFIFO(parentId);

        //4.Recorrer una por una las clases pendientes
        for (Attendance clase: clasesPendientes) {
            //Solo va a descontar si el saldo es mayor o igual a la deuda
            //No vamos a tener "Clases medias pagas"
            if (parent.getSaldoAbono().compareTo(clase.getPrecioCobrado()) >= 0) {
                //Si alcanza, restamos el precio de la clase del salgo del padre
                parent.setSaldoAbono(parent.getSaldoAbono().subtract(clase.getPrecioCobrado()));

                //Marcar la clase pagada y guardamos
                clase.setClasePaga(true);
                attendanceRepository.save(clase);

                //Comprobante inmutable en la bitácora financiera
                FinancialLog log = new FinancialLog();
                log.setParent(parent);
                log.setNombreClienteRespaldo(parent.getNombreCompleto());
                log.setFecha(LocalDateTime.now());
                log.setMonto(clase.getPrecioCobrado()); //Acá se guarda cuánto fue el cobro
                log.setTipoMovimiento(FinancialLog.MovementType.USO_ABONO_CLASE);
                log.setMetodoPago(FinancialLog.PaymentMethod.ABONO);

                financialLogRepository.save(log);
            }
        }
        //5.Guardar al padre con su nuevo saldo actualizado
        parentRepository.save(parent);
    }

    public void registrarAbono(Long parentId, BigDecimal monto, FinancialLog.PaymentMethod metodoPago, java.time.LocalDate fechaAbono) {
        //1. Por seguridad no se podrán dar abonos de $0 o de saldo negativo
        if(monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a cero");
        }

        //2. Buscar al padre en la base de datos
        Parent parent = parentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

        //3. Sumamos el nuevo dinero al saldo que ya tenía el padre
        parent.setSaldoAbono(parent.getSaldoAbono().add(monto));

        //4. Crear el recibo histórico en la bitácora
        FinancialLog log = new FinancialLog();
        log.setParent(parent);
        log.setNombreClienteRespaldo(parent.getNombreCompleto());

        // NUEVO: Usamos la fecha que eligió el usuario. Como la base de datos guarda Fecha y Hora, le ponemos la hora actual.
        log.setFecha(fechaAbono.atTime(java.time.LocalTime.now()));

        log.setMonto(monto); //Aquí se guarda cuánto dinero se entregó
        log.setTipoMovimiento(FinancialLog.MovementType.INGRESO_ABONO);
        log.setMetodoPago(metodoPago); //El usuario dirá si fue efectivo o transferencia

        financialLogRepository.save(log);
        parentRepository.save(parent);

        //5. Automatización (Motor FIFO)
        procesarPagosPendientes(parentId);
    }

    @Transactional
    public void registrarAsistencia(Long studentId, TipoClase tipoClase, String nivel, String fecha, BigDecimal precioPersonalizado, Long sedeId) {
        //1. Buscar al niño que vino a clase
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Estudiante no encontrado"));

        //2. Lógica de precios (precio personalizado tiene prioridad si se envió)
        BigDecimal precioFinal;
        if (precioPersonalizado != null) {
            precioFinal = precioPersonalizado;
        } else if (tipoClase == TipoClase.PERSONALIZADA) {
            precioFinal = PRECIO_PERSONALIZADA;
        } else {
            precioFinal = PRECIO_GRUPAL;
        }

        //3. Procesar la Fecha (Si el profe no manda fecha, usamos la de hoy)
        java.time.LocalDateTime fechaFinal;
        if (fecha != null && !fecha.isEmpty()) {
            // Convierte el string "YYYY-MM-DD" a fecha, y le pone la hora actual
            fechaFinal = java.time.LocalDate.parse(fecha).atTime(java.time.LocalTime.now());
        } else {
            fechaFinal = java.time.LocalDateTime.now();
        }

        //4. Crear registro de asistencia
        Attendance asistencia = new Attendance();
        asistencia.setStudent(student);
        asistencia.setFecha(fechaFinal);
        asistencia.setPrecioCobrado(precioFinal);
        asistencia.setClasePaga(false);
        asistencia.setNivel(nivel); // Guardamos el nuevo nivel ("INICIACIÓN" o "AVANZADO")
        asistencia.setNombreEstudianteHistorico(student.getNombreCompleto());
        asistencia.setTipoClase(tipoClase.name()); // Guarda "GRUPAL" o "PERSONALIZADA"

        // Asignar sede a la asistencia si se especificó
        if (sedeId != null) {
            Sede sede = sedeRepository.findById(sedeId)
                    .orElseThrow(() -> new RuntimeException("Sede no encontrada con ID: " + sedeId));
            asistencia.setSede(sede);
        }
        attendanceRepository.save(asistencia);

        //5. Motor FIFO de cobro automático
        procesarPagosPendientes(student.getParent().getId());
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

        // Solo permitimos eliminar INGRESO_ABONO (no USO_ABONO_CLASE)
        if (log.getTipoMovimiento() != FinancialLog.MovementType.INGRESO_ABONO) {
            throw new RuntimeException("Solo se pueden eliminar abonos de ingreso directo");
        }

        Parent parent = log.getParent();

        // Restar el monto del saldo del padre (revertir el ingreso)
        parent.setSaldoAbono(parent.getSaldoAbono().subtract(log.getMonto()));
        parentRepository.save(parent);

        // Eliminar el registro financiero
        financialLogRepository.delete(log);

        // Re-ejecutar FIFO por si se liberaron fondos para pagos previos
        procesarPagosPendientes(parent.getId());
    }
}
