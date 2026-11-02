package dev.jamon.bitscraft;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

/** Cualquier región de WorldGuard (fuera de __global__) cuenta como protegida. */
final class ZonasWorldGuard implements Zonas {

    private final RegionQuery consulta = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();

    @Override
    public boolean protegido(Location l) {
        return consulta.getApplicableRegions(BukkitAdapter.adapt(l)).size() > 0;
    }
}
