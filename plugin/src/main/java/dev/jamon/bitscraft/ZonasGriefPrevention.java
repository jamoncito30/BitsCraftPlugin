package dev.jamon.bitscraft;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/** Cualquier claim de GriefPrevention cuenta como protegido, salvo los de un dueño permitido (los de admin, siempre). */
final class ZonasGriefPrevention implements Zonas {

    @Override
    public boolean protegido(Location l, Set<UUID> permitidos) {
        Claim c = GriefPrevention.instance.dataStore.getClaimAt(l, true, null);
        if (c == null) {
            return false;
        }
        UUID dueno = c.getOwnerID();
        return dueno == null || !permitidos.contains(dueno);
    }
}
