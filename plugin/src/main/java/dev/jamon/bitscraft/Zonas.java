package dev.jamon.bitscraft;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Zonas protegidas de un plugin de protección (hoy WorldGuard y GriefPrevention): sirven para decidir
 * dónde rompen bloques las explosiones y los jefes del plugin (ver {@link Rompe}). Cada integración se
 * carga solo si su plugin está instalado; para otro plugin (de parcelas, por ejemplo) basta una clase
 * más que implemente esta interfaz y una línea en {@link #cargar}.
 */
interface Zonas {

    /**
     * true si el punto está en una zona protegida cuyo dueño no está en {@code permitidos}
     * (con {@code permitidos} vacío: cualquier zona protegida).
     */
    boolean protegido(Location l, Set<UUID> permitidos);

    /** true si el punto está dentro de alguna zona protegida, sea de quien sea. */
    default boolean enZona(Location l) {
        return protegido(l, Set.of());
    }

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
        return new Zonas() {
            @Override
            public boolean protegido(Location l, Set<UUID> permitidos) {
                for (Zonas z : todas) {
                    if (z.protegido(l, permitidos)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean enZona(Location l) {
                for (Zonas z : todas) {
                    if (z.enZona(l)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
