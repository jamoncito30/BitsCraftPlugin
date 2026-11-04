package dev.jamon.bitscraft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * TikTok LIVE: qué streamer tiene qué @, una {@link ConexionTikTok} por cada uno y la clave de Euler Stream.
 * Esta clase no toca la librería de TikTok: la carga por nombre ({@code ConectorTikTokLive}) y, si no carga
 * o se rompe, apaga solo TikTok. Twitch no se entera. La clave nunca va a la consola, a los logs ni al chat.
 */
final class TikTok {

    static final String CLASE_CONECTOR = "dev.jamon.bitscraft.ConectorTikTokLive";
    private static final Pattern ARROBA = Pattern.compile("[a-z0-9_.]{2,24}");

    enum Fallo { FUERA_DE_VIVO, NO_EXISTE, FIRMA, OTRO }

    /** Por qué no se pudo conectar. El mensaje ya viene sin la clave. */
    static final class FalloTikTok extends Exception {
        private static final long serialVersionUID = 1L;
        final Fallo tipo;

        FalloTikTok(Fallo tipo, String mensaje) {
            super(mensaje, null, false, false);
            this.tipo = tipo;
        }
    }

    /** Lo que sabe conectarse a un directo (la librería real o el falso de prueba). */
    interface Conector {
        /** Se conecta al directo de {@code usuario} (puede tardar). Lanza FalloTikTok si no se pudo. */
        Sesion conectar(String usuario, Receptor receptor) throws FalloTikTok;
    }

    interface Sesion {
        void cerrar();
    }

    /** Lo que llega del directo, ya en datos simples. */
    interface Receptor {
        void conectado();

        void regalo(String id, String fan, String regalo, int monedasCadaUno, int cantidad);

        void follow(String id, String fan);

        void share(String id, String fan);

        void likes(String id, String fan, int likes);

        void desconectado(String motivo);
    }

    private final Logger log;
    private final LongSupplier reloj;
    private final Path carpeta;
    private final BiConsumer<String, Evento> destino;
    private final ScheduledThreadPoolExecutor hilos;
    private final java.util.function.Function<String, Conector> real;

    private final Map<String, String> nombres = new ConcurrentHashMap<>();
    private final Map<String, String> cuentasConfig = new ConcurrentHashMap<>();
    private final Map<String, String> cuentasComando = new ConcurrentHashMap<>();
    private final Map<String, ConexionTikTok> conexiones = new ConcurrentHashMap<>();

    private boolean activo;
    private boolean prueba;
    private long revisarMs = 120_000;
    private volatile boolean claveConfigurada;
    private volatile String apagado = "todavía no arrancó";
    private volatile ConectorFalso falso;
    private boolean romperPrueba;

    /**
     * @param destino recibe (jugador de Minecraft, evento) desde hilos de TikTok; quien lo use lo pasa
     *                al hilo principal
     */
    TikTok(Logger log, LongSupplier reloj, Path carpetaDatos, BiConsumer<String, Evento> destino) {
        this(log, reloj, carpetaDatos, destino, TikTok::cargarLibreria);
    }

    /** @param real crea el conector de verdad a partir de la clave (en las pruebas, uno falso) */
    TikTok(Logger log, LongSupplier reloj, Path carpetaDatos, BiConsumer<String, Evento> destino,
           java.util.function.Function<String, Conector> real) {
        this.real = real;
        this.log = log;
        this.reloj = reloj;
        this.carpeta = carpetaDatos;
        this.destino = destino;
        AtomicInteger n = new AtomicInteger();
        this.hilos = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "BitsCraft-TikTok-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.hilos.setRemoveOnCancelPolicy(true);
    }

    // ---- configuración ----

    /** Lee tiktok.* y streamers.* de config.yml y (re)arranca las conexiones. */
    synchronized void configurar(ConfigurationSection tiktok, ConfigurationSection streamers) {
        activo = tiktok != null && tiktok.getBoolean("activo", false);
        prueba = tiktok != null && tiktok.getBoolean("prueba", false);
        revisarMs = Math.max(30, tiktok == null ? 120 : tiktok.getLong("revisar-cada-segundos", 120)) * 1000L;
        cuentasConfig.clear();
        if (streamers != null) {
            for (String jugador : streamers.getKeys(false)) {
                String a = arroba(streamers.getString(jugador + ".tiktok", ""));
                nombres.put(clave(jugador), jugador);
                if (a != null) {
                    cuentasConfig.put(clave(jugador), a);
                }
            }
        }
        leerCuentasComando();
        reiniciar();
    }

