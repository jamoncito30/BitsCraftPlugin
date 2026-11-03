package dev.jamon.bitscraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Dónde pueden romper bloques las explosiones y los jefes de una acción (opción rompe-bloques):
 * <ul>
 *   <li>{@code false} / {@code no}: en ningún lado.</li>
 *   <li>{@code true} / {@code fuera-de-zonas}: fuera de las zonas protegidas.</li>
 *   <li>{@code streamer}: fuera de zonas, y en las zonas del streamer al que le donaron.</li>
 *   <li>{@code streamers}: fuera de zonas, y en las zonas de cualquier streamer vinculado.</li>
 *   <li>{@code solo-streamer}: únicamente dentro de las zonas del streamer al que le donaron
 *       (sus parcelas o construcciones); fuera de ellas no rompe nada.</li>
 * </ul>
 * Una zona es «de alguien» si es el dueño del claim, la región o la parcela (ver {@link Zonas}).
 */
enum Rompe {
    NO, FUERA_DE_ZONAS, STREAMER, STREAMERS, SOLO_STREAMER;

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
            case "solo-streamer", "solo-propias", "solo-suyas" -> SOLO_STREAMER;
            default -> porDefecto;
        };
    }

    /**
     * Si se puede romper un bloque.
     *
     * @param enZona        el bloque está dentro de alguna zona protegida
     * @param protegidoPara dados unos dueños permitidos, true si el bloque está en una zona de otro
     * @param streamer      el streamer al que le donaron (dueño de la entidad que rompe)
     * @param vinculados    los streamers con canal vinculado
     */
    boolean permite(boolean enZona, Predicate<Set<UUID>> protegidoPara, UUID streamer, Set<UUID> vinculados) {
        Set<UUID> suyo = streamer == null ? Set.of() : Set.of(streamer);
        return switch (this) {
            case NO -> false;
            case FUERA_DE_ZONAS -> !enZona;
            case STREAMER -> !protegidoPara.test(suyo);
            case STREAMERS -> {
                Set<UUID> s = new HashSet<>(vinculados);
                s.addAll(suyo);
                yield !protegidoPara.test(s);
            }
            case SOLO_STREAMER -> enZona && streamer != null && !protegidoPara.test(suyo);
        };
    }
}
