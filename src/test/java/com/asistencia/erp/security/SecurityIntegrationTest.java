package com.asistencia.erp.security;

import com.asistencia.erp.controller.FinancialController;
import com.asistencia.erp.controller.RegistroController;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.*;
import com.asistencia.erp.service.FinancialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

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
    @MockBean
    private JwtAuthFilter jwtAuthFilter;
    @MockBean
    private JwtUtil jwtUtil;

    // ── Dependencias de los controladores ──
    @MockBean
    private FinancialService financialService;
    @MockBean
    private FinancialLogRepository financialLogRepository;
    @MockBean
    private AttendanceRepository attendanceRepository;
    @MockBean
    private ParentRepository parentRepository;
    @MockBean
    private StudentRepository studentRepository;
    @MockBean
    private SedeRepository sedeRepository;
    @MockBean
    private AppUserRepository appUserRepository;
    @MockBean
    private EnrollmentRepository enrollmentRepository;

    @BeforeEach
    void setUp() {
        Parent mockParent = new Parent();
        mockParent.setId(1L);
        mockParent.setNombreCompleto("Test Parent");

        Student mockStudent = new Student();
        mockStudent.setId(1L);
        mockStudent.setNombreCompleto("Test Student");
        mockStudent.setParent(mockParent);

        when(studentRepository.findById(1L)).thenReturn(Optional.of(mockStudent));
        when(parentRepository.findById(1L)).thenReturn(Optional.of(mockParent));

        // Para el test de asistencia inexistente (SEC-BOLA-01)
        when(attendanceRepository.existsById(anyLong())).thenReturn(false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-IDOR-01: @PreAuthorize en endpoints financieros mutables
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede registrar asistencia (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeRegistrarAsistencia() throws Exception {
        mockMvc.perform(post("/api/finanzas/asistencia")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al registrar asistencia")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeRegistrarAsistencia() throws Exception {
        mockMvc.perform(post("/api/finanzas/asistencia")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: Anónimo recibe 401 al registrar asistencia")
    void anonimoNoPuedeRegistrarAsistencia() throws Exception {
        mockMvc.perform(post("/api/finanzas/asistencia")
                .param("studentId", "1")
                .param("tipoClase", "GRUPAL")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede registrar abono (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeRegistrarAbono() throws Exception {
        mockMvc.perform(post("/api/finanzas/abono")
                .param("parentId", "1")
                .param("monto", "50000")
                .param("metodoPago", "EFECTIVO")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al registrar abono")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeRegistrarAbono() throws Exception {
        mockMvc.perform(post("/api/finanzas/abono")
                .param("parentId", "1")
                .param("monto", "50000")
                .param("metodoPago", "EFECTIVO")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar abono (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeEliminarAbono() throws Exception {
        mockMvc.perform(delete("/api/finanzas/abono/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar abono")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeEliminarAbono() throws Exception {
        mockMvc.perform(delete("/api/finanzas/abono/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar deportista desde finanzas (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeEliminarDeportista() throws Exception {
        mockMvc.perform(delete("/api/finanzas/deportista/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar deportista desde finanzas")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeEliminarDeportista() throws Exception {
        mockMvc.perform(delete("/api/finanzas/deportista/1"))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-IDOR-01: @PreAuthorize en endpoints de RegistroController
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede eliminar padre (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeEliminarPadre() throws Exception {
        mockMvc.perform(delete("/api/registro/padre/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al eliminar padre")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeEliminarPadre() throws Exception {
        mockMvc.perform(delete("/api/registro/padre/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede inactivar padre (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeInactivarPadre() throws Exception {
        mockMvc.perform(post("/api/registro/padre/1/inactivar"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al inactivar padre")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeInactivarPadre() throws Exception {
        mockMvc.perform(post("/api/registro/padre/1/inactivar"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: ADMIN puede retirar deportista (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeRetirarDeportista() throws Exception {
        mockMvc.perform(post("/api/registro/deportista/1/retirar"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 al retirar deportista")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeRetirarDeportista() throws Exception {
        mockMvc.perform(post("/api/registro/deportista/1/retirar"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-IDOR-01: Acceso a GET /api/finanzas/padres solo ADMIN")
    @WithMockUser(roles = "ADMIN")
    void adminPuedeVerPadres() throws Exception {
        mockMvc.perform(get("/api/finanzas/padres"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-IDOR-01: EMPLEADO recibe 403 en GET /api/finanzas/padres")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeVerPadres() throws Exception {
        mockMvc.perform(get("/api/finanzas/padres"))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════
    // 🛡️ SEC-BOLA-01: Verificación de existencia en eliminarAsistencia
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SEC-BOLA-01: ADMIN elimina asistencia que no existe → 404")
    @WithMockUser(roles = "ADMIN")
    void eliminarAsistenciaInexistenteRetorna404() throws Exception {
        mockMvc.perform(delete("/api/finanzas/asistencia/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SEC-BOLA-01: EMPLEADO recibe 403 al intentar eliminar asistencia")
    @WithMockUser(roles = "EMPLEADO")
    void empleadoNoPuedeEliminarAsistencia() throws Exception {
        mockMvc.perform(delete("/api/finanzas/asistencia/1"))
                .andExpect(status().isForbidden());
    }
}
