package dev.jamon.bitscraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Fox;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Ejecuta las acciones del catálogo sobre un streamer. Solo desde el hilo principal. */
final class Ejecutor {

    private static final Set<EntityType> VOLADORES = Set.of(EntityType.GHAST, EntityType.PHANTOM, EntityType.WITHER,
            EntityType.ENDER_DRAGON, EntityType.BLAZE, EntityType.VEX, EntityType.BAT, EntityType.PARROT, EntityType.BEE,
            EntityType.ALLAY, EntityType.HAPPY_GHAST);

    private final Plugin plugin;
    private final Marcas marcas;
    private final LongSupplier reloj;
    private Ritmo ritmo;
    private Set<Material> quitables;
    private final Map<UUID, BukkitTask> noches = new HashMap<>();

    Ejecutor(Plugin plugin, Marcas marcas, LongSupplier reloj, Ritmo ritmo, Set<Material> quitables) {
        this.plugin = plugin;
        this.marcas = marcas;
        this.reloj = reloj;
        this.ritmo = ritmo;
        this.quitables = quitables;
    }

    void configurar(Ritmo ritmo, Set<Material> quitables) {
        this.ritmo = ritmo;
        this.quitables = quitables;
    }

    /** Lo que pasó al ejecutar: mobs que no aparecieron por el tope, y problemas de configuración. */
    static final class Resultado {
        int mobsSaltados;
        final List<String> problemas = new ArrayList<>();
    }

    Resultado ejecutar(Player p, Catalogo.Entrada entrada, Evento ev, String canal) {
        Resultado r = new Resultado();
        Map<String, String> valores = Placeholders.de(p.getName(), ev, canal);
        for (Accion a : entrada.acciones()) {
            ejecutar(p, a, ev, valores, r);
        }
        return r;
    }

    private void ejecutar(Player p, Accion a, Evento ev, Map<String, String> valores, Resultado r) {
        try {
            switch (a.tipo()) {
                case "mob" -> mobs(p, a, ev, valores, Marcas.Clase.MOB, r);
                case "jefe" -> mobs(p, a, ev, valores, Marcas.Clase.JEFE, r);
                case "mascota" -> mobs(p, a, ev, valores, Marcas.Clase.MASCOTA, r);
                case "efecto" -> efecto(p, a, r);
                case "dar" -> dar(p, a, r);
                case "quitar" -> quitar(p, a, r);
                case "rayo" -> rayo(p, a);
                case "tnt" -> tnt(p, a);
                case "nuke" -> nuke(p, a);
                case "lanzar" -> lanzar(p, a);
                case "tp-azar" -> tpAzar(p, a, 0);
                case "noche" -> noche(p, a);
                case "sonido" -> p.playSound(Sound.sound(Key.key(a.texto("sonido", "entity.player.levelup")),
                        Sound.Source.MASTER, (float) a.decimal("volumen", 1), (float) a.decimal("tono", 1)));
                case "particulas" -> particulas(p, a, r);
                case "fuegos" -> fuegos(p, a);
                case "comando" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        Placeholders.aplicar(a.texto("comando", ""), valores));
                case "aleatorio" -> ejecutar(p, a.opcionesAleatorias().get(
                        ThreadLocalRandom.current().nextInt(a.opcionesAleatorias().size())), ev, valores, r);
                default -> r.problemas.add("tipo de acción desconocido: " + a.tipo());
            }
        } catch (RuntimeException e) {
            r.problemas.add(a.tipo() + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            plugin.getLogger().warning("Falló la acción " + a.tipo() + " sobre " + p.getName() + ": " + e);
        }
    }

    // ---- validación (al cargar la config) ----

    /** Revisa nombres de mobs, ítems y efectos de todo el catálogo. Devuelve los problemas. */
    List<String> validar(Catalogo c) {
        List<String> problemas = new ArrayList<>();
        for (Map.Entry<String, Catalogo.Entrada> e : c.todas().entrySet()) {
            for (Accion a : e.getValue().acciones()) {
                validar(a, e.getKey(), problemas);
            }
        }
        return problemas;
    }

