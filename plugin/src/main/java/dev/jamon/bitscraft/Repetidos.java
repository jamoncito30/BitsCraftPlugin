package dev.jamon.bitscraft;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recuerda los últimos ids de mensaje de EventSub: Twitch puede mandar el mismo dos veces. */
final class Repetidos {

    private final Map<String, Boolean> vistos;

    Repetidos(int capacidad) {
        this.vistos = new LinkedHashMap<>(capacidad * 2, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> mayor) {
                return size() > capacidad;
            }
        };
    }

    /** true la primera vez que se ve el id; false si ya llegó antes. */
    synchronized boolean primeraVez(String id) {
        if (id == null) {
            return true;
        }
        return vistos.put(id, Boolean.TRUE) == null;
    }
}
