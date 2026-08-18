package com.asistencia.erp.service.billing;

import com.asistencia.erp.dto.CompraPaqueteRequest;
import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.CompraPaquete;
import com.asistencia.erp.entity.FinancialLog;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.CompraPaqueteRepository;
import com.asistencia.erp.repository.FinancialLogRepository;
import com.asistencia.erp.repository.PaqueteRepository;
import com.asistencia.erp.repository.ParentRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PackagePurchaseService {

    private final ParentRepository parentRepository;
    private final PaqueteRepository paqueteRepository;
    private final CompraPaqueteRepository compraPaqueteRepository;
    private final StudentRepository studentRepository;
    private final SedeRepository sedeRepository;
    private final FinancialLogRepository financialLogRepository;
    private final ClubConfigResolver clubConfigResolver;
    private final ParentLockManager parentLockManager;

    @org.springframework.transaction.annotation.Transactional
    public void registrarCompraPaquete(CompraPaqueteRequest req) {
        if (req.getParentId() == null) {
            throw new IllegalArgumentException("parentId es obligatorio");
        }

        parentLockManager.executeWithLock(req.getParentId(), () -> {
            Parent parent = parentRepository.findById(req.getParentId())
                .orElseThrow(() -> new RuntimeException("Padre no encontrado"));

            // SEC/BUG: req.getPaqueteId() es un id "virtual" generado por PaqueteController a partir
            // de la posición del paquete dentro del JSON de configuración del club (índice + 1), NO el
            // PK real de la tabla legacy `paquetes` (catálogo global, sin club_id). Resolverlo aquí vía
            // paqueteRepository.findById() podía hacer colisión con cualquier fila legacy que tuviera
            // ese mismo PK y sobreescribir silenciosamente el nombre/clases/precio que el admin vio y
            // seleccionó en pantalla. El frontend siempre envía nombrePaquete/clasesIncluidas/precio
            // explícitos (lo que el admin realmente seleccionó), así que esos son la única fuente de
            // verdad — la tabla legacy `paquetes` ya no se consulta para resolver compras.
            String nombrePaquete = req.getNombrePaquete();
            Integer clasesACreditar = req.getClasesIncluidas();
            BigDecimal precio = req.getPrecio();

            if (nombrePaquete == null || clasesACreditar == null || precio == null) {
                throw new IllegalArgumentException("El paquete seleccionado no tiene nombre, clases o precio válidos");
            }
            if (clasesACreditar <= 0) {
                throw new IllegalArgumentException("Las clases incluidas del paquete deben ser mayores a cero");
            }
            if (precio.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("El precio del paquete no puede ser negativo");
            }

            // Si el club maneja precios por sede para paquetes, priorizar ese valor.
            BigDecimal precioPorSede = resolvePackagePriceFromConfig(parent, nombrePaquete, req.getSedeId());
            if (precioPorSede != null && precioPorSede.compareTo(BigDecimal.ZERO) > 0) {
                precio = precioPorSede;
            }

            Sede sede = null;
            if (req.getSedeId() != null) {
                sede = sedeRepository.findById(req.getSedeId()).orElse(null);
            }

            CompraPaquete compra = new CompraPaquete();
            compra.setParent(parent);
            compra.setSede(sede);
            compra.setNombrePaquete(nombrePaquete);
            compra.setClasesIncluidas(clasesACreditar);
            compra.setPrecio(precio);
            compra.setMetodoPago(parsePaymentMethod(req.getMetodoPago()));
            compraPaqueteRepository.save(compra);

            // Bug 4 fix: asignar clases al estudiante específico si se indica, o dividir equitativamente.
            // Se registra la asignación EXACTA (studentId -> clases otorgadas) para poder revertirla con
            // precisión si el abono se elimina (ver eliminarAbono en CashAndPaymentAllocationService),
            // en vez de re-derivarla por texto libre y descontarle a TODOS los hermanos por igual.
            Map<Long, Integer> asignaciones = new LinkedHashMap<>();
            if (req.getStudentId() != null) {
                // Asignación a un estudiante específico
                Student targetStudent = studentRepository.findById(req.getStudentId()).orElse(null);
                if (targetStudent != null && targetStudent.getParent() != null
                        && targetStudent.getParent().getId().equals(req.getParentId())) {
                    int actuales = targetStudent.getClasesDisponibles() != null ? targetStudent.getClasesDisponibles() : 0;
                    targetStudent.setClasesDisponibles(actuales + clasesACreditar);
                    studentRepository.save(targetStudent);
                    asignaciones.put(targetStudent.getId(), clasesACreditar);
                }
            } else {
                // Distribución equitativa entre todos los hijos activos
                List<Student> hijos = studentRepository.findByParentId(req.getParentId()).stream()
                        .filter(s -> s.getEstado() == Student.StudentStatus.ACTIVO)
                        .toList();
                if (!hijos.isEmpty()) {
                    int clasesPorHijo = clasesACreditar / hijos.size();
                    int resto = clasesACreditar % hijos.size();
                    for (int i = 0; i < hijos.size(); i++) {
                        Student s = hijos.get(i);
                        int actuales = s.getClasesDisponibles() != null ? s.getClasesDisponibles() : 0;
                        // El primer hijo recibe las clases extra del módulo (si clasesACreditar no es divisible)
                        int clasesHijo = clasesPorHijo + (i == 0 ? resto : 0);
                        s.setClasesDisponibles(actuales + clasesHijo);
                        studentRepository.save(s);
                        asignaciones.put(s.getId(), clasesHijo);
                    }
                }
            }

            // Bug 3 fix: registrar como PAGO_DIRECTO, no CARGO_EXTRA.
            // Un paquete es un ingreso del cliente, no una deuda pendiente.
            FinancialLog log = new FinancialLog();
            log.setParent(parent);
            log.setNombreClienteRespaldo(parent.getNombreCompleto());
            log.setFecha(java.time.LocalDateTime.now());
            log.setMonto(precio);
            log.setTipoMovimiento(FinancialLog.MovementType.PAGO_DIRECTO);
            log.setConcepto("Paquete: " + nombrePaquete + " (" + clasesACreditar + " clases)");
            log.setMetodoPago(compra.getMetodoPago());
            log.setClubId(parent.getClubId());
            if (!asignaciones.isEmpty()) {
                String asignacionesTexto = asignaciones.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(","));
                log.setDetalles("PKG_ALLOC:" + asignacionesTexto);
            }
            financialLogRepository.save(log);
        });
    }

    private FinancialLog.PaymentMethod parsePaymentMethod(String rawMethod) {
        if (rawMethod == null) {
            return FinancialLog.PaymentMethod.EFECTIVO;
        }
        try {
            return FinancialLog.PaymentMethod.valueOf(rawMethod);
        } catch (IllegalArgumentException ignored) {
            return FinancialLog.PaymentMethod.EFECTIVO;
        }
    }

    private BigDecimal resolvePackagePriceFromConfig(Parent parent, String packageName, Long sedeId) {
        ClubConfig config = clubConfigResolver.resolveForParent(parent);
        if (config == null || config.getPaquetesClasesJson() == null || config.getPaquetesClasesJson().isBlank()) {
            return null;
        }

        String target = findPackageJsonBlock(config.getPaquetesClasesJson(), packageName);
        if (target == null) {
            return null;
        }

        if (Boolean.TRUE.equals(config.getPreciosDiferenciados()) && sedeId != null) {
            Pattern bySede = Pattern.compile("\\\"" + sedeId + "\\\"\\s*:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)");
            Matcher m = bySede.matcher(target);
            if (m.find()) {
                return new BigDecimal(m.group(1));
            }
        }

        Pattern fallback = Pattern.compile("\\\"precio\\\"\\s*:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)");
        Matcher mf = fallback.matcher(target);
        if (mf.find()) {
            return new BigDecimal(mf.group(1));
        }
        return null;
    }

    private String findPackageJsonBlock(String json, String packageName) {
        if (packageName == null) {
            return null;
        }
        String escapedName = Pattern.quote(packageName);
        Pattern pattern = Pattern.compile("\\{[^{}]*\\\"nombre\\\"\\s*:\\s*\\\"" + escapedName + "\\\"[^{}]*\\}");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
