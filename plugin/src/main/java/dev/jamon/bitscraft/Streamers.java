package dev.jamon.bitscraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * El centro del plugin: qué jugador tiene vinculado qué canal, la conexión de EventSub de cada uno,
 * la renovación de tokens y la fila de eventos que se va ejecutando en el juego.
 * Los vínculos se tocan desde varios hilos; las filas, solo desde el hilo principal.
 */
final class Streamers {

    /** El canal vinculado a un jugador. */
    static final class Vinculo {
        final UUID jugador;
        volatile Token token;
        ConexionEventSub conexion;
        volatile boolean revincular;
        volatile long validado;

        Vinculo(UUID jugador, Token token) {
            this.jugador = jugador;
            this.token = token;
        }
    }

    private final JavaPlugin plugin;
    private final Logger log;
    private final LongSupplier reloj;
    private final ScheduledExecutorService hilos;
    private final HttpClient http;
    private final AlmacenTokens almacen;
    private final GuardadoFilas guardado;
    private final Marcas marcas;
    private final Ejecutor ejecutor;
    private final Repetidos repetidos = new Repetidos(2000);

    private volatile ClienteTwitch cliente;
    private volatile String urlEventSub;
    private Catalogo catalogo;
    private Ritmo ritmo;
    private Avisos avisos;
    private Set<String> mundosExcluidos;

    private final Map<UUID, Vinculo> vinculos = new ConcurrentHashMap<>();
    private final Map<String, UUID> porCanal = new ConcurrentHashMap<>();
    private final Map<UUID, Vinculacion> vinculaciones = new ConcurrentHashMap<>();
    private final Map<UUID, Fila> filas = new HashMap<>();
    private int vueltas;

