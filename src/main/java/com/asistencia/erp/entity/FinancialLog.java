package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "financial_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinancialLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //Relación los registros financieros van atados al padre o madre (el que paga)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"students", "hibernateLazyInitializer", "handler"})
    private Parent parent;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "monto", precision = 10, scale = 2, nullable = false)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", nullable = false)
    private MovementType tipoMovimiento;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago", nullable = false)
    private PaymentMethod metodoPago;

    //Tipos de movimientos para auditoría
    public enum MovementType {
        PAGO_DIRECTO, INGRESO_ABONO, USO_ABONO_CLASE, REVERSION
    }

    //Métodos de pago
    public enum PaymentMethod {
        EFECTIVO, TRANSFERENCIA, ABONO
    }
}