    /** Cierra todo y vuelve a conectar con la config actual (también lo usa /bits tiktok recargar). */
    synchronized void reiniciar() {
        cerrarTodas();
        falso = null;
        String clave = leerClave();
        claveConfigurada = !clave.isEmpty();
        if (!activo) {
            apagado = "apagado (tiktok.activo: false)";
            return;
        }
        Conector conector;
        if (prueba) {
            falso = new ConectorFalso();
            falso.romper = romperPrueba;
            romperPrueba = false;
            conector = falso;
        } else if (clave.isEmpty()) {
            apagado = "apagado: falta la clave de Euler Stream en secretos.yml";
            log.warning("TikTok: " + apagado + " (ver README).");
            return;
        } else {
            conector = cargarConector(clave);
            if (conector == null) {
                return;
            }
        }
        apagado = null;
        cuentas().forEach((jugador, arroba) -> conectar(jugador, arroba, conector));
        log.info("TikTok: " + conexiones.size() + " cuenta(s)" + (prueba ? " en modo de prueba (sin internet)" : ""));
    }

    private Conector cargarConector(String clave) {
        try {
            return real.apply(clave);
        } catch (LinkageError | RuntimeException e) {
            libreriaRota(e.getCause() instanceof LinkageError l ? l : e);
        }
        return null;
    }

