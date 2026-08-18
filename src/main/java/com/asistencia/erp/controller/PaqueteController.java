package com.asistencia.erp.controller;

import com.asistencia.erp.dto.PaqueteDto;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Paquete;
import com.asistencia.erp.repository.ClubConfigRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.PaqueteRepository;
import com.asistencia.erp.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/paquetes")
public class PaqueteController {

    @Autowired
    private PaqueteRepository paqueteRepository;

    @Autowired
    private ParentRepository parentRepository;

    @Autowired
    private ClubConfigRepository clubConfigRepository;

    // Devuelve los paquetes del club del padre y resuelve el precio por sede cuando aplica.
    @GetMapping
    public ResponseEntity<List<PaqueteDto>> listarPaquetes(
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Long sedeId) {

        List<PaqueteDto> paquetesConfigurados = new ArrayList<>();

        if (parentId != null) {
            Parent parent = parentRepository.findById(parentId).orElse(null);
            // SEC-BOLA: el padre consultado debe pertenecer al club del usuario autenticado —
            // antes cualquier usuario autenticado (de cualquier club/rol) podía leer el catálogo
            // de paquetes/precios de OTRO club con solo pasar su parentId.
            Long clubActual = SecurityUtils.getClubId();
            if (parent == null || parent.getClubId() == null || clubActual == null
                    || !parent.getClubId().equals(clubActual)) {
                return ResponseEntity.status(403).build();
            }
            ClubConfig config = clubConfigRepository.findByAdminId(parent.getClubId()).orElse(null);
            paquetesConfigurados = extraerPaquetesDesdeConfig(config, sedeId);
        }

        // NOTA: el catálogo legacy `paquetes` (tabla global sin club_id, sin escritores activos)
        // ya NO se usa como fallback aquí — exponía sin filtrar el catálogo de TODOS los clubes a
        // cualquier usuario autenticado, y sus ids podían colisionar con los ids "virtuales" que
        // se generan arriba desde el JSON de configuración del club (ver PackagePurchaseService).
        return ResponseEntity.ok(paquetesConfigurados);
    }

    private List<PaqueteDto> extraerPaquetesDesdeConfig(ClubConfig config, Long sedeId) {
        if (config == null || !StringUtils.hasText(config.getPaquetesClasesJson())) {
            return List.of();
        }

        try {
            List<PaqueteDto> resultado = new ArrayList<>();
            List<String> objetos = separarObjetosJson(config.getPaquetesClasesJson());
            for (int i = 0; i < objetos.size(); i++) {
                String item = objetos.get(i);
                String nombre = extraerTexto(item, "nombre");
                Integer clases = leerEntero(extraerValorNumerico(item, "clases"));
                BigDecimal precio = leerBigDecimal(extraerValorNumerico(item, "precio"));

                if (Boolean.TRUE.equals(config.getPreciosDiferenciados()) && sedeId != null) {
                    BigDecimal precioSede = leerPrecioSede(item, sedeId);
                    if (precioSede != null) {
                        precio = precioSede;
                    }
                }

                if (nombre != null && clases != null && precio != null) {
                    resultado.add(new PaqueteDto((long) i + 1L, nombre, precio, clases, sedeId));
                }
            }

            return resultado;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> separarObjetosJson(String json) {
        List<String> objetos = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return objetos;
        }

        int profundidad = 0;
        int inicio = -1;
        boolean dentroDeCadena = false;
        boolean escapando = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escapando) {
                escapando = false;
                continue;
            }
            if (c == '\\' && dentroDeCadena) {
                escapando = true;
                continue;
            }
            if (c == '"') {
                dentroDeCadena = !dentroDeCadena;
                continue;
            }
            if (dentroDeCadena) {
                continue;
            }
            if (c == '{') {
                if (profundidad == 0) {
                    inicio = i;
                }
                profundidad++;
            } else if (c == '}') {
                profundidad--;
                if (profundidad == 0 && inicio >= 0) {
                    objetos.add(json.substring(inicio, i + 1));
                    inicio = -1;
                }
            }
        }

        return objetos;
    }

    private String extraerTexto(String json, String campo) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(campo) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String extraerValorNumerico(String json, String campo) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(campo) + "\"\\s*:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private Integer leerEntero(Object value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal leerBigDecimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal leerPrecioSede(String json, Long sedeId) {
        if (!StringUtils.hasText(json) || sedeId == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("\"" + sedeId + "\"\\s*:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return leerBigDecimal(matcher.group(1));
    }
}
