package com.asistencia.erp.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendances")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //Relación: Muchas asistencias pertenecen a un estudiante
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "student_id", nullable = true, foreignKey = @ForeignKey(name = "FK_attendance_student"))
    @JsonIgnoreProperties({"attendances", "parent", "hibernateLazyInitializer", "handler"})
    private Student student;

    //Relación: Cada asistencia está vinculada a una sede específica
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sede;

    @Column(name = "nombre_estudiante_historico")
    private String nombreEstudianteHistorico;

    @Column(name = "fecha", nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime fecha;

    @Column(name = "es_media_clase")
    private Boolean esMediaClase;

    //CRÍTICO: Inmutable. Se guarda el valor exacto en el momento del entrenamiento. Inmutabilidad histórica
    @Column(name = "precio_cobrado", precision = 10, scale = 2, nullable = false)
    private BigDecimal precioCobrado;

    @Column(name = "nivel_clase")
    private String nivel; // Guardará el nombre del grupo/nivel (ej. "🌱 Iniciación")

    //Indica el tipo de clase: "GRUPAL" o "PERSONALIZADA"
    @Column(name = "tipo_clase")
    private String tipoClase;

    //Nos indicará si esta clase ya fue cubierta por un abono o pago
    @Column(name = "clase_paga", nullable = false)
    private Boolean clasePaga= false;

    @Column(name = "club_id")
    private Long clubId;

    // FASE 3 — Clase de Cortesía/Prueba
    /** true si este registro es una clase de cortesía (visitante sin matrícula formal) */
    @Column(name = "es_cortesia")
    private Boolean esCortesia = false;

    /** Teléfono del acudiente del visitante de cortesía (sin Parent registrado) */
    @Column(name = "telefono_acudiente_cortesia")
    private String telefonoAcudienteCortesia;

    // FASE 4 — Nómina: trazabilidad de quién dictó/registró la clase
    /** ID del AppUser (ADMIN o EMPLEADO) que registró esta asistencia. */
    @Column(name = "registrado_por_id")
    private Long registradoPorId;

    /** Nombre histórico de quien registró la clase (se conserva aunque el empleado sea eliminado). */
    @Column(name = "registrado_por_nombre")
    private String registradoPorNombre;

    /** true si esta asistencia superó la cuota semanal (plan incluido + complementos) de la sede física donde se tomó. */
    @Column(name = "fuera_de_plan")
    private Boolean fueraDePlan = false;

    /** Id (crudo, sin FK) del Complemento contra el que se evaluó la cuota semanal, si aplica. Solo trazabilidad. */
    @Column(name = "complemento_evaluado_id")
    private Long complementoEvaluadoId;

    /** Texto explicando por qué quedó fuera de plan (nombre del complemento/plan y el tope superado). */
    @Column(name = "motivo_fuera_de_plan")
    private String motivoFueraDePlan;

    /** true si el club ya le pagó al entrenador/empleado por dictar esta clase (nómina). */
    @Column(name = "pagado_nomina")
    private Boolean pagadoNomina = false;

    /** Fecha en la que se le pagó al entrenador/empleado esta clase (nómina). Null mientras esté pendiente. */
    @Column(name = "fecha_pago_nomina")
    private LocalDate fechaPagoNomina;

    /** Medio de pago usado para pagarle al entrenador/empleado esta clase (EFECTIVO/TRANSFERENCIA). Null mientras esté pendiente. */
    @Column(name = "metodo_pago_nomina")
    private String metodoPagoNomina;

    /**
     * Aviso de solo-respuesta (nunca persistido) para cuando esta clase se cobró en $0 porque
     * el club no tiene configurada su "Tarifa Clase Suelta" — para que el admin/entrenador que
     * la registró sepa de inmediato que falta ese ajuste, en vez de asumir silenciosamente que
     * la clase fue gratis. Ver AttendanceBillingService.
     */
    @Transient
    private String avisoTarifaNoConfigurada;
}