    private void validar(Accion a, String ruta, List<String> problemas) {
        switch (a.tipo()) {
            case "mob", "jefe", "mascota" -> {
                EntityType t = tipoMob(a.texto("mob", ""));
                if (t == null) {
                    problemas.add(ruta + ": mob desconocido '" + a.texto("mob", "") + "'");
                }
            }
            case "efecto" -> {
                if (efecto(a.texto("efecto", "")) == null) {
                    problemas.add(ruta + ": efecto desconocido '" + a.texto("efecto", "") + "'");
                }
            }
            case "dar", "quitar" -> {
                Material m = Material.matchMaterial(a.texto("item", ""));
                if (m == null || !m.isItem()) {
                    problemas.add(ruta + ": ítem desconocido '" + a.texto("item", "") + "'");
                } else if (a.tipo().equals("quitar") && !quitables.contains(m)) {
                    problemas.add(ruta + ": '" + m + "' no está en quitar-permitidos (no se va a quitar)");
                }
            }
            case "particulas" -> {
                if (particula(a.texto("particula", "")) == null) {
                    problemas.add(ruta + ": partícula desconocida '" + a.texto("particula", "") + "'");
                }
            }
            case "aleatorio" -> a.opcionesAleatorias().forEach(o -> validar(o, ruta, problemas));
            case "rayo", "tnt", "nuke", "lanzar", "tp-azar", "noche", "sonido", "fuegos", "comando" -> {
            }
            default -> problemas.add(ruta + ": tipo de acción desconocido '" + a.tipo() + "'");
        }
    }

    private static NamespacedKey clave(String id) {
        return NamespacedKey.fromString(id.trim().toLowerCase(Locale.ROOT));
    }

    static EntityType tipoMob(String id) {
        NamespacedKey k = clave(id);
        EntityType t = k == null ? null : Registry.ENTITY_TYPE.get(k);
        return t != null && t.isSpawnable() && t.isAlive() && t != EntityType.PLAYER ? t : null;
    }

    private static PotionEffectType efecto(String id) {
        NamespacedKey k = clave(id);
        return k == null ? null : Registry.MOB_EFFECT.get(k);
    }

    private static Particle particula(String id) {
        NamespacedKey k = clave(id);
        return k == null ? null : Registry.PARTICLE_TYPE.get(k);
    }

    // ---- acciones ----

    private void mobs(Player p, Accion a, Evento ev, Map<String, String> valores, Marcas.Clase clase, Resultado r) {
        EntityType tipo = tipoMob(a.texto("mob", ""));
        if (tipo == null) {
            r.problemas.add("mob desconocido '" + a.texto("mob", "") + "'");
            return;
        }
        boolean volador = VOLADORES.contains(tipo);
        int[] dist = a.rango("distancia", volador ? 6 : 3, volador ? 10 : 6);
        int cantidad = a.cantidad(ev.monto());
        String nombre = a.texto("nombre", "");
        long vida = clase == Marcas.Clase.JEFE ? ritmo.jefeVidaMs() : ritmo.mobVidaMs();
        vida = a.opciones().containsKey("vida-segundos") ? a.entero("vida-segundos", 300) * 1000L : vida;
        boolean rompe = a.si("rompe-bloques", tipo == EntityType.CREEPER);
        for (int i = 0; i < cantidad; i++) {
            if (clase != Marcas.Clase.MASCOTA && marcas.vivos(p.getUniqueId()) >= ritmo.mobsVivos()) {
                r.mobsSaltados += cantidad - i;
                return;
            }
            Location lugar = LugarSeguro.cerca(p.getLocation(), dist[0], dist[1], volador);
            long vidaFinal = vida;
            Entity e = p.getWorld().spawnEntity(lugar, tipo, CreatureSpawnEvent.SpawnReason.CUSTOM, ent -> {
                marcas.marcar(ent, p.getUniqueId(), clase, rompe, false, vidaFinal, reloj.getAsLong());
                if (clase != Marcas.Clase.MASCOTA) {
                    ent.setPersistent(false);
                }
                if (!nombre.isEmpty()) {
                    ent.customName(Component.text(Placeholders.aplicar(nombre, valores), NamedTextColor.LIGHT_PURPLE));
                    ent.setCustomNameVisible(true);
                }
                preparar(ent, p, a, clase);
            });
            if (clase == Marcas.Clase.MOB || clase == Marcas.Clase.JEFE) {
                if (e instanceof Mob m && (e instanceof Enemy || a.si("enojada", false))) {
                    m.setTarget(p);
                }
                if (e instanceof EnderDragon d) {
                    d.setPhase(EnderDragon.Phase.CIRCLING);
                }
            }
        }
    }