    Streamers(JavaPlugin plugin, LongSupplier reloj, ScheduledExecutorService hilos, HttpClient http,
              AlmacenTokens almacen, GuardadoFilas guardado, Marcas marcas, Ejecutor ejecutor) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.reloj = reloj;
        this.hilos = hilos;
        this.http = http;
        this.almacen = almacen;
        this.guardado = guardado;
        this.marcas = marcas;
        this.ejecutor = ejecutor;
    }

    // ---- configuración ----

    /** Aplica la config. Si cambió el client id o las URLs de Twitch, reconecta a todos. */
    void configurar(String clientId, String urlId, String urlApi, String urlEventSub, Catalogo catalogo,
                    Ritmo ritmo, Avisos avisos, Set<String> mundosExcluidos) {
        this.catalogo = catalogo;
        this.ritmo = ritmo;
        this.avisos = avisos;
        this.mundosExcluidos = mundosExcluidos;
        ClienteTwitch viejo = cliente;
        boolean cambio = viejo == null || !viejo.clientId().equals(clientId) || !urlEventSub.equals(this.urlEventSub);
        this.cliente = new ClienteTwitch(http, clientId, urlId, urlApi, reloj);
        this.urlEventSub = urlEventSub;
        if (cambio && viejo != null) {
            vinculos.values().forEach(v -> hilos.execute(() -> validarYConectar(v)));
        }
    }

    boolean urlsLocales() {
        try {
            String host = URI.create(urlEventSub).getHost();
            return host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Carga los vínculos y filas guardados y conecta a cada streamer. */
    void iniciar() {
        List<String> malos = new ArrayList<>();
        almacen.todos(malos).forEach((jugador, token) -> {
            Vinculo v = new Vinculo(jugador, token);
            vinculos.put(jugador, v);
            porCanal.put(token.usuarioId(), jugador);
            hilos.execute(() -> validarYConectar(v));
        });
        filas.putAll(guardado.leerTodas(ritmo.colaMaxima(), malos));
        if (!malos.isEmpty()) {
            log.warning("No se pudieron leer: " + String.join(", ", malos));
        }
        log.info(vinculos.size() + " canal(es) vinculado(s), " + filas.values().stream().mapToInt(Fila::tamano).sum()
                + " evento(s) en fila.");
        hilos.scheduleAtFixedRate(this::mantenimiento, 5, 5, TimeUnit.MINUTES);
    }

    void apagar() {
        vinculaciones.values().forEach(Vinculacion::cancelar);
        vinculos.values().forEach(v -> {
            synchronized (v) {
                if (v.conexion != null) {
                    v.conexion.cerrar();
                }
            }
        });
        guardarFilas(true);
    }

    // ---- conexión y tokens (hilos del plugin) ----

    private void validarYConectar(Vinculo v) {
        if (v.revincular) {
            return;
        }
        if (!v.token.mock()) {
            try {
                ClienteTwitch.Validacion val = cliente.validar(v.token.acceso());
                if (val == null && !renovar(v)) {
                    return;
                }
                v.validado = reloj.getAsLong();
            } catch (IOException e) {
                log.warning("No se pudo validar el token de " + v.token.login() + " (" + e.getMessage()
                        + "); se intenta conectar igual.");
            }
        }
        conectar(v);
    }

    private void conectar(Vinculo v) {
        synchronized (v) {
            if (v.conexion != null) {
                v.conexion.cerrar();
            }
            v.conexion = new ConexionEventSub(http, cliente, urlEventSub, v.token.usuarioId(),
                    new ConexionEventSub.Credencial() {
                        @Override
                        public String acceso() {
                            return v.token.acceso();
                        }

                        @Override
                        public boolean renovar() {
                            return Streamers.this.renovar(v);
                        }
                    },
                    new ConexionEventSub.Receptor() {
                        @Override
                        public void evento(Evento ev) {
                            Bukkit.getScheduler().runTask(plugin, () -> recibirDeTwitch(ev));
                        }

                        @Override
                        public void info(String mensaje) {
                            log.info("[" + v.token.login() + "] " + mensaje);
                        }

                        @Override
                        public void revocado() {
                            revincular(v, "Twitch canceló el permiso de la app");
                        }
                    }, repetidos, hilos, reloj);
            v.conexion.iniciar();
        }
    }

    /** Renueva el token. false si no se pudo (y, si el refresh token ya no sirve, pide volver a vincular). */
    private boolean renovar(Vinculo v) {
        synchronized (v) {
            if (v.token.mock() || v.revincular) {
                return false;
            }
            try {
                v.token = cliente.renovar(v.token);
                almacen.guardar(v.jugador, v.token);
                return true;
            } catch (ClienteTwitch.TokenInvalido e) {
                revincular(v, e.getMessage());
            } catch (IOException e) {
                log.warning("No se pudo renovar el token de " + v.token.login() + ": " + e.getMessage());
            }
            return false;
        }
    }

    private void revincular(Vinculo v, String motivo) {
        v.revincular = true;
        synchronized (v) {
            if (v.conexion != null) {
                v.conexion.cerrar();
            }
        }
        log.warning("El canal " + v.token.login() + " tiene que volver a vincularse: " + motivo);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(v.jugador);
            if (p != null) {
                avisarRevincular(p);
            }
        });
    }

    private void avisarRevincular(Player p) {
        p.sendMessage(Component.text("Tu canal de Twitch se desconectó del servidor. Escribe ", NamedTextColor.RED)
                .append(Component.text("/twitch vincular", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/twitch vincular")))
                .append(Component.text(" para volver a conectarlo.", NamedTextColor.RED)));
    }

    /** Cada 5 minutos: renueva los tokens que vencen pronto y valida cada hora (lo pide Twitch). */
    private void mantenimiento() {
        long ahora = reloj.getAsLong();
        for (Vinculo v : vinculos.values()) {
            if (v.token.mock() || v.revincular) {
                continue;
            }
            try {
                if (v.token.expira() - ahora < 20 * 60_000L) {
                    renovar(v);
                } else if (ahora - v.validado > 3_600_000L) {
                    if (cliente.validar(v.token.acceso()) == null) {
                        renovar(v);
                    }
                    v.validado = ahora;
                }
            } catch (IOException e) {
                log.fine("Validación pendiente para " + v.token.login() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Error en el mantenimiento de " + v.token.login(), e);
            }
        }
    }

    // ---- vincular (lo pide el jugador) ----

    void vincular(Player p) {
        if (cliente.clientId().isBlank()) {
            p.sendMessage(Component.text("Falta el client-id de Twitch en la config del plugin. Avísale al admin.", NamedTextColor.RED));
            return;
        }
        UUID id = p.getUniqueId();
        Vinculacion previa = vinculaciones.remove(id);
        if (previa != null) {
            previa.cancelar();
        }
        p.sendMessage(Component.text("Pidiendo un código a Twitch...", NamedTextColor.GRAY));
        Vinculacion nueva = new Vinculacion(cliente, hilos, reloj, new Vinculacion.Salida() {
            @Override
            public void codigo(ClienteTwitch.Codigo c) {
                Bukkit.getScheduler().runTask(plugin, () -> mostrarCodigo(id, c));
            }

            @Override
            public void listo(Token token) {
                vinculaciones.remove(id);
                Bukkit.getScheduler().runTask(plugin, () -> vinculado(id, token));
            }

            @Override
            public void fallo(String motivo) {
                vinculaciones.remove(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player q = Bukkit.getPlayer(id);
                    if (q != null) {
                        q.sendMessage(Component.text("No se pudo vincular: " + motivo + ". Prueba de nuevo con /twitch vincular.", NamedTextColor.RED));
                    }
                });
            }
        });
        vinculaciones.put(id, nueva);
        nueva.iniciar();
    }

    private void mostrarCodigo(UUID id, ClienteTwitch.Codigo c) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            return;
        }
        p.sendMessage(Component.text("» ", NamedTextColor.DARK_PURPLE)
                .append(Component.text("Haz clic aquí para conectar tu canal de Twitch", NamedTextColor.LIGHT_PURPLE)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(c.urlVerificacion()))));
        p.sendMessage(Component.text("  Inicia sesión con tu cuenta de Twitch y acepta. Si te pide un código: ", NamedTextColor.GRAY)
                .append(Component.text(c.userCode(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD)));
        p.sendMessage(Component.text("  El código vence en " + Math.max(1, c.expiraSeg() / 60) + " minutos.", NamedTextColor.DARK_GRAY));
    }

    private void vinculado(UUID id, Token token) {
        UUID otro = porCanal.get(token.usuarioId());
        Player p = Bukkit.getPlayer(id);
        if (otro != null && !otro.equals(id)) {
            hilos.execute(() -> {
                try {
                    cliente.revocar(token.acceso());
                } catch (IOException ignorado) {
                    // Si no se pudo revocar, vence solo.
                }
            });
            if (p != null) {
                p.sendMessage(Component.text("El canal " + token.login() + " ya está vinculado a " + nombre(otro)
                        + ". Que esa persona use /twitch desvincular primero.", NamedTextColor.RED));
            }
            return;
        }
        Vinculo anterior = vinculos.get(id);
        if (anterior != null) {
            cerrar(anterior);
            porCanal.remove(anterior.token.usuarioId());
        }
        Vinculo v = new Vinculo(id, token);
        v.validado = reloj.getAsLong();
        vinculos.put(id, v);
        porCanal.put(token.usuarioId(), id);
        hilos.execute(() -> {
            try {
                almacen.guardar(id, token);
            } catch (IOException e) {
                log.warning("No se pudo guardar el token de " + token.login() + ": " + e.getMessage()
                        + " (al reiniciar habrá que volver a vincular)");
            }
            conectar(v);
        });
        log.info(nombre(id) + " vinculó el canal " + token.login());
        if (p != null) {
            p.sendMessage(Component.text("¡Listo! Tu canal ", NamedTextColor.GREEN)
                    .append(Component.text(token.login(), NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(" quedó conectado. Lo que pase en tu canal te va a pasar a ti.", NamedTextColor.GREEN)));
        }
    }

    /** Vínculo falso para probar con la Twitch CLI. Solo si las URLs apuntan a esta máquina. */
    boolean mock(UUID id, String usuarioId, String login) {
        if (!urlsLocales()) {
            return false;
        }
        Vinculo anterior = vinculos.remove(id);
        if (anterior != null) {
            cerrar(anterior);
            porCanal.remove(anterior.token.usuarioId());
        }
        Vinculo v = new Vinculo(id, new Token("mock", "", Long.MAX_VALUE, usuarioId, login, true));
        vinculos.put(id, v);
        porCanal.put(usuarioId, id);
        hilos.execute(() -> conectar(v));
        return true;
    }

    void desvincular(UUID id) {
        Vinculacion pendiente = vinculaciones.remove(id);
        if (pendiente != null) {
            pendiente.cancelar();
        }
        Vinculo v = vinculos.remove(id);
        if (v == null) {
            return;
        }
        porCanal.remove(v.token.usuarioId());
        cerrar(v);
        hilos.execute(() -> {
            try {
                if (!v.token.mock()) {
                    cliente.revocar(v.token.acceso());
                }
            } catch (IOException e) {
                log.info("No se pudo revocar el token de " + v.token.login() + " (vence solo): " + e.getMessage());
            }
            try {
                almacen.borrar(id);
            } catch (IOException e) {
                log.warning("No se pudo borrar el token de " + v.token.login() + ": " + e.getMessage());
            }
        });
        log.info(nombre(id) + " desvinculó el canal " + v.token.login());
    }

    private static void cerrar(Vinculo v) {
        synchronized (v) {
            if (v.conexion != null) {
                v.conexion.cerrar();
                v.conexion = null;
            }
        }
    }

    Vinculo vinculo(UUID id) {
        return vinculos.get(id);
    }

    // ---- eventos y fila (hilo principal) ----

    private void recibirDeTwitch(Evento ev) {
        UUID j = porCanal.get(ev.canalId());
        if (j != null) {
            recibir(j, ev);
        }
    }

    /** Un evento para el streamer {@code j}: se pone en su fila, o solo se avisa si no hace nada. */
    void recibir(UUID j, Evento ev) {
        Catalogo.Entrada e = catalogo.elegir(ev);
        Fila f = fila(j);
        if (ev.tipo() == Evento.Tipo.FOLLOW && e != null && !f.tomarFollow(reloj.getAsLong(), catalogo.enfriamientoFollowMs())) {
            e = null;
        }
        if (e == null) {
            avisos.soloChat(nombre(j), ev);
            return;
        }
        Evento fuera = f.agregar(ev, ritmo.colaMaxima());
        if (fuera != null) {
            log.warning("La fila de " + nombre(j) + " pasó de " + ritmo.colaMaxima() + "; se descartó un evento de "
                    + fuera.tipo().clave + " de " + fuera.usuario());
        }
    }

    Fila fila(UUID j) {
        return filas.computeIfAbsent(j, k -> new Fila());
    }

    /** Cada medio segundo: saca lo que toca de cada fila, muestra el aviso y ejecuta tras el retardo. */
    void tick() {
        long ahora = reloj.getAsLong();
        vueltas++;
        for (Map.Entry<UUID, Fila> entrada : filas.entrySet()) {
            UUID j = entrada.getKey();
            Fila f = entrada.getValue();
            Player p = Bukkit.getPlayer(j);
            if (p == null || f.tamano() == 0 || mundosExcluidos.contains(p.getWorld().getName())) {
                continue;
            }
            for (Evento viejo : f.quitarViejos(ahora, ritmo.colaEdadMaximaMs())) {
                log.info("Se descartó un evento de " + viejo.tipo().clave + " de " + viejo.usuario()
                        + " para " + p.getName() + ": llevaba demasiado en la fila.");
            }
            Evento ev = f.siguiente(ahora, ritmo, x -> Ejecutor.tiposConTope(catalogo.elegir(x)));
            if (ev == null) {
                continue;
            }
            Catalogo.Entrada e = catalogo.elegir(ev);
            if (e == null) {
                continue;
            }
            String canal = canal(j);
            String nombreEntrada = Placeholders.aplicar(e.nombre(), Placeholders.de(p.getName(), ev, canal));
            avisos.mostrar(p, ev, nombreEntrada, ritmo.retardoAvisoMs());
            Bukkit.getScheduler().runTaskLater(plugin, () -> ejecutarAhora(j, f, ev), Math.max(0, ritmo.retardoAvisoMs() / 50));
        }
        if (vueltas % 4 == 0) {
            marcas.limpiar(ahora);
        }
        if (vueltas % 10 == 0) {
            guardarFilas(false);
        }
    }

    private void ejecutarAhora(UUID j, Fila f, Evento ev) {
        Player p = Bukkit.getPlayer(j);
        Catalogo.Entrada e = catalogo.elegir(ev);
        if (e == null) {
            return;
        }
        if (p == null) {
            f.devolver(ev);
            return;
        }
        Ejecutor.Resultado r = ejecutor.ejecutar(p, e, ev, canal(j));
        if (r.mobsSaltados > 0) {
            p.sendMessage(Component.text(r.mobsSaltados + " mob(s) no aparecieron: ya tienes " + ritmo.mobsVivos()
                    + " mobs de donaciones encima.", NamedTextColor.GRAY));
        }
        for (String problema : r.problemas) {
            log.warning("Catálogo (" + e.nombre() + "): " + problema);
        }
    }

    private void guardarFilas(boolean todas) {
        for (Map.Entry<UUID, Fila> e : filas.entrySet()) {
            if (e.getValue().tomarCambio() || todas) {
                try {
                    guardado.guardar(e.getKey(), e.getValue());
                } catch (IOException ex) {
                    log.warning("No se pudo guardar la fila de " + nombre(e.getKey()) + ": " + ex.getMessage());
                }
            }
        }
    }

    void alEntrar(Player p) {
        Fila f = filas.get(p.getUniqueId());
        if (f != null && f.tamano() > 0) {
            f.gracia(reloj.getAsLong() + ritmo.graciaMs());
            p.sendMessage(Component.text("Tienes " + f.tamano() + " evento(s) de Twitch esperando"
                    + (f.enPausa() ? " (estás en pausa: /twitch reanudar)." : "; empiezan en " + ritmo.graciaMs() / 1000 + " s."),
                    NamedTextColor.LIGHT_PURPLE));
        }
        Vinculo v = vinculos.get(p.getUniqueId());
        if (v != null && v.revincular) {
            avisarRevincular(p);
        }
    }

    void pausar(UUID j, boolean pausa) {
        Fila f = fila(j);
        f.pausar(pausa);
        if (!pausa) {
            f.gracia(reloj.getAsLong() + ritmo.graciaMs());
        }
        guardarFilas(false);
    }

    String canal(UUID j) {
        Vinculo v = vinculos.get(j);
        return v == null ? "" : v.token.login();
    }

    static String nombre(UUID j) {
        Player p = Bukkit.getPlayer(j);
        if (p != null) {
            return p.getName();
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(j);
        return o.getName() == null ? j.toString() : o.getName();
    }

    Catalogo catalogo() {
        return catalogo;
    }

    Ritmo ritmo() {
        return ritmo;
    }

    /** Estado de un streamer, para /twitch estado. */
    List<String> estado(UUID j) {
        List<String> l = new ArrayList<>();
        Vinculo v = vinculos.get(j);
        if (v == null) {
            l.add("Canal: sin vincular (usa /twitch vincular)");
        } else {
            l.add("Canal: " + v.token.login() + (v.token.mock() ? " (prueba)" : ""));
            ConexionEventSub c = v.conexion;
            l.add("Conexión: " + (v.revincular ? "hay que volver a vincular" : c == null ? "iniciando" : c.estado()));
        }
        Fila f = filas.get(j);
        l.add("Fila: " + (f == null ? 0 : f.tamano()) + " evento(s)" + (f != null && f.enPausa() ? ", EN PAUSA" : ""));
        return l;
    }

    /** Jugadores con un canal vinculado. */
    Set<UUID> vinculados() {
        return Set.copyOf(vinculos.keySet());
    }

    /** Nombres de los streamers vinculados (para autocompletar). */
    List<String> nombresVinculados() {
        return vinculos.keySet().stream().map(Streamers::nombre).toList();
    }
}
