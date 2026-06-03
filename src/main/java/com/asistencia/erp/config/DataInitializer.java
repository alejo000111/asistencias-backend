package com.asistencia.erp.config;

import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.repository.ParentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ParentRepository parentRepository;

    @Override
    public void run(String... args) {
        List<Parent> parentsSinToken = parentRepository.findAll().stream()
                .filter(p -> p.getSecretToken() == null || p.getSecretToken().isBlank())
                .toList();

        if (!parentsSinToken.isEmpty()) {
            log.info("Generando secretToken para {} padres existentes...", parentsSinToken.size());
            for (Parent parent : parentsSinToken) {
                parent.setSecretToken(UUID.randomUUID().toString());
                parentRepository.save(parent);
            }
            log.info("Tokens generados correctamente.");
        } else {
            log.info("Todos los padres ya tienen secretToken.");
        }
    }
}
