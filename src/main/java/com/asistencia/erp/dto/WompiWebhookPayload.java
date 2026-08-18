package com.asistencia.erp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class WompiWebhookPayload {
    private String event;
    private DataWrapper data;
    private String environment;
    private Signature signature;
    // Unix timestamp que Wompi incluye en cada evento y que forma parte del checksum de firma
    // (properties + timestamp + secreto, sin separadores) — ver documentación oficial de Wompi.
    private Long timestamp;

    @Data
    public static class DataWrapper {
        private Transaction transaction;
    }

    @Data
    public static class Transaction {
        private String id;
        private String status;
        private String reference;
        @JsonProperty("amount_in_cents")
        private Long amountInCents;
        private String currency;
    }

    @Data
    public static class Signature {
        private List<String> properties;
        private String checksum;
    }
}