    /** Carga ConectorTikTokLive (la librería) por nombre: si falta o cambió, falla aquí y no en el resto. */
    private static Conector cargarLibreria(String clave) {
        try {
            Class<?> k = Class.forName(CLASE_CONECTOR, true, TikTok.class.getClassLoader());
            return (Conector) k.getDeclaredConstructor(String.class).newInstance(clave);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof LinkageError l) {
                throw l;
            }
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void conectar(String jugador, String arroba, Conector conector) {
        ConexionTikTok c = new ConexionTikTok(arroba, conector, hilos, reloj, revisarMs, new ConexionTikTok.Salida() {
            @Override
            public void evento(Evento ev) {
                destino.accept(nombre(jugador), ev);
            }

            @Override
            public void info(String mensaje) {
                log.info(mensaje);
            }

            @Override
            public void libreriaRota(Throwable t) {
                TikTok.this.libreriaRota(t);
            }
        });
        conexiones.put(jugador, c);
        c.iniciar();
    }

    /** La librería no cargó o falló por dentro: se apaga TikTok entero; Twitch sigue igual. */
    void libreriaRota(Throwable t) {
        apagado = "apagado: la librería de TikTok falló (" + t.getClass().getSimpleName() + "). Twitch sigue funcionando.";
        log.warning("TikTok: " + apagado + " Puede que TikTok haya cambiado algo: ver README («Si TikTok deja de andar»).");
        hilos.execute(this::cerrarTodas);
    }

    private synchronized void cerrarTodas() {
        conexiones.values().forEach(ConexionTikTok::cerrar);
        conexiones.clear();
    }

    void apagar() {
        cerrarTodas();
        hilos.shutdownNow();
        try {
            hilos.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Cuentas de TikTok por jugador (clave en minúsculas): las del comando pisan a las de config.yml. */
    Map<String, String> cuentas() {
        Map<String, String> m = new LinkedHashMap<>(cuentasConfig);
        cuentasComando.forEach((j, a) -> {
            if (a.isEmpty()) {
                m.remove(j);
            } else {
                m.put(j, a);
            }
        });
        return m;
    }

    // ---- /bits tiktok ----

    /** Cambia (o quita, con {@code arroba} = null) el @ de TikTok de un jugador y lo guarda. */
    synchronized void cambiarCuenta(String jugador, String arroba) throws IOException {
        String j = clave(jugador);
        nombres.put(j, jugador);
        cuentasComando.put(j, arroba == null ? "" : arroba);
        guardarCuentasComando();
        ConexionTikTok vieja = conexiones.remove(j);
        if (vieja != null) {
            vieja.cerrar();
        }
        if (arroba != null && apagado == null) {
            reiniciar();
        }
    }

    synchronized void activar(boolean si) {
        activo = si;
        reiniciar();
    }

    boolean activo() {
        return activo;
    }

    boolean prueba() {
        return prueba;
    }

    /** Estado de TikTok para un jugador (para /bits estado). Nunca muestra la clave. */
    List<String> estado(String jugador) {
        List<String> l = new ArrayList<>();
        String a = cuentas().get(clave(jugador));
        String motivo = apagado;
        if (motivo != null) {
            l.add("TikTok: " + motivo + (a == null ? "" : " — cuenta @" + a));
        } else if (a == null) {
            l.add("TikTok: sin cuenta (usa /bits tiktok cuenta <@usuario>)");
        } else {
            ConexionTikTok c = conexiones.get(clave(jugador));
            l.add("TikTok: @" + a + " — " + (c == null ? "sin conexión" : c.estado()) + (prueba ? " [modo de prueba]" : ""));
        }
        if (!prueba) {
            l.add("Clave de Euler Stream: " + (claveConfigurada ? "configurada" : "falta (secretos.yml)"));
        }
        return l;
    }

    /** Modo de prueba: reconecta como si la librería no cargara (debe apagar TikTok y nada más). */
    synchronized void simularLibreriaRota() {
        romperPrueba = true;
        reiniciar();
    }

    /** El conector falso (solo en modo de prueba), para /bits tiktok simular. */
    ConectorFalso falso() {
        return falso;
    }

    // ---- archivos ----

    /** Normaliza un @ de TikTok; null si no sirve. */
    static String arroba(String texto) {
        if (texto == null) {
            return null;
        }
        String a = texto.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("@")) {
            a = a.substring(1);
        }
        return ARROBA.matcher(a).matches() ? a : null;
    }

    private static String clave(String jugador) {
        return jugador.toLowerCase(Locale.ROOT);
    }

    private String nombre(String clave) {
        return nombres.getOrDefault(clave, clave);
    }

    /**
     * La clave de Euler Stream, de secretos.yml (solo el dueño puede leerlo). Si el archivo no existe, se crea
     * vacío con permisos solo para el dueño. Nunca se registra en ningún lado.
     */
    String leerClave() {
        Path p = carpeta.resolve("secretos.yml");
        try {
            Files.createDirectories(carpeta);
            if (!Files.exists(p)) {
                Files.createFile(p);
                AlmacenTokens.restringir(p, false);
                Files.writeString(p, """
                        # Secretos de BitsCraftPlugin. NO compartas este archivo ni lo pegues en ningún chat.
                        # Clave de Euler Stream para conectarse a TikTok LIVE (https://www.eulerstream.com).
                        # Después de pegarla: /bits tiktok recargar
                        euler-api-key: ""
                        """, StandardCharsets.UTF_8);
                return "";
            }
            AlmacenTokens.restringir(p, false);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(Files.newBufferedReader(p, StandardCharsets.UTF_8));
            return y.getString("euler-api-key", "").trim();
        } catch (IOException | RuntimeException e) {
            log.warning("TikTok: no se pudo leer secretos.yml (" + e.getClass().getSimpleName() + ")");
            return "";
        }
    }

    private void leerCuentasComando() {
        cuentasComando.clear();
        Path p = carpeta.resolve("tiktok-cuentas.yml");
        if (!Files.exists(p)) {
            return;
        }
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(Files.newBufferedReader(p, StandardCharsets.UTF_8));
            for (String j : y.getKeys(false)) {
                String v = y.getString(j, "");
                String a = arroba(v);
                cuentasComando.put(clave(j), a == null ? "" : a);
                nombres.putIfAbsent(clave(j), j);
            }
        } catch (IOException | RuntimeException e) {
            log.warning("TikTok: no se pudo leer tiktok-cuentas.yml (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void guardarCuentasComando() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        cuentasComando.forEach((j, a) -> y.set(nombre(j), a));
        Files.createDirectories(carpeta);
        Files.writeString(carpeta.resolve("tiktok-cuentas.yml"),
                "# Cuentas de TikTok puestas con /bits tiktok cuenta (pisan a las de config.yml; \"\" = quitada).\n"
                        + y.saveToString(), StandardCharsets.UTF_8);
    }

    // ---- modo de prueba ----

    /**
     * Conector sin internet ni clave: «conecta» al instante y deja simular lo que mandaría TikTok con
     * /bits tiktok simular. Una cuenta que empieza con "offline" hace como si no estuviera en vivo.
     */
    static final class ConectorFalso implements Conector {
        private final Map<String, Receptor> conectados = new ConcurrentHashMap<>();
        volatile boolean romper;

        @Override
        public Sesion conectar(String usuario, Receptor receptor) throws FalloTikTok {
            if (romper) {
                throw new NoClassDefFoundError("prueba de librería rota");
            }
            if (usuario.startsWith("offline")) {
                throw new FalloTikTok(Fallo.FUERA_DE_VIVO, "no está en vivo");
            }
            conectados.put(usuario, receptor);
            return () -> conectados.remove(usuario, receptor);
        }

        /** El receptor de la conexión de prueba de ese @ (null si no está conectado). */
        Receptor receptor(String usuario) {
            return conectados.get(usuario);
        }
    }
}
