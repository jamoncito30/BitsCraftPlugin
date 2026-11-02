package dev.jamon.bitscraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Dónde pueden romper bloques las explosiones y los jefes de una acción (opción rompe-bloques):
 * <ul>
 *   <li>{@code false} / {@code no}: en ningún lado.</li>
 *   <li>{@code true} / {@code fuera-de-zonas}: fuera de las zonas protegidas.</li>
 *   <li>{@code streamer}: fuera de zonas, y en las zonas del streamer al que le donaron.</li>
 *   <li>{@code streamers}: fuera de zonas, y en las zonas de cualquier streamer vinculado.</li>
 * </ul>
 * Una zona es «de alguien» si es el dueño del claim de GriefPrevention o de la región de WorldGuard.
 */
enum Rompe {
    NO, FUERA_DE_ZONAS, STREAMER, STREAMERS;

    /** Lee la opción del config; {@code porDefecto} si falta o no se entiende. */
    static Rompe desde(Object valor, Rompe porDefecto) {
        if (valor == null) {
            return porDefecto;
        }
        if (valor instanceof Boolean b) {
            return b ? FUERA_DE_ZONAS : NO;
        }
        return switch (valor.toString().trim().toLowerCase(Locale.ROOT)) {
            case "false", "no", "nunca" -> NO;
            case "true", "si", "sí", "fuera-de-zonas" -> FUERA_DE_ZONAS;
            case "streamer", "propias", "suyas" -> STREAMER;
            case "streamers", "de-streamers" -> STREAMERS;
            default -> porDefecto;
        };
    }

    /** Dueños cuyas zonas se pueden romper, o null si no se rompe nada. */
    Set<UUID> duenosPermitidos(UUID streamer, Set<UUID> vinculados) {
        return switch (this) {
            case NO -> null;
            case FUERA_DE_ZONAS -> Set.of();
            case STREAMER -> streamer == null ? Set.of() : Set.of(streamer);
            case STREAMERS -> {
                Set<UUID> s = new HashSet<>(vinculados);
                if (streamer != null) {
                    s.add(streamer);
                }
                yield s;
            }
        };
    }
}
