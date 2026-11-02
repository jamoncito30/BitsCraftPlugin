package dev.jamon.bitscraft;

import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * BitsCraftPlugin: lo que pasa en el canal de Twitch de cada streamer (bits, subs, puntos, raids,
 * follows) le pasa a él en el juego. Ver PLAN.md y README.md en la raíz del repo.
 */
public final class BitsCraftPlugin extends JavaPlugin implements Listener {

    private final LongSupplier reloj = System::currentTimeMillis;
    private ScheduledThreadPoolExecutor hilos;
    private Marcas marcas;
    private Ejecutor ejecutor;
    private Streamers streamers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AtomicInteger n = new AtomicInteger();
        hilos = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "BitsCraft-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        hilos.setRemoveOnCancelPolicy(true);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        AlmacenTokens almacen;
        GuardadoFilas guardado;
        try {
            almacen = new AlmacenTokens(getDataFolder().toPath().resolve("tokens"));
            guardado = new GuardadoFilas(getDataFolder().toPath().resolve("filas"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "No se pudo crear la carpeta de datos; el plugin queda apagado.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        marcas = new Marcas(this);
        ejecutor = new Ejecutor(this, marcas, reloj, Ritmo.leer(getConfig().getConfigurationSection("ritmo")), Set.of());
        streamers = new Streamers(this, reloj, hilos, http, almacen, guardado, marcas, ejecutor);
        aplicarConfig();
        streamers.iniciar();

        getServer().getPluginManager().registerEvents(new Protecciones(marcas, Zonas.cargar(this, getLogger()), streamers::vinculados), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("twitch");
        if (cmd != null) {
            ComandoTwitch c = new ComandoTwitch(streamers, this::recargar, reloj);
            cmd.setExecutor(c);
            cmd.setTabCompleter(c);
        }
        getServer().getScheduler().runTaskTimer(this, streamers::tick, 20L, 10L);
        if (getConfig().getString("twitch.client-id", "").isBlank()) {
            getLogger().warning("Falta twitch.client-id en config.yml: nadie va a poder vincular su canal (ver README).");
        }
    }

    @Override
    public void onDisable() {
        if (streamers != null) {
            streamers.apagar();
        }
        if (ejecutor != null) {
            ejecutor.terminarNoches();
        }
        if (marcas != null) {
            marcas.borrarTodos();
        }
        if (hilos != null) {
            hilos.shutdownNow();
            try {
                hilos.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recargar() {
        reloadConfig();
        aplicarConfig();
    }

    private void aplicarConfig() {
        FileConfiguration c = getConfig();
        Ritmo ritmo = Ritmo.leer(c.getConfigurationSection("ritmo"));
        Set<Material> quitables = new HashSet<>();
        for (String m : c.getStringList("quitar-permitidos")) {
            Material mat = Material.matchMaterial(m);
            if (mat == null) {
                getLogger().warning("quitar-permitidos: ítem desconocido '" + m + "'");
            } else {
                quitables.add(mat);
            }
        }
        ejecutor.configurar(ritmo, Set.copyOf(quitables));
        Catalogo catalogo = Catalogo.leer(c.getConfigurationSection("catalogo"));
        List<String> problemas = new java.util.ArrayList<>(catalogo.avisos());
        problemas.addAll(ejecutor.validar(catalogo));
        problemas.forEach(p -> getLogger().warning("Catálogo: " + p));
        streamers.configurar(c.getString("twitch.client-id", "").trim(),
                c.getString("twitch.url-id", "https://id.twitch.tv/oauth2"),
                c.getString("twitch.url-api", "https://api.twitch.tv/helix"),
                c.getString("twitch.url-eventsub", "wss://eventsub.wss.twitch.tv/ws"),
                catalogo, ritmo, new Avisos(c.getConfigurationSection("avisos")),
                Set.copyOf(c.getStringList("mundos-excluidos")));
        getLogger().info("Catálogo: " + catalogo.todas().size() + " entradas"
                + (problemas.isEmpty() ? ", sin problemas." : ", " + problemas.size() + " problema(s) (ver arriba)."));
    }

    @EventHandler
    public void alEntrar(PlayerJoinEvent e) {
        streamers.alEntrar(e.getPlayer());
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ejecutor.terminarNoche(e.getPlayer());
    }
}
