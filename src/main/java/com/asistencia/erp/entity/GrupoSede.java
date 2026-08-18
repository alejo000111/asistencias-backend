package com.asistencia.erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

@Embeddable
public class GrupoSede {

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "emoji")
    private String emoji;

    @Column(name = "color_hex")
    private String colorHex;

    public GrupoSede() {}

    public GrupoSede(String nombre, String emoji, String colorHex) {
        this.nombre = nombre;
        this.emoji = emoji;
        this.colorHex = colorHex;
    }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public String getColorHex() { return colorHex; }
    public void setColorHex(String colorHex) { this.colorHex = colorHex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrupoSede)) return false;
        GrupoSede that = (GrupoSede) o;
        return Objects.equals(nombre, that.nombre) && Objects.equals(emoji, that.emoji);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nombre, emoji);
    }

    @Override
    public String toString() {
        return "GrupoSede{nombre='" + nombre + "', emoji='" + emoji + "'}";
    }
}
