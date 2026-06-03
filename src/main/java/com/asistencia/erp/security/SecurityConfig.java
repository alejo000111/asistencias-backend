package com.asistencia.erp.security;

import lombok.RequiredArgsConstructor;
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

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Público
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/finanzas/padres").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/finanzas/historial-asistencias").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/finanzas/historial/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/finanzas/deudas/**").permitAll()
                .requestMatchers("/api/registro/**").permitAll()
                // Solo ADMIN
                .requestMatchers("/api/finanzas/asistencia").hasRole("ADMIN")
                .requestMatchers("/api/finanzas/abono").hasRole("ADMIN")
                .requestMatchers("/api/finanzas/historial").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/finanzas/**").hasRole("ADMIN")
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
