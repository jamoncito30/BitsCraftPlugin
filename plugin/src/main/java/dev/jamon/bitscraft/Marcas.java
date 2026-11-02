package dev.jamon.bitscraft;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Las entidades que crea el plugin llevan una marca con el streamer al que pertenecen: así se
 * cuentan los mobs vivos, se les quitan los drops, se borran a su hora y se decide si sus
 * explosiones rompen bloques. Solo se usa desde el hilo principal.
 */
final class Marcas {

    /** Cómo se trata una entidad marcada. */
    enum Clase { MOB, JEFE, MASCOTA, EXPLOSIVO, EFECTO }

    private record Seguida(UUID streamer, long vence) {
    }

    private final NamespacedKey claveStreamer;
    private final NamespacedKey claveClase;
    private final NamespacedKey claveRompe;
    private final NamespacedKey claveFuego;
    private final Map<UUID, Seguida> seguidas = new java.util.HashMap<>();

    Marcas(Plugin plugin) {
        claveStreamer = new NamespacedKey(plugin, "streamer");
        claveClase = new NamespacedKey(plugin, "clase");
        claveRompe = new NamespacedKey(plugin, "rompe");
        claveFuego = new NamespacedKey(plugin, "fuego");
    }

    /** Marca la entidad. Los mobs y jefes se siguen para borrarlos a los {@code vidaMs}. */
    void marcar(Entity e, UUID streamer, Clase clase, boolean rompe, boolean fuego, long vidaMs, long ahora) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(claveStreamer, PersistentDataType.STRING, streamer.toString());
        pdc.set(claveClase, PersistentDataType.STRING, clase.name());
        pdc.set(claveRompe, PersistentDataType.BOOLEAN, rompe);
        pdc.set(claveFuego, PersistentDataType.BOOLEAN, fuego);
        if (clase == Clase.MOB || clase == Clase.JEFE) {
            seguidas.put(e.getUniqueId(), new Seguida(streamer, ahora + vidaMs));
        }
    }

    /** La entidad que manda: la misma, o quien disparó el proyectil (bola del ghast, cráneo del wither). */
    static Entity origen(Entity e) {
        if (e instanceof Projectile p) {
            ProjectileSource s = p.getShooter();
            if (s instanceof Entity tirador) {
                return tirador;
            }
        }
        return e;
    }

    boolean marcada(Entity e) {
        return e != null && e.getPersistentDataContainer().has(claveStreamer, PersistentDataType.STRING);
    }

    Clase clase(Entity e) {
        String c = e.getPersistentDataContainer().get(claveClase, PersistentDataType.STRING);
        try {
            return c == null ? null : Clase.valueOf(c);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    boolean rompe(Entity e) {
        return Boolean.TRUE.equals(e.getPersistentDataContainer().get(claveRompe, PersistentDataType.BOOLEAN));
    }

    boolean fuego(Entity e) {
        return Boolean.TRUE.equals(e.getPersistentDataContainer().get(claveFuego, PersistentDataType.BOOLEAN));
    }

    /** Mobs y jefes del plugin vivos de un streamer. */
    int vivos(UUID streamer) {
        int n = 0;
        for (Iterator<Map.Entry<UUID, Seguida>> it = seguidas.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Seguida> s = it.next();
            Entity e = Bukkit.getEntity(s.getKey());
            if (e == null || !e.isValid()) {
                it.remove();
            } else if (s.getValue().streamer().equals(streamer)) {
                n++;
            }
        }
        return n;
    }

    /** Borra los mobs a los que se les acabó el tiempo. */
    void limpiar(long ahora) {
        for (Iterator<Map.Entry<UUID, Seguida>> it = seguidas.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Seguida> s = it.next();
            if (ahora < s.getValue().vence()) {
                continue;
            }
            Entity e = Bukkit.getEntity(s.getKey());
            if (e != null) {
                e.remove();
            }
            it.remove();
        }
    }

    /** Al apagar: borra todos los mobs del plugin que siguen vivos. */
    void borrarTodos() {
        limpiar(Long.MAX_VALUE);
    }
}
