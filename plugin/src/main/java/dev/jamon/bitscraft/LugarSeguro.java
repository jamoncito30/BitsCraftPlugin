package dev.jamon.bitscraft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.ThreadLocalRandom;

/** Busca dónde hacer aparecer algo junto al streamer: en el suelo, nunca dentro de un bloque ni en lava. */
final class LugarSeguro {

    private LugarSeguro() {
    }

    /**
     * Un punto a entre {@code min} y {@code max} bloques del centro. Si {@code volador}, basta con
     * que haya aire (para ghasts, phantoms, wither, dragón), unos bloques más arriba.
     * Si no encuentra nada, devuelve el mismo centro.
     */
    static Location cerca(Location centro, int min, int max, boolean volador) {
        World w = centro.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int intento = 0; intento < 16; intento++) {
            double ang = r.nextDouble(Math.PI * 2);
            double dist = min >= max ? min : min + r.nextDouble(max - min);
            int x = (int) Math.floor(centro.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(centro.getZ() + Math.sin(ang) * dist);
            if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, centro.getY(), z + 0.5))) {
                continue;
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (volador) {
                int y = Math.min(w.getMaxHeight() - 4, centro.getBlockY() + 4 + r.nextInt(4));
                if (libre(w.getBlockAt(x, y, z)) && libre(w.getBlockAt(x, y + 1, z)) && libre(w.getBlockAt(x, y + 2, z))) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
                continue;
            }
            for (int y = centro.getBlockY() + 3; y >= centro.getBlockY() - 6 && y > w.getMinHeight(); y--) {
                if (sePuedePararEn(w, x, y, z)) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return centro.clone();
    }

    /** Pies y cabeza libres, y suelo firme debajo (no líquido, no aire). */
    static boolean sePuedePararEn(World w, int x, int y, int z) {
        Block suelo = w.getBlockAt(x, y - 1, z);
        return libre(w.getBlockAt(x, y, z)) && libre(w.getBlockAt(x, y + 1, z))
                && suelo.getType().isSolid() && !suelo.isLiquid() && suelo.getType() != Material.MAGMA_BLOCK
                && suelo.getType() != Material.CACTUS;
    }

    static boolean libre(Block b) {
        return b.isPassable() && !b.isLiquid() && b.getType() != Material.FIRE && b.getType() != Material.SOUL_FIRE
                && b.getType() != Material.POWDER_SNOW && b.getType() != Material.SWEET_BERRY_BUSH;
    }

    /**
     * Para tp-azar: la altura donde pararse en la columna x,z (el chunk tiene que estar cargado), o
     * Integer.MIN_VALUE si no hay ninguna segura (océano, lava, techo del Nether).
     */
    static int alturaSegura(World w, int x, int z) {
        int techo = w.getEnvironment() == World.Environment.NETHER ? 120 : w.getHighestBlockYAt(x, z) + 1;
        for (int y = Math.min(techo, w.getMaxHeight() - 2); y > w.getMinHeight() + 1; y--) {
            if (sePuedePararEn(w, x, y, z)) {
                return y;
            }
            if (w.getBlockAt(x, y - 1, z).isLiquid()) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }
}
