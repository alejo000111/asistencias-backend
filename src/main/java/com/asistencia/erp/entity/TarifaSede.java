package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "tarifas_sede")
@Data
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class TarifaSede {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_config_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private ClubConfig clubConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sede_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "grupos"})
    private Sede sede;

    @Column(name = "monto_estandar", precision = 12, scale = 2)
    private BigDecimal montoEstandar;

    @Column(name = "monto_preferencial", precision = 12, scale = 2)
    private BigDecimal montoPreferencial;

    @Column(name = "monto_mora", precision = 12, scale = 2)
    private BigDecimal montoMora;
}
