package dev.jamon.bitscraft;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/** Cualquier región de WorldGuard (fuera de __global__) cuenta como protegida, salvo las de un dueño permitido. */
final class ZonasWorldGuard implements Zonas {

    private final RegionQuery consulta = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();

    @Override
    public boolean protegido(Location l, Set<UUID> permitidos) {
        for (ProtectedRegion r : consulta.getApplicableRegions(BukkitAdapter.adapt(l))) {
            if (permitidos.stream().noneMatch(id -> r.getOwners().contains(id))) {
                return true;
            }
        }
        return false;
    }
}
