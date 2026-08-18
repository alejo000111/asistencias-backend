package com.asistencia.erp.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class WompiTransactionInitRequest {
    private Long clubId;
    private String secretToken;
    private BigDecimal amount;
    private String currency = "COP";
}
