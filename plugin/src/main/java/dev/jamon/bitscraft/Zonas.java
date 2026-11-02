package dev.jamon.bitscraft;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Zonas protegidas (regiones de WorldGuard, claims de GriefPrevention): ahí las explosiones y los
 * jefes del plugin no rompen bloques, salvo que la zona sea de un dueño permitido (ver {@link Rompe}).
 * Cada integración se carga solo si su plugin está instalado.
 */
interface Zonas {

    /**
     * true si el punto está en una zona protegida cuyo dueño no está en {@code permitidos}
     * (con {@code permitidos} vacío: cualquier zona protegida).
     */
    boolean protegido(Location l, Set<UUID> permitidos);

    static Zonas cargar(Plugin plugin, Logger log) {
        List<Zonas> todas = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        if (plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                todas.add(new ZonasWorldGuard());
                nombres.add("WorldGuard");
            } catch (LinkageError | RuntimeException e) {
                log.warning("WorldGuard está, pero no se pudo usar: " + e);
            }
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("GriefPrevention")) {
            try {
                todas.add(new ZonasGriefPrevention());
                nombres.add("GriefPrevention");
            } catch (LinkageError | RuntimeException e) {
                log.warning("GriefPrevention está, pero no se pudo usar: " + e);
            }
        }
        log.info(nombres.isEmpty() ? "Sin WorldGuard ni GriefPrevention: no hay zonas protegidas."
                : "Zonas protegidas de: " + String.join(", ", nombres));
        return (l, permitidos) -> {
            for (Zonas z : todas) {
                if (z.protegido(l, permitidos)) {
                    return true;
                }
            }
            return false;
        };
    }
}
