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

    /** Dueños cuyas zonas puede romper la entidad, o null si no rompe nada. */
    private Set<UUID> permitidos(Entity e) {
        return marcas.rompe(e).duenosPermitidos(marcas.streamer(e), vinculados.get());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        Entity origen = Marcas.origen(e.getEntity());
        if (!marcas.marcada(origen)) {
            return;
        }
        Set<UUID> permitidos = permitidos(origen);
        if (permitidos == null) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(b -> zonas.protegido(b.getLocation(), permitidos));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alCambiarBloque(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (!marcas.marcada(ent)) {
            return;
        }
        Block b = e.getBlock();
        Set<UUID> permitidos = permitidos(ent);
        if (permitidos == null || zonas.protegido(b.getLocation(), permitidos)) {
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