    private void preparar(Entity ent, Player p, Accion a, Marcas.Clase clase) {
        if (ent instanceof Creeper c && a.si("cargado", false)) {
            c.setPowered(true);
        }
        if (ent instanceof Slime s && a.opciones().containsKey("tamano")) {
            s.setSize(Math.max(1, Math.min(8, a.entero("tamano", 2))));
        }
        if (ent instanceof Bee b && clase != Marcas.Clase.MASCOTA && a.si("enojada", false)) {
            b.setAnger(20 * 60);
        }
        if (clase == Marcas.Clase.MASCOTA) {
            if (ent instanceof Tameable t) {
                t.setTamed(true);
                t.setOwner(p);
            }
            if (ent instanceof Fox f) {
                f.setFirstTrustedPlayer(p);
            }
            if (ent instanceof LivingEntity le) {
                le.setRemoveWhenFarAway(false);
            }
            return;
        }
        if (ent instanceof LivingEntity le) {
            EntityEquipment eq = le.getEquipment();
            String armadura = a.texto("armadura", "");
            if (eq != null && !armadura.isEmpty()) {
                String m = switch (armadura.toLowerCase(Locale.ROOT)) {
                    case "cuero" -> "LEATHER";
                    case "oro" -> "GOLDEN";
                    case "malla", "cota" -> "CHAINMAIL";
                    case "diamante" -> "DIAMOND";
                    case "netherita" -> "NETHERITE";
                    default -> "IRON";
                };
                eq.setHelmet(new ItemStack(Material.valueOf(m + "_HELMET")));
                eq.setChestplate(new ItemStack(Material.valueOf(m + "_CHESTPLATE")));
                eq.setLeggings(new ItemStack(Material.valueOf(m + "_LEGGINGS")));
                eq.setBoots(new ItemStack(Material.valueOf(m + "_BOOTS")));
            }
            if (eq != null) {
                eq.setHelmetDropChance(0);
                eq.setChestplateDropChance(0);
                eq.setLeggingsDropChance(0);
                eq.setBootsDropChance(0);
                eq.setItemInMainHandDropChance(0);
                eq.setItemInOffHandDropChance(0);
            }
        }
    }

    private void efecto(Player p, Accion a, Resultado r) {
        PotionEffectType t = efecto(a.texto("efecto", ""));
        if (t == null) {
            r.problemas.add("efecto desconocido '" + a.texto("efecto", "") + "'");
            return;
        }
        p.addPotionEffect(new PotionEffect(t, Math.max(1, a.entero("segundos", 5)) * 20,
                Math.max(0, a.entero("nivel", 1) - 1)));
    }

