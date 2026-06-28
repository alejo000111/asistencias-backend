package com.asistencia.erp.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAutoEnv;

    @Value("${cors.allowed-origins:https://tu-app.vercel.app}")
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
                // Solo ADMIN
                .requestMatchers("/api/finanzas/asistencia").hasRole("ADMIN")
                .requestMatchers("/api/finanzas/abono").hasRole("ADMIN")
                .requestMatchers("/api/finanzas/historial").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/finanzas/**").hasRole("ADMIN")
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

        // DETECTOR AUTOMÁTICO DE ENTORNO
        // Staging (create/update): patrón abierto para Vercel + localhost, con credentials para Axios
        if ("create".equals(ddlAutoEnv) || "update".equals(ddlAutoEnv)) {
            configuration.setAllowedOriginPatterns(Arrays.asList("https://*.vercel.app", "http://localhost:*"));
        } else {
            // Producción (validate): solo orígenes explícitos de la variable cors.allowed-origins
            configuration.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.split(",")));
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
