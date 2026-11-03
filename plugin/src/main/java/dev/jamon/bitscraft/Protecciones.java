package dev.jamon.bitscraft;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LightningStrike;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SlimeSplitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lo que impide que las donaciones arruinen bases o se puedan farmear: explosiones y jefes del
 * plugin no rompen bloques en zonas protegidas ajenas (ver {@link Rompe}),
 * los rayos no prenden fuego, los mobs no sueltan ítems ni experiencia y los fuegos artificiales
 * no hacen daño.
 */
final class Protecciones implements Listener {

    private final Marcas marcas;
    private final Zonas zonas;
    private final Supplier<Set<UUID>> vinculados;

    Protecciones(Marcas marcas, Zonas zonas, Supplier<Set<UUID>> vinculados) {
        this.marcas = marcas;
        this.zonas = zonas;
        this.vinculados = vinculados;
    }

    /** Si la entidad (marcada) puede romper el bloque que está en {@code l}. */
    private boolean puedeRomper(Entity e, Location l, Set<UUID> v) {
        Rompe r = marcas.rompe(e);
        return r != Rompe.NO && r.permite(zonas.enZona(l), permitidos -> zonas.protegido(l, permitidos), marcas.streamer(e), v);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        Entity origen = Marcas.origen(e.getEntity());
        if (!marcas.marcada(origen)) {
            return;
        }
        if (marcas.rompe(origen) == Rompe.NO) {
            e.blockList().clear();
        } else {
            Set<UUID> v = vinculados.get();
            e.blockList().removeIf(b -> !puedeRomper(origen, b.getLocation(), v));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alCambiarBloque(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (!marcas.marcada(ent)) {
            return;
        }
        Block b = e.getBlock();
        if (!puedeRomper(ent, b.getLocation(), vinculados.get())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alPrender(BlockIgniteEvent e) {
        if (e.getCause() == BlockIgniteEvent.IgniteCause.LIGHTNING && e.getIgnitingEntity() instanceof LightningStrike r
                && marcas.marcada(r) && !marcas.fuego(r)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alDanar(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && marcas.marcada(f)) {
            e.setCancelled(true);
        }
    }

    /** Los slimes y cubos de magma del plugin no se dividen: los hijos no tendrían marca y se podrían farmear. */
    @EventHandler(ignoreCancelled = true)
    public void alDividir(SlimeSplitEvent e) {
        if (marcas.marcada(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void alMorir(EntityDeathEvent e) {
        Entity ent = e.getEntity();
        if (!marcas.marcada(ent) || marcas.clase(ent) == Marcas.Clase.MASCOTA) {
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
    }
}
