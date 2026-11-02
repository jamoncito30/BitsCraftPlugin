package dev.jamon.bitscraft;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Qué pasa con cada evento: la sección "catalogo" de config.yml, ya leída. */
final class Catalogo {

    /** Un escalón o recompensa: el nombre que sale en el aviso y sus acciones. */
    record Entrada(String nombre, List<Accion> acciones) {
    }

    private final NavigableMap<Integer, Entrada> bits = new TreeMap<>();
    private final NavigableMap<Integer, Entrada> subs = new TreeMap<>();
    private final Map<String, Entrada> puntos = new java.util.HashMap<>();
    private Entrada raid;
    private int minimoRaid = 1;
    private Entrada follow;
    private long enfriamientoFollowMs;
    private final List<String> avisos = new ArrayList<>();

    static Catalogo leer(ConfigurationSection seccion) {
        Catalogo c = new Catalogo();
        if (seccion == null) {
            c.avisos.add("falta la sección 'catalogo': no va a pasar nada con los eventos");
            return c;
        }
        c.escalones(seccion.getConfigurationSection("bits"), c.bits, "bits");
        c.escalones(seccion.getConfigurationSection("subs"), c.subs, "subs");
        ConfigurationSection p = seccion.getConfigurationSection("puntos");
        if (p != null) {
            for (String titulo : p.getKeys(false)) {
                Entrada e = c.entrada(p.getConfigurationSection(titulo), "puntos." + titulo, titulo);
                if (e != null) {
                    c.puntos.put(normalizar(titulo), e);
                }
            }
        }
        ConfigurationSection r = seccion.getConfigurationSection("raid");
        if (r != null) {
            c.raid = c.entrada(r, "raid", "Raid");
            c.minimoRaid = Math.max(1, r.getInt("minimo-espectadores", 1));
        }
        ConfigurationSection f = seccion.getConfigurationSection("follow");
        if (f != null) {
            c.follow = c.entrada(f, "follow", "Follow");
            c.enfriamientoFollowMs = Math.max(0, f.getLong("enfriamiento-segundos", 30)) * 1000L;
        }
        return c;
    }

    private void escalones(ConfigurationSection s, NavigableMap<Integer, Entrada> destino, String ruta) {
        if (s == null) {
            return;
        }
        for (String clave : s.getKeys(false)) {
            int minimo;
            try {
                minimo = Integer.parseInt(clave.replace(".", "").trim());
            } catch (NumberFormatException e) {
                avisos.add(ruta + "." + clave + ": el escalón tiene que ser un número");
                continue;
            }
            Entrada e = entrada(s.getConfigurationSection(clave), ruta + "." + clave, clave + " " + ruta);
            if (e != null) {
                destino.put(minimo, e);
            }
        }
    }

    private Entrada entrada(ConfigurationSection s, String ruta, String nombrePorDefecto) {
        if (s == null) {
            avisos.add(ruta + ": tiene que tener 'nombre' y 'acciones'");
            return null;
        }
        List<Accion> acciones = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("acciones")) {
            try {
                acciones.add(Accion.desde(m));
            } catch (IllegalArgumentException ex) {
                avisos.add(ruta + ": " + ex.getMessage());
            }
        }
        return new Entrada(s.getString("nombre", nombrePorDefecto), List.copyOf(acciones));
    }

    /** Título de una recompensa sin mayúsculas ni espacios de más, para comparar. */
    static String normalizar(String titulo) {
        return titulo.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * La entrada que corresponde al evento, o null si no hay ninguna (bits bajo el primer escalón,
     * recompensa que no está en el catálogo, raid chica): en ese caso solo se avisa en el chat.
     */
    Entrada elegir(Evento ev) {
        return switch (ev.tipo()) {
            case BITS -> valor(bits.floorEntry(ev.monto()));
            case SUB, SUB_REGALO -> valor(subs.floorEntry(ev.monto()));
            case PUNTOS -> ev.recompensa() == null ? null : puntos.get(normalizar(ev.recompensa()));
            case RAID -> ev.monto() >= minimoRaid ? raid : null;
            case FOLLOW -> follow;
        };
    }

    private static Entrada valor(Map.Entry<Integer, Entrada> e) {
        return e == null ? null : e.getValue();
    }

    long enfriamientoFollowMs() {
        return enfriamientoFollowMs;
    }

    /** Problemas encontrados al leer (para mostrarlos en la consola). */
    List<String> avisos() {
        return Collections.unmodifiableList(avisos);
    }

    /** Todas las entradas, para validar sus acciones contra el servidor. */
    Map<String, Entrada> todas() {
        Map<String, Entrada> m = new java.util.LinkedHashMap<>();
        bits.forEach((k, v) -> m.put("bits." + k, v));
        subs.forEach((k, v) -> m.put("subs." + k, v));
        puntos.forEach((k, v) -> m.put("puntos." + k, v));
        if (raid != null) {
            m.put("raid", raid);
        }
        if (follow != null) {
            m.put("follow", follow);
        }
        return m;
    }

    /** Escalones de bits y subs, y títulos de recompensas (para autocompletar /twitch probar). */
    List<String> ejemplos(Evento.Tipo tipo) {
        return switch (tipo) {
            case BITS -> bits.keySet().stream().map(String::valueOf).toList();
            case SUB, SUB_REGALO -> subs.keySet().stream().map(String::valueOf).toList();
            case PUNTOS -> todas().keySet().stream().filter(k -> k.startsWith("puntos.")).map(k -> k.substring(7)).toList();
            case RAID -> List.of(String.valueOf(minimoRaid), "37", "150");
            case FOLLOW -> List.of();
        };
    }
}
