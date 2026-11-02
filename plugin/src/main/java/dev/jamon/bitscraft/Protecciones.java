package dev.jamon.bitscraft;

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

/**
 * Lo que impide que las donaciones arruinen bases o se puedan farmear: explosiones y jefes del
 * plugin no rompen bloques en zonas protegidas (ni en ningún lado si rompe-bloques es false),
 * los rayos no prenden fuego, los mobs no sueltan ítems ni experiencia y los fuegos artificiales
 * no hacen daño.
 */
final class Protecciones implements Listener {

    private final Marcas marcas;
    private final Zonas zonas;

    Protecciones(Marcas marcas, Zonas zonas) {
        this.marcas = marcas;
        this.zonas = zonas;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        Entity origen = Marcas.origen(e.getEntity());
        if (!marcas.marcada(origen)) {
            return;
        }
        if (!marcas.rompe(origen)) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(b -> zonas.protegido(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alCambiarBloque(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (!marcas.marcada(ent)) {
            return;
        }
        Block b = e.getBlock();
        if (!marcas.rompe(ent) || zonas.protegido(b.getLocation())) {
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
