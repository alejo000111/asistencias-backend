package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.service.FinancialService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BillingSupportUtils {

    public Long resolveTargetSedeId(Student student, Long requestedSedeId) {
        if (requestedSedeId != null) {
            return requestedSedeId;
        }
        if (student == null || student.getMatriculas() == null) {
            return null;
        }

        for (var matricula : student.getMatriculas()) {
            if (matricula.getSede() != null && matricula.getSede().getId() != null) {
                return matricula.getSede().getId();
            }
        }
        return null;
    }

    public BigDecimal extractSedePrice(String json, Long sedeId, String tipoClave) {
        if (json == null || json.isBlank() || sedeId == null || tipoClave == null) {
            return null;
        }

        try {
            String regex = "\"" + sedeId + "\":\\s*\\{([^}]+)\\}";
            java.util.regex.Matcher blockMatcher = java.util.regex.Pattern.compile(regex).matcher(json);
            if (!blockMatcher.find()) {
                return null;
            }

            String block = blockMatcher.group(1);
            String valueRegex = "\"" + tipoClave + "\":\\s*\"?([0-9.]+)\"?";
            java.util.regex.Matcher valueMatcher = java.util.regex.Pattern.compile(valueRegex).matcher(block);
            if (!valueMatcher.find()) {
                return null;
            }

            return new BigDecimal(valueMatcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Precio de "clase suelta" propio del club para el tipo de clase indicado. Si el admin no
     * lo ha configurado, el valor por defecto es CERO — deliberadamente, para no cobrarle a un
     * club una tarifa ajena/global sin que lo haya decidido. Ver {@link #esTarifaClaseSueltaConfigurada}
     * para distinguir "cobra $0 porque configuró $0" de "cobra $0 porque nadie lo configuró".
     */
    public BigDecimal resolveClassPrice(ClubConfig config, FinancialService.TipoClase tipoClase) {
        if (tipoClase == FinancialService.TipoClase.PERSONALIZADA) {
            return config.getPrecioClasePersonalizada() != null ? config.getPrecioClasePersonalizada() : BigDecimal.ZERO;
        }
        return config.getPrecioClaseGrupal() != null ? config.getPrecioClaseGrupal() : BigDecimal.ZERO;
    }

    /** true si el admin ya guardó explícitamente su Tarifa Clase Suelta para este tipo de clase. */
    public boolean esTarifaClaseSueltaConfigurada(ClubConfig config, FinancialService.TipoClase tipoClase) {
        return tipoClase == FinancialService.TipoClase.PERSONALIZADA
            ? config.getPrecioClasePersonalizada() != null
            : config.getPrecioClaseGrupal() != null;
    }

    /**
     * Resuelve el valor de una "clase extra" (fuera de cupo/paquete/mensualidad), para
     * cualquier esquema de cobro: precio diferenciado por sede si está activo, si no,
     * la Tarifa Clase Suelta propia del club (0 si no la ha configurado).
     */
    public BigDecimal resolverValorClaseExtra(ClubConfig config, Long sedeId, FinancialService.TipoClase tipoClase) {
        if (Boolean.TRUE.equals(config.getPreciosDiferenciados()) && sedeId != null && config.getPreciosPorSedeJson() != null) {
            String tipoClave = (tipoClase == FinancialService.TipoClase.PERSONALIZADA) ? "personalizada" : "grupal";
            BigDecimal precioPorSede = extractSedePrice(config.getPreciosPorSedeJson(), sedeId, tipoClave);
            if (precioPorSede != null && precioPorSede.compareTo(BigDecimal.ZERO) > 0) {
                return precioPorSede;
            }
        }
        return resolveClassPrice(config, tipoClase);
    }
}
