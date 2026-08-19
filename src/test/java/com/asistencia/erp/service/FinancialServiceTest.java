package com.asistencia.erp.service;

import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.repository.AttendanceRepository;
import com.asistencia.erp.service.billing.MonthlyBillingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class FinancialServiceTest {

    private final MonthlyBillingService monthlyBillingService = new MonthlyBillingService(mock(AttendanceRepository.class));

    private ClubConfig buildConfig() {
        ClubConfig config = new ClubConfig();
        config.setMontoEstandar(BigDecimal.valueOf(150));
        config.setMontoPreferencial(BigDecimal.valueOf(100));
        config.setMontoMora(BigDecimal.valueOf(20));
        config.setDiaLimitePreferencial(5);
        config.setDiaCorteMora(5);
        config.setPreciosDiferenciados(false);
        return config;
    }

    @Test
    void testPreferentialRateWithinPreferredDays() {
        ClubConfig config = buildConfig();
        BigDecimal price = monthlyBillingService.calculateMonthlyPrice(config, null, null);
        int today = java.time.LocalDate.now().getDayOfMonth();

        if (today <= config.getDiaLimitePreferencial()) {
            assertEquals(config.getMontoPreferencial(), price);
        } else if (today > config.getDiaCorteMora()) {
            // La Mora es un monto plano e independiente, no un recargo sumado al Estándar
            // (ver MonthlyBillingService.resolverMontoPorCalendario).
            assertEquals(config.getMontoMora(), price);
        } else {
            assertEquals(config.getMontoEstandar(), price);
        }
    }

    @Test
    void testMoraRateAfterCutoff() {
        ClubConfig config = buildConfig();
        config.setDiaLimitePreferencial(1);
        config.setDiaCorteMora(2);
        BigDecimal price = monthlyBillingService.calculateMonthlyPrice(config, null, null);
        // La Mora es un monto plano e independiente, no un recargo sumado al Estándar
        // (ver MonthlyBillingService.resolverMontoPorCalendario).
        assertEquals(config.getMontoMora(), price);
    }
}