    private void dar(Player p, Accion a, Resultado r) {
        Material m = Material.matchMaterial(a.texto("item", ""));
        if (m == null || !m.isItem()) {
            r.problemas.add("ítem desconocido '" + a.texto("item", "") + "'");
            return;
        }
        int cantidad = Math.max(1, a.entero("cantidad", 1));
        while (cantidad > 0) {
            int n = Math.min(cantidad, m.getMaxStackSize());
            cantidad -= n;
            p.getInventory().addItem(new ItemStack(m, n)).values()
                    .forEach(sobra -> p.getWorld().dropItemNaturally(p.getLocation(), sobra));
        }
    }

    private void quitar(Player p, Accion a, Resultado r) {
        Material m = Material.matchMaterial(a.texto("item", ""));
        if (m == null || !quitables.contains(m)) {
            r.problemas.add("'" + a.texto("item", "") + "' no está en quitar-permitidos");
            return;
        }
        p.getInventory().removeItem(new ItemStack(m, Math.max(1, a.entero("cantidad", 1))));
    }

    private void rayo(Player p, Accion a) {
        int d = a.entero("distancia", 3);
        Location l = d <= 0 ? p.getLocation() : LugarSeguro.cerca(p.getLocation(), d, d, false);
        if (a.si("dano", true)) {
            Entity rayo = p.getWorld().strikeLightning(l);
            marcas.marcar(rayo, p.getUniqueId(), Marcas.Clase.EFECTO, false, a.si("fuego", false), 0, reloj.getAsLong());
        } else {
            p.getWorld().strikeLightningEffect(l);
        }
    }

    private void tnt(Player p, Accion a) {
        int d = a.entero("distancia", 3);
        for (int i = 0; i < Math.max(1, a.entero("cantidad", 1)); i++) {
            Location l = d <= 0 ? p.getLocation() : LugarSeguro.cerca(p.getLocation(), d, d + 1, false);
            prenderTnt(p, l, a.entero("mecha-ticks", 80), a.si("rompe-bloques", false));
        }
    }

    private void prenderTnt(Player p, Location l, int mecha, boolean rompe) {
        p.getWorld().spawn(l, TNTPrimed.class, t -> {
            t.setFuseTicks(Math.max(10, mecha));
            marcas.marcar(t, p.getUniqueId(), Marcas.Clase.EXPLOSIVO, rompe, false, 0, reloj.getAsLong());
        });
    }

