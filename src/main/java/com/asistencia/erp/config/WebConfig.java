package com.asistencia.erp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAutoEnv;

    @Value("${cors.allowed-origins:https://tu-app.vercel.app}")
    private String allowedOriginsProduction;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // DETECTOR AUTOMÁTICO DE ENTORNO
        // MODO STAGING - COMPATIBLE CON CREDENCIALES DE AXIOS (withCredentials=true)
        if ("create".equals(ddlAutoEnv) || "update".equals(ddlAutoEnv)) {
            registry.addMapping("/api/**")
                    .allowedOriginPatterns("https://*.vercel.app", "http://localhost:*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true); // REQUERIDO POR INTERCEPTORES AXIOS
        } else {
            // MODO ULTRA SEGURO PARA PRODUCCIÓN (MAIN) - INMUTABLE
            registry.addMapping("/api/**")
                    .allowedOrigins(allowedOriginsProduction.split(","))
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    }
}