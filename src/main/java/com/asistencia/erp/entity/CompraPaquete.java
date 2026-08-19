package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * Registro de la compra de un paquete por parte de un padre (cliente).
 */
@Entity
@Table(name = "compra_paquetes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompraPaquete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Padre que adquiere el paquete
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = false)
    private Parent parent;

    // Sede donde se vende el paquete (opcional)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_id", nullable = true)
    private Sede sede;

    // Información del paquete adquirido (se almacena el nombre y número de clases)
    @Column(name = "nombre_paquete", nullable = false)
    private String nombrePaquete;

    @Column(name = "clases_incluidas", nullable = false)
    private Integer clasesIncluidas;

    @Column(name = "precio", precision = 10, scale = 2, nullable = false)
    private BigDecimal precio;

    @Column(name = "fecha_compra", nullable = false)
    private LocalDateTime fechaCompra = LocalDateTime.now();

    // Método de pago opcional
    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago")
    private FinancialLog.PaymentMethod metodoPago;
}