    private void nuke(Player p, Accion a) {
        int lado = Math.max(1, Math.min(30, a.entero("lado", 10)));
        int mecha = a.entero("mecha-ticks", 80);
        boolean rompe = a.si("rompe-bloques", false);
        Location c = p.getLocation();
        World w = p.getWorld();
        double inicio = -(lado - 1) / 2.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < lado; i++) {
            for (int j = 0; j < lado; j++) {
                Location l = new Location(w, c.getX() + inicio + i, Math.min(w.getMaxHeight() - 2, c.getY() + 5), c.getZ() + inicio + j);
                prenderTnt(p, l, mecha + r.nextInt(20), rompe);
            }
        }
    }

    private void lanzar(Player p, Accion a) {
        Location l = p.getLocation();
        World w = p.getWorld();
        int suelo = Math.max(l.getBlockY(), w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1);
        double y = Math.min(w.getMaxHeight() - 2, suelo + Math.max(5, a.entero("altura", 50)));
        Location destino = l.clone();
        destino.setY(y);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        p.setFallDistance(0);
        p.teleport(destino);
        if (a.si("balde", true)) {
            ponerEnMano(p, new ItemStack(Material.WATER_BUCKET));
        }
    }

    /** Pone el ítem en la mano, moviendo lo que tenía a un espacio libre. Si no hay espacio, no lo da. */
    private void ponerEnMano(Player p, ItemStack item) {
        PlayerInventory inv = p.getInventory();
        ItemStack mano = inv.getItemInMainHand();
        if (mano.getType().isAir()) {
            inv.setItemInMainHand(item);
            return;
        }
        int libre = inv.firstEmpty();
        if (libre >= 0) {
            inv.setItem(libre, mano);
            inv.setItemInMainHand(item);
        } else {
            p.sendMessage(Component.text("No tenías espacio para el balde de agua. ¡Suerte!", NamedTextColor.RED));
        }
    }

    private void tpAzar(Player p, Accion a, int intento) {
        if (intento >= 10 || !p.isOnline()) {
            if (intento >= 10) {
                p.sendMessage(Component.text("No se encontró un lugar seguro para el teletransporte.", NamedTextColor.GRAY));
            }
            return;
        }
        World w = p.getWorld();
        int d = Math.max(16, a.entero("distancia", 1000));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble(Math.PI * 2);
        double dist = d * (0.9 + r.nextDouble(0.2));
        int x = (int) Math.floor(p.getLocation().getX() + Math.cos(ang) * dist);
        int z = (int) Math.floor(p.getLocation().getZ() + Math.sin(ang) * dist);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) {
            tpAzar(p, a, intento + 1);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            int y = LugarSeguro.alturaSegura(w, x, z);
            if (y == Integer.MIN_VALUE) {
                tpAzar(p, a, intento + 1);
                return;
            }
            Location destino = new Location(w, x + 0.5, y, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
            p.setFallDistance(0);
            p.teleportAsync(destino);
        });
    }

    private void noche(Player p, Accion a) {
        BukkitTask previa = noches.remove(p.getUniqueId());
        if (previa != null) {
            previa.cancel();
        }
        p.setPlayerTime(18000, false);
        UUID id = p.getUniqueId();
        noches.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            noches.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (q != null) {
                q.resetPlayerTime();
            }
        }, Math.max(1, a.entero("segundos", 60)) * 20L));
    }

    /** Al apagar o al salir el streamer: deja la hora normal. */
    void terminarNoche(Player p) {
        BukkitTask t = noches.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            p.resetPlayerTime();
        }
    }

    void terminarNoches() {
        for (UUID id : List.copyOf(noches.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                terminarNoche(p);
            }
        }
        noches.clear();
    }

    private void particulas(Player p, Accion a, Resultado r) {
        Particle t = particula(a.texto("particula", "heart"));
        if (t == null) {
            r.problemas.add("partícula desconocida '" + a.texto("particula", "") + "'");
            return;
        }
        if (t.getDataType() != Void.class) {
            r.problemas.add("la partícula '" + a.texto("particula", "") + "' necesita datos extra; usa otra");
            return;
        }
        p.getWorld().spawnParticle(t, p.getLocation().add(0, 1.2, 0), Math.max(1, a.entero("cantidad", 10)), 0.6, 0.8, 0.6, 0);
    }

    private void fuegos(Player p, Accion a) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Color[] colores = {Color.FUCHSIA, Color.PURPLE, Color.AQUA, Color.YELLOW, Color.LIME, Color.ORANGE};
        for (int i = 0; i < Math.max(1, Math.min(10, a.entero("cantidad", 1))); i++) {
            Location l = LugarSeguro.cerca(p.getLocation(), 3, 5, true);
            p.getWorld().spawn(l, Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.values()[r.nextInt(FireworkEffect.Type.values().length)])
                        .withColor(colores[r.nextInt(colores.length)], colores[r.nextInt(colores.length)])
                        .flicker(r.nextBoolean()).trail(true).build());
                meta.setPower(1);
                f.setFireworkMeta(meta);
                marcas.marcar(f, p.getUniqueId(), Marcas.Clase.EFECTO, false, false, 0, reloj.getAsLong());
            });
        }
    }

    /** Tipos con tope por minuto que dispararía una entrada (para la fila). */
    static Set<String> tiposConTope(Catalogo.Entrada e) {
        Set<String> s = new java.util.HashSet<>();
        if (e != null) {
            e.acciones().forEach(a -> a.tiposConTope(s));
        }
        return s;
    }
}
