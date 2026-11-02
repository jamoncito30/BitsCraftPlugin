package dev.jamon.bitscraft;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/** Los límites de la sección "ritmo" de config.yml, en milisegundos. */
record Ritmo(long retardoAvisoMs, long entreAccionesMs, int accionesPorMinuto, Map<String, Integer> porMinuto,
             int mobsVivos, long mobVidaMs, long jefeVidaMs, int colaMaxima, long colaEdadMaximaMs,
             long graciaMs) {

    static Ritmo leer(ConfigurationSection s) {
        if (s == null) {
            s = new org.bukkit.configuration.MemoryConfiguration();
        }
        Map<String, Integer> topes = new HashMap<>();
        ConfigurationSection pm = s.getConfigurationSection("por-minuto");
        if (pm != null) {
            for (String k : pm.getKeys(false)) {
                topes.put(k, Math.max(0, pm.getInt(k)));
            }
        }
        return new Ritmo(
                segundos(s, "retardo-aviso-segundos", 3),
                segundos(s, "segundos-entre-acciones", 2),
                Math.max(1, s.getInt("acciones-por-minuto", 20)),
                Map.copyOf(topes),
                Math.max(0, s.getInt("mobs-vivos-por-streamer", 30)),
                segundos(s, "mob-vida-segundos", 300),
                segundos(s, "jefe-vida-segundos", 600),
                Math.max(1, s.getInt("cola-maxima", 50)),
                Math.max(1, s.getLong("cola-horas-maximas", 24)) * 3_600_000L,
                segundos(s, "gracia-al-volver-segundos", 10));
    }

    private static long segundos(ConfigurationSection s, String clave, double porDefecto) {
        return Math.round(Math.max(0, s.getDouble(clave, porDefecto)) * 1000);
    }

    /** Tope por minuto de un tipo de acción (tnt, rayo...), o -1 si no tiene. */
    int topeDe(String tipo) {
        return porMinuto.getOrDefault(tipo, -1);
    }
}
