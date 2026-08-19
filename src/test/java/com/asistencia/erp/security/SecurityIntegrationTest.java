package com.asistencia.erp.security;

import com.asistencia.erp.controller.FinancialController;
import com.asistencia.erp.controller.RegistroController;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.*;
import com.asistencia.erp.service.FinancialService;
import com.asistencia.erp.service.SuperAdminService;
import com.asistencia.erp.service.CortesiaService;
import com.asistencia.erp.service.AsistenciaExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ══════════════════════════════════════════════════════════════════════
 * 🧪 PRUEBAS DE SEGURIDAD - Asistencias ERP
 * ══════════════════════════════════════════════════════════════════════
 *
 * Valida los hallazgos corregidos:
 *   🛡️ SEC-IDOR-01 → @PreAuthorize("hasRole('ADMIN')") en endpoints sensibles
 *   🛡️ SEC-BOLA-01 → Verificación de existencia antes de eliminar asistencia
 *
 * ⚠️ NOTA: Este test requiere que la dependencia spring-boot-test-autoconfigure
 *    esté disponible en el classpath. Si falla la compilación con
 *    "package org.springframework.boot.test.autoconfigure.web.servlet does not exist",
 *    se debe a que la versión de Spring Boot configurada en pom.xml (4.0.3)
 *    no está disponible en los repositorios Maven configurados. Revisar la
 *    versión real de Spring Boot a usar.
 *
 * Estrategia:
 *   - @WebMvcTest con los controladores afectados + @Import(SecurityConfig.class)
 *   - @MockBean para todas las dependencias (incluyendo JwtAuthFilter y JwtUtil)
 *   - Simulación de roles con @WithMockUser
 *   - Stubs en @BeforeEach para que los métodos retornen valores esperados
 * ══════════════════════════════════════════════════════════════════════
 */
