package com.asistencia.erp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WompiTransactionInitResponse {
    private String reference;
    private Long amountInCents;
    private String currency;
    private String signature; // If required by wompi
    private String publicKey;
}
