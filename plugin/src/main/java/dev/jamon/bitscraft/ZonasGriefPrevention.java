package dev.jamon.bitscraft;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;

/** Cualquier claim de GriefPrevention cuenta como protegido. */
final class ZonasGriefPrevention implements Zonas {

    @Override
    public boolean protegido(Location l) {
        return GriefPrevention.instance.dataStore.getClaimAt(l, true, null) != null;
    }
}
