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
        // Si ddl-auto está en 'create' o 'update' (como configuramos en Render Staging), activa modo permisivo para pruebas personales
        if ("create".equals(ddlAutoEnv) || "update".equals(ddlAutoEnv)) {
            registry.addMapping("/api/**")
                    .allowedOriginPatterns("https://*.vercel.app", "http://localhost:*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        } else {
            // MODO ULTRA SEGURO PARA PRODUCCIÓN (MAIN)
            // Solo acepta el dominio productivo oficial mapeado en la variable real
            registry.addMapping("/api/**")
                    .allowedOrigins(allowedOriginsProduction.split(","))
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    }
}