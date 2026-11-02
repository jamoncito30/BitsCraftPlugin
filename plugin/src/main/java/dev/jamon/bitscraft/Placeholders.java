package dev.jamon.bitscraft;

import java.util.Map;

/**
 * Reemplaza {streamer} {usuario} {canal} {monto} {recompensa} en nombres de mobs y comandos.
 * Lo que viene de Twitch se limpia a letras, números y _ (como los nombres de Twitch), para que
 * nadie pueda meter texto en un comando de consola canjeando una recompensa con un título raro.
 */
final class Placeholders {

    private Placeholders() {
    }

    static String limpiar(String texto) {
        if (texto == null) {
            return "";
        }
        String t = texto.replaceAll("[^A-Za-z0-9_]", "");
        return t.length() > 32 ? t.substring(0, 32) : t;
    }

    /** {@code valores} = clave sin llaves → valor ya limpio o confiable (el nombre del streamer). */
    static String aplicar(String plantilla, Map<String, String> valores) {
        String r = plantilla;
        for (Map.Entry<String, String> e : valores.entrySet()) {
            r = r.replace("{" + e.getKey() + "}", e.getValue());
        }
        return r;
    }

    static Map<String, String> de(String streamer, Evento ev, String canal) {
        return Map.of(
                "streamer", limpiar(streamer),
                "usuario", ev.usuario() == null ? "Anonimo" : limpiar(ev.usuario()),
                "canal", limpiar(canal),
                "monto", String.valueOf(ev.monto()),
                "recompensa", limpiar(ev.recompensa()));
    }
}
