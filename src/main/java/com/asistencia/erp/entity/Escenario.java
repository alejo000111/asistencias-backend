package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Espacio donde se dictan las clases, definido libremente por cada club.
 *
 * No hay lista predefinida: el admin escribe el nombre que necesite —
 * "Cancha", "Pista" y "Gimnasio" en patinaje; "Sintética" y "Parque" en fútbol;
 * o simplemente ninguno si el club no necesita distinguir espacios.
 *
 * Cada Sede se enlaza a un escenario, los planes declaran cuántos días incluyen
 * de cada escenario (ver PlanCupo) y la cuota se cuenta agregando TODAS las sedes
 * que comparten el mismo escenario — así un club con dos pistas controla bien el
 * tope de "2 días de pista por semana" aunque el deportista alterne entre ambas.
 */
@Entity
@Table(name = "escenarios", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"nombre", "club_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Escenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    /** Nombre libre escrito por el admin. */
    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "emoji")
    private String emoji;

    /**
     * Unidad en la que se cuenta la cuota de este escenario: SEMANAL o MENSUAL.
     * De aquí salen tanto la ventana de conteo como las etiquetas de la interfaz
     * ("Cancha /sem", "Gym /mes"), para que ningún texto quede escrito a mano.
     *
     * Columna VARCHAR y no ENUM nativo, siguiendo la regla de
     * SchemaInitializer.blindarColumnasEnumComoVarchar.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "periodo", nullable = false, length = 20)
    @Builder.Default
    private Periodo periodo = Periodo.SEMANAL;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "orden")
    private Integer orden;

    public enum Periodo {
        SEMANAL,
        MENSUAL
    }
}
