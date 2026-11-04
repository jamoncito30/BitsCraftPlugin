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
    // TikTok LIVE
    private final Map<String, Entrada> regalos = new java.util.HashMap<>();
    private final Map<String, Integer> regalosComoBits = new java.util.HashMap<>();
    private double bitsPorMoneda = 1;
    private Entrada tiktokFollow;
    private Entrada tiktokShare;
    private Entrada tiktokLikes;
    private final Map<Evento.Tipo, Long> enfriamientos = new java.util.EnumMap<>(Evento.Tipo.class);
    private int likesCada = 500;
    private final List<String> avisos = new ArrayList<>();

    static Catalogo leer(ConfigurationSection seccion) {
        return leer(seccion, 1);
    }

    /** @param bitsPorMoneda a cuántos bits equivale una moneda de TikTok (tiktok.bits-por-moneda) */
    static Catalogo leer(ConfigurationSection seccion, double bitsPorMoneda) {
        Catalogo c = new Catalogo();
        if (bitsPorMoneda > 0 && Double.isFinite(bitsPorMoneda)) {
            c.bitsPorMoneda = bitsPorMoneda;
        } else {
            c.avisos.add("tiktok.bits-por-moneda tiene que ser mayor que 0; se usa 1");
        }
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
            c.enfriamientos.put(Evento.Tipo.FOLLOW, c.enfriamientoFollowMs);
        }
        c.leerTikTok(seccion);
        return c;
    }

    private void leerTikTok(ConfigurationSection seccion) {
        ConfigurationSection r = seccion.getConfigurationSection("tiktok-regalos");
        if (r != null) {
            for (String regalo : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(regalo);
                if (s != null && s.contains("como-bits")) {
                    int b = s.getInt("como-bits", -1);
                    if (b < 0) {
                        avisos.add("tiktok-regalos." + regalo + ": como-bits tiene que ser un número");
                    } else {
                        regalosComoBits.put(normalizar(regalo), b);
                    }
                    continue;
                }
                Entrada e = entrada(s, "tiktok-regalos." + regalo, regalo);
                if (e != null) {
                    regalos.put(normalizar(regalo), e);
                }
            }
        }
        tiktokFollow = opcional(seccion, "tiktok-follow", "Follow en TikTok", Evento.Tipo.TIKTOK_FOLLOW, 30);
        tiktokShare = opcional(seccion, "tiktok-share", "Compartió el directo", Evento.Tipo.TIKTOK_SHARE, 60);
        tiktokLikes = opcional(seccion, "tiktok-likes", "Likes", Evento.Tipo.TIKTOK_LIKES, 60);
        ConfigurationSection l = seccion.getConfigurationSection("tiktok-likes");
        if (l != null) {
            likesCada = Math.max(1, l.getInt("cada", 500));
        }
    }

    /** follow, share y likes de TikTok: apagados si no están o si tienen activo: false. */
    private Entrada opcional(ConfigurationSection seccion, String clave, String nombre, Evento.Tipo tipo, long enfriamientoSeg) {
        ConfigurationSection s = seccion.getConfigurationSection(clave);
        if (s == null || !s.getBoolean("activo", true)) {
            return null;
        }
        enfriamientos.put(tipo, Math.max(0, s.getLong("enfriamiento-segundos", enfriamientoSeg)) * 1000L);
        return entrada(s, clave, nombre);
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
            case TIKTOK_REGALO -> regalo(ev);
            case TIKTOK_FOLLOW -> tiktokFollow;
            case TIKTOK_SHARE -> tiktokShare;
            case TIKTOK_LIKES -> tiktokLikes;
        };
    }

    /** Un regalo de TikTok: primero por su nombre; si no tiene entrada, por su valor en bits. */
    private Entrada regalo(Evento ev) {
        String nombre = ev.recompensa() == null ? "" : normalizar(ev.recompensa());
        Entrada propia = regalos.get(nombre);
        if (propia != null) {
            return propia;
        }
        return valor(bits.floorEntry(bitsDeRegalo(ev)));
    }

    /** Cuántos bits vale un regalo de TikTok (para los escalones de bits). */
    int bitsDeRegalo(Evento ev) {
        String nombre = ev.recompensa() == null ? "" : normalizar(ev.recompensa());
        Integer comoBits = regalosComoBits.get(nombre);
        long b = comoBits != null ? (long) comoBits * Math.max(1, ev.cantidad())
                : (long) Math.floor(ev.monto() * bitsPorMoneda);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, b));
    }

    /** Enfriamiento por streamer del follow de Twitch y del follow, share y likes de TikTok (0 = sin). */
    long enfriamientoMs(Evento.Tipo tipo) {
        return enfriamientos.getOrDefault(tipo, 0L);
    }

    /** Cada cuántos likes de TikTok se dispara la entrada tiktok-likes. */
    int likesCada() {
        return likesCada;
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
        regalos.forEach((k, v) -> m.put("tiktok-regalos." + k, v));
        if (tiktokFollow != null) {
            m.put("tiktok-follow", tiktokFollow);
        }
        if (tiktokShare != null) {
            m.put("tiktok-share", tiktokShare);
        }
        if (tiktokLikes != null) {
            m.put("tiktok-likes", tiktokLikes);
        }
        return m;
    }

    /** Escalones de bits y subs, títulos de recompensas y regalos (para autocompletar /bits probar). */
    List<String> ejemplos(Evento.Tipo tipo) {
        return switch (tipo) {
            case BITS -> bits.keySet().stream().map(String::valueOf).toList();
            case SUB, SUB_REGALO -> subs.keySet().stream().map(String::valueOf).toList();
            case PUNTOS -> todas().keySet().stream().filter(k -> k.startsWith("puntos.")).map(k -> k.substring(7)).toList();
            case RAID -> List.of(String.valueOf(minimoRaid), "37", "150");
            case TIKTOK_REGALO -> {
                List<String> l = new ArrayList<>(List.of("Rose", "Lion", "Universe"));
                regalos.keySet().forEach(l::add);
                regalosComoBits.keySet().forEach(l::add);
                yield l;
            }
            case TIKTOK_LIKES -> List.of(String.valueOf(likesCada));
            case FOLLOW, TIKTOK_FOLLOW, TIKTOK_SHARE -> List.of();
        };
    }
}
