package com.asistencia.erp.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Permite usar @PreAuthorize en los controladores
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Público
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                // Solo ADMIN — respaldo a nivel HTTP (además de @PreAuthorize a nivel de método)
                .requestMatchers("/api/finanzas/**").hasAnyRole("ADMIN", "EMPLEADO")
                .requestMatchers(HttpMethod.GET, "/api/sedes").hasAnyRole("ADMIN", "EMPLEADO")
                .requestMatchers("/api/sedes/**").hasRole("ADMIN")
                .requestMatchers("/api/empleados/**").hasRole("ADMIN")
                // ADMIN o EMPLEADO
                .requestMatchers("/api/clientes/**").hasAnyRole("ADMIN", "EMPLEADO")
                .requestMatchers("/api/asistencias/**").hasAnyRole("ADMIN", "EMPLEADO")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();

        // ============================================================
        // 🔒 ORÍGENES LOCALES — siempre permitidos
        //    Sin importar el entorno (ddl-auto, perfil, etc.)
        // ============================================================
        configuration.addAllowedOriginPattern("http://localhost:*");
        configuration.addAllowedOriginPattern("http://127.0.0.1:*");

        // ============================================================
        // 🌐 VERCEL — siempre permitido (Producción + Preview)
        //    - Production:  https://<app>.vercel.app
        //    - Preview:     https://<project-hash>-<scope>.vercel.app
        //    El patrón con comodín cubre TODOS los despliegues de Vercel
        //    sin necesidad de reconfigurar CORS en cada preview.
        //    Esto es seguro porque Vercel es la única plataforma de frontend.
        // ============================================================
        configuration.addAllowedOriginPattern("https://*.vercel.app");

        // ============================================================
        // 🌐 ORÍGENES ADICIONALES (lectura jerárquica)
        //    1. System.getenv("CORS_ALLOWED_ORIGINS") — Render dashboard
        //    2. @Value("${cors.allowed-origins}")      — application.properties o System property
        //    3. Si ambos están vacíos → solo localhost + Vercel (comportamiento seguro por defecto)
        // ============================================================
        String origins = System.getenv("CORS_ALLOWED_ORIGINS");
        if (origins == null || origins.isBlank()) {
            origins = corsAllowedOrigins;
        }

        if (origins != null && !origins.isBlank()) {
            for (String origin : origins.split(",")) {
                origin = origin.trim();
                if (!origin.isEmpty()) {
                    // Si tiene comodín (*), usar pattern; si no, origen exacto
                    if (origin.contains("*")) {
                        configuration.addAllowedOriginPattern(origin);
                    } else {
                        configuration.addAllowedOrigin(origin);
                    }
                }
            }
        }

        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

}