@WebMvcTest(controllers = {
    FinancialController.class,
    RegistroController.class
})
@Import(SecurityConfig.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Dependencias de SecurityConfig ──
    @MockitoSpyBean
    private JwtAuthFilter jwtAuthFilter;
    @MockitoBean
    private JwtUtil jwtUtil;

    // ── Dependencias de los controladores ──
    @MockitoBean
    private FinancialService financialService;
    @MockitoBean
    private SuperAdminService superAdminService;
    @MockitoBean
    private CortesiaService cortesiaService;
    @MockitoBean
    private AsistenciaExportService asistenciaExportService;
    @MockitoBean
    private FinancialLogRepository financialLogRepository;
    @MockitoBean
    private AttendanceRepository attendanceRepository;
    @MockitoBean
    private ParentRepository parentRepository;
    @MockitoBean
    private StudentRepository studentRepository;
    @MockitoBean
    private SedeRepository sedeRepository;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private EnrollmentRepository enrollmentRepository;
    @MockitoBean
    private com.asistencia.erp.repository.PlanMensualidadRepository planMensualidadRepository;
    @MockitoBean
    private ClubConfigRepository clubConfigRepository;
    @MockitoBean
    private com.asistencia.erp.service.billing.MonthlyBillingService monthlyBillingService;

    @BeforeEach
    void setUp() {
        Parent mockParent = new Parent();
        mockParent.setId(1L);
        mockParent.setNombreCompleto("Test Parent");
        mockParent.setClubId(100L); // debe coincidir con getClubIdFromToken() simulado en mockToken()

        Student mockStudent = new Student();
        mockStudent.setId(1L);
        mockStudent.setNombreCompleto("Test Student");
        mockStudent.setParent(mockParent);
        mockStudent.setClubId(100L); // ídem: los endpoints de RegistroController validan pertenencia al club

        com.asistencia.erp.entity.Sede mockSede = new com.asistencia.erp.entity.Sede();
        mockSede.setId(1L);
        mockSede.setNombre("Sede Test");
        mockSede.setActiva(true);
        mockSede.setClubId(100L); // debe pertenecer al mismo club para pasar la validación SEC-BOLA de sede

        when(studentRepository.findById(1L)).thenReturn(Optional.of(mockStudent));
        when(parentRepository.findById(1L)).thenReturn(Optional.of(mockParent));
        when(sedeRepository.findById(1L)).thenReturn(Optional.of(mockSede));

        // Para el test de asistencia inexistente (SEC-BOLA-01)
        when(attendanceRepository.existsById(anyLong())).thenReturn(false);
    }

    private void mockToken(String role) {
        when(jwtUtil.validateToken("dummy-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("dummy-token")).thenReturn(role);
        when(jwtUtil.getUsernameFromToken("dummy-token")).thenReturn("testuser");
        when(jwtUtil.getUserIdFromToken("dummy-token")).thenReturn(1L);
        when(jwtUtil.getClubIdFromToken("dummy-token")).thenReturn(100L);
        when(jwtUtil.getSedesFromToken("dummy-token")).thenReturn(java.util.List.of(1L));
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-IDOR-01: @PreAuthorize en endpoints financieros mutables
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede registrar asistencia (200 OK)")
    void adminPuedeRegistrarAsistencia() throws Exception {
        mockToken("ADMIN");
        com.asistencia.erp.entity.Attendance attendanceRegistrada = new com.asistencia.erp.entity.Attendance();
        attendanceRegistrada.setId(1L);
        when(financialService.registrarAsistencia(anyLong(), any(), any(), any(), any(), anyLong(), any()))
            .thenReturn(attendanceRegistrada);
        mockMvc.perform(post("/api/finanzas/asistencia")
                .header("Authorization", "Bearer dummy-token")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .param("sedeId", "1")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al registrar asistencia sin sede válida")
    void empleadoNoPuedeRegistrarAsistencia() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(post("/api/finanzas/asistencia")
                .header("Authorization", "Bearer dummy-token")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: Anónimo recibe 403 al registrar asistencia")
    void anonimoNoPuedeRegistrarAsistencia() throws Exception {
        mockMvc.perform(post("/api/finanzas/asistencia")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede registrar abono (200 OK)")
    void adminPuedeRegistrarAbono() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(post("/api/finanzas/abono")
                .header("Authorization", "Bearer dummy-token")
                .param("parentId", "1")
                .param("monto", "50000")
                .param("metodoPago", "EFECTIVO")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al registrar abono")
    void empleadoNoPuedeRegistrarAbono() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(post("/api/finanzas/abono")
                .header("Authorization", "Bearer dummy-token")
                .param("parentId", "1")
                .param("monto", "50000")
                .param("metodoPago", "EFECTIVO")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar abono (200 OK)")
    void adminPuedeEliminarAbono() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(delete("/api/finanzas/abono/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar abono")
    void empleadoNoPuedeEliminarAbono() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(delete("/api/finanzas/abono/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar deportista desde registro (200 OK)")
    void adminPuedeEliminarDeportista() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(delete("/api/registro/deportista/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar deportista desde registro")
    void empleadoNoPuedeEliminarDeportista() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(delete("/api/registro/deportista/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-IDOR-01: @PreAuthorize en endpoints de RegistroController
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar padre (200 OK)")
    void adminPuedeEliminarPadre() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(delete("/api/registro/padre/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar padre")
    void empleadoNoPuedeEliminarPadre() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(delete("/api/registro/padre/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede inactivar padre (200 OK)")
    void adminPuedeInactivarPadre() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(post("/api/registro/padre/1/inactivar")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al inactivar padre")
    void empleadoNoPuedeInactivarPadre() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(post("/api/registro/padre/1/inactivar")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede retirar deportista (200 OK)")
    void adminPuedeRetirarDeportista() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(post("/api/registro/deportista/1/retirar")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al retirar deportista")
    void empleadoNoPuedeRetirarDeportista() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(post("/api/registro/deportista/1/retirar")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: Acceso a GET /api/finanzas/padres permitido para ADMIN")
    void adminPuedeVerPadres() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(get("/api/finanzas/padres")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO puede ver padres pero filtrados")
    void empleadoPuedeVerPadres() throws Exception {
        mockToken("EMPLEADO");
        mockMvc.perform(get("/api/finanzas/padres")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-BOLA-01: Verificación de existencia en eliminarAsistencia
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-BOLA-01: ADMIN elimina asistencia que no existe → 404")
    void eliminarAsistenciaInexistenteRetorna404() throws Exception {
        mockToken("ADMIN");
        mockMvc.perform(delete("/api/finanzas/asistencia/999")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SEC-BOLA-01: EMPLEADO recibe 403 al intentar eliminar asistencia que no registró")
    void empleadoNoPuedeEliminarAsistencia() throws Exception {
        mockToken("EMPLEADO");
        // Asistencia real del club (100L) pero registrada por OTRO usuario (999L, no el 1L
        // que simula mockToken) — FASE 4: un EMPLEADO solo puede deshacer sus propias asistencias.
        com.asistencia.erp.entity.Attendance attendanceDeOtroEmpleado = new com.asistencia.erp.entity.Attendance();
        attendanceDeOtroEmpleado.setId(1L);
        attendanceDeOtroEmpleado.setClubId(100L);
        attendanceDeOtroEmpleado.setRegistradoPorId(999L);
        when(attendanceRepository.findById(1L)).thenReturn(Optional.of(attendanceDeOtroEmpleado));

        mockMvc.perform(delete("/api/finanzas/asistencia/1")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isForbidden());
    }
}
