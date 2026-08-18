package com.asistencia.erp.controller;

import com.asistencia.erp.entity.Enrollment;
import com.asistencia.erp.entity.Escenario;
import com.asistencia.erp.entity.PlanCupo;
import com.asistencia.erp.entity.PlanMensualidad;
import com.asistencia.erp.entity.Sede;
import com.asistencia.erp.repository.EnrollmentRepository;
import com.asistencia.erp.repository.EscenarioRepository;
import com.asistencia.erp.repository.PlanMensualidadRepository;
import com.asistencia.erp.repository.SedeRepository;
import com.asistencia.erp.security.SecurityUtils;
import com.asistencia.erp.service.FinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planes de mensualidad de cada sede (ej. "Básico", "Medio", "Completo").
 * Cada sede tiene su propio catálogo, independiente de las demás sedes del club.
 */
@RestController
@RequestMapping("/api/planes")
@RequiredArgsConstructor
public class PlanMensualidadController {

    private final PlanMensualidadRepository planMensualidadRepository;
    private final SedeRepository sedeRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EscenarioRepository escenarioRepository;
    private final FinancialService financialService;

    /**
     * Sin sedeId devuelve todos los planes del club (lo que usa la tabla comparativa
     * de Ajustes de Cobro para pintar todas las sedes de una sola carga).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> listarPlanes(@RequestParam(required = false) Long sedeId) {
        if (sedeId == null) {
            return ResponseEntity.ok(planMensualidadRepository.findByClubIdWithCupos(SecurityUtils.getClubId()));
        }
        Sede sede = sedeRepository.findById(sedeId).orElse(null);
        if (sede == null || !validarSedeDelClub(sede)) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a esta sede"));
        }
        return ResponseEntity.ok(planMensualidadRepository.findBySedeIdWithCupos(sedeId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Transactional
    public ResponseEntity<?> crearPlan(@RequestBody Map<String, Object> body) {
        if (body.get("sedeId") == null || body.get("nombre") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sedeId y nombre son obligatorios"));
        }
        Sede sede = sedeRepository.findById(Long.parseLong(body.get("sedeId").toString())).orElse(null);
        if (sede == null || !validarSedeDelClub(sede)) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a esta sede"));
        }

        PlanMensualidad plan = new PlanMensualidad();
        plan.setSede(sede);
        plan.setActivo(true);
        aplicarCamposPlan(plan, body);

        return ResponseEntity.ok(planMensualidadRepository.save(plan));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> actualizarPlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        PlanMensualidad plan = planMensualidadRepository.findById(id).orElse(null);
        if (plan == null || !validarSedeDelClub(plan.getSede())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este plan"));
        }
        aplicarCamposPlan(plan, body);
        return ResponseEntity.ok(planMensualidadRepository.save(plan));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> eliminarPlan(@PathVariable Long id) {
        PlanMensualidad plan = planMensualidadRepository.findById(id).orElse(null);
        if (plan == null || !validarSedeDelClub(plan.getSede())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a este plan"));
        }
        plan.setActivo(false);
        planMensualidadRepository.save(plan);
        return ResponseEntity.ok(Map.of("mensaje", "Plan desactivado"));
    }

    /**
     * Asigna (o quita, con planMensualidadId=null) el plan de mensualidad de una matrícula
     * existente. Si esta matrícula es la principal (la que gobierna el cobro mensual del
     * deportista) y la mensualidad del mes en curso YA fue pagada bajo el plan anterior, se
     * reconcilia automáticamente la diferencia contra el nuevo plan — ver
     * {@link #reconciliarCambioDePlan}. Cada mes es independiente: no se tocan meses pasados.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/matricula/{enrollmentId}")
    @Transactional
    public ResponseEntity<?> asignarPlanAMatricula(@PathVariable Long enrollmentId, @RequestBody Map<String, Object> body) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId).orElse(null);
        if (enrollment == null || !validarSedeDelClub(enrollment.getSede())) {
            return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado a esta matrícula"));
        }

        PlanMensualidad planAnterior = enrollment.getPlanMensualidad();
        Sede sedeAnterior = enrollment.getSede();

        Object planIdObj = body.get("planMensualidadId");
        if (planIdObj == null) {
            enrollment.setPlanMensualidad(null);
        } else {
            PlanMensualidad plan = planMensualidadRepository.findById(Long.parseLong(planIdObj.toString())).orElse(null);
            if (plan == null || !plan.getSede().getId().equals(enrollment.getSede().getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "El plan no pertenece a la sede de esta matrícula"));
            }
            enrollment.setPlanMensualidad(plan);
        }
        Enrollment guardado = enrollmentRepository.save(enrollment);

        // La reconciliación de dinero (si la mensualidad del mes ya se pagó bajo el plan
        // anterior) vive en FinancialService — es la misma lógica que dispara el flujo real de
        // edición de deportista (RegistroController.actualizarDeportista), para no duplicarla.
        boolean huboReconciliacion = financialService.reconciliarCambioDePlanMensualidad(guardado, planAnterior, sedeAnterior);

        Map<String, Object> respuesta = new java.util.HashMap<>();
        respuesta.put("enrollment", guardado);
        respuesta.put("reconciliacionAplicada", huboReconciliacion);
        return ResponseEntity.ok(respuesta);
    }

    // ─────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────

    private boolean validarSedeDelClub(Sede sede) {
        Long clubIdActual = SecurityUtils.getClubId();
        return sede.getClubId() != null && sede.getClubId().equals(clubIdActual);
    }

    private void aplicarCamposPlan(PlanMensualidad plan, Map<String, Object> body) {
        if (body.containsKey("nombre") && body.get("nombre") != null)
            plan.setNombre(body.get("nombre").toString());
        if (body.containsKey("montoPreferencial") && body.get("montoPreferencial") != null)
            plan.setMontoPreferencial(new BigDecimal(body.get("montoPreferencial").toString()));
        if (body.containsKey("montoEstandar") && body.get("montoEstandar") != null)
            plan.setMontoEstandar(new BigDecimal(body.get("montoEstandar").toString()));
        if (body.containsKey("montoMora") && body.get("montoMora") != null)
            plan.setMontoMora(new BigDecimal(body.get("montoMora").toString()));
        if (body.containsKey("orden") && body.get("orden") != null)
            plan.setOrden(Integer.parseInt(body.get("orden").toString()));
        if (body.containsKey("activo") && body.get("activo") != null)
            plan.setActivo(Boolean.valueOf(body.get("activo").toString()));

        // Cupos por escenario: reemplazan por completo a los del plan (mismo patrón
        // "clear + addAll" que ClubConfigController usa para tarifasSede). Una cantidad
        // nula o <= 0 equivale a "este plan no incluye ese escenario", así que se omite.
        if (body.containsKey("cupos") && body.get("cupos") != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cuposBody = (List<Map<String, Object>>) body.get("cupos");
            Long clubId = SecurityUtils.getClubId();

            List<PlanCupo> actualizados = new ArrayList<>();
            for (Map<String, Object> item : cuposBody) {
                if (item.get("escenarioId") == null || item.get("cantidad") == null) continue;
                int cantidad;
                try {
                    cantidad = Integer.parseInt(item.get("cantidad").toString());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (cantidad <= 0) continue;

                Escenario escenario = escenarioRepository.findById(Long.parseLong(item.get("escenarioId").toString())).orElse(null);
                if (escenario == null || !escenario.getClubId().equals(clubId)) continue;

                PlanCupo cupo = new PlanCupo();
                cupo.setPlan(plan);
                cupo.setEscenario(escenario);
                cupo.setCantidad(cantidad);
                actualizados.add(cupo);
            }

            plan.getCupos().clear();
            plan.getCupos().addAll(actualizados);
        }
    }
}
