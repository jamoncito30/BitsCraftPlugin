package dev.jamon.bitscraft;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TikTok LIVE sin la librería: catálogo, likes, enfriamientos, la conexión con un conector falso y el aislamiento. */
class TikTokTest {

    // ---- catálogo ----

    static Evento regalo(String nombre, int monedasCadaUno, int cantidad) {
        return new Evento("x", Evento.Tipo.TIKTOK_REGALO, "loniux", "fan", monedasCadaUno * cantidad, nombre, null, 0, cantidad);
    }

    static String nombre(Catalogo c, Evento e) {
        Catalogo.Entrada en = c.elegir(e);
        return en == null ? null : en.nombre();
    }

    @Test
    void regaloPorValorCaeEnLosEscalonesDeBits() {
        Catalogo c = CatalogoTest.catalogoReal(1);
        assertNull(nombre(c, regalo("Rose", 1, 1)), "una Rosa (1 moneda) no llega al primer escalón");
        assertEquals("5 lepismas", nombre(c, regalo("Rose", 1, 10)), "10 Rosas = 10 bits");
        assertEquals("Esqueletos", nombre(c, regalo("Perfume", 20, 5)), "5 Perfumes = 100 monedas");
        assertEquals("¡NUKE!", nombre(c, regalo("Lion", 29999, 1)));
        assertEquals(100, c.bitsDeRegalo(regalo("Perfume", 20, 5)));
    }

    @Test
    void bitsPorMonedaMultiplica() {
        Catalogo c = CatalogoTest.catalogoReal(2);
        assertEquals("5 lepismas", nombre(c, regalo("Rose", 1, 5)), "5 monedas × 2 = 10 bits");
        assertEquals(10, c.bitsDeRegalo(regalo("Rose", 1, 5)));
        assertEquals(1, CatalogoTest.catalogoReal(-3).bitsDeRegalo(regalo("Rose", 1, 1)), "un valor inválido usa 1");
    }

    @Test
    void regaloConNombrePropioVaAntesQueElValor() {
        YamlConfiguration y = CatalogoTest.copiaConfig();
        y.set("catalogo.tiktok-regalos.Rose.nombre", "Rosa");
        y.set("catalogo.tiktok-regalos.Rose.acciones", List.of(java.util.Map.of("tipo", "particulas", "particula", "heart", "cantidad", 8)));
        y.set("catalogo.tiktok-regalos.Finger Heart.como-bits", 100);
        Catalogo c = Catalogo.leer(y.getConfigurationSection("catalogo"), 1);
        assertEquals("Rosa", nombre(c, regalo("rose", 1, 1)), "por nombre, sin importar mayúsculas");
        assertEquals("Rosa", nombre(c, regalo("Rose", 1, 50)), "con nombre propio no cae en los bits aunque valga 50");
        assertEquals("Esqueletos", nombre(c, regalo("Finger Heart", 5, 1)), "como-bits 100");
        assertEquals("5 arañas de cueva", nombre(c, regalo("Finger Heart", 5, 2)), "como-bits × cantidad = 200");
        assertTrue(c.todas().containsKey("tiktok-regalos.rose"));
        assertTrue(c.avisos().isEmpty(), c.avisos().toString());
    }

    @Test
    void followSharesYLikesApagadosPorDefecto() {
        Catalogo c = CatalogoTest.catalogoReal(1);
        for (Evento.Tipo t : List.of(Evento.Tipo.TIKTOK_FOLLOW, Evento.Tipo.TIKTOK_SHARE, Evento.Tipo.TIKTOK_LIKES)) {
            assertNull(c.elegir(new Evento("x", t, "loniux", "fan", 1, null, null, 0)), t.clave);
        }
        YamlConfiguration y = CatalogoTest.copiaConfig();
        y.set("catalogo.tiktok-likes.activo", true);
        y.set("catalogo.tiktok-follow.activo", true);
        Catalogo c2 = Catalogo.leer(y.getConfigurationSection("catalogo"), 1);
        assertNotNull(c2.elegir(new Evento("x", Evento.Tipo.TIKTOK_LIKES, "loniux", "fan", 500, null, null, 0)));
        assertEquals(500, c2.likesCada());
        assertEquals(60_000, c2.enfriamientoMs(Evento.Tipo.TIKTOK_LIKES));
        assertEquals(30_000, c2.enfriamientoMs(Evento.Tipo.TIKTOK_FOLLOW));
        assertEquals(30_000, c2.enfriamientoMs(Evento.Tipo.FOLLOW), "el follow de Twitch sigue igual");
    }

    // ---- fila: likes y enfriamientos ----

    @Test
    void likesSeJuntanPorGrupos() {
        Fila f = new Fila();
        assertEquals(0, f.sumarLikes(120, 500));
        assertEquals(0, f.sumarLikes(300, 500));
        assertEquals(500, f.sumarLikes(100, 500), "120 + 300 + 100 = 520 → un grupo, quedan 20");
        assertEquals(1000, f.sumarLikes(1000, 500), "20 + 1000 = 1020 → dos grupos");
        assertEquals(0, f.sumarLikes(-5, 500));
    }

    @Test
    void enfriamientosPorTipoSonIndependientes() {
        Fila f = new Fila();
        assertTrue(f.tomarEnfriamiento(Evento.Tipo.TIKTOK_FOLLOW, 0, 30_000));
        assertTrue(f.tomarFollow(1000, 30_000), "el follow de Twitch no comparte enfriamiento con el de TikTok");
        assertFalse(f.tomarEnfriamiento(Evento.Tipo.TIKTOK_FOLLOW, 29_999, 30_000));
        assertTrue(f.tomarEnfriamiento(Evento.Tipo.TIKTOK_FOLLOW, 30_000, 30_000));
    }

    @Test
    void lasFilasGuardanLaCantidad(@TempDir Path dir) throws Exception {
        GuardadoFilas g = new GuardadoFilas(dir);
        Fila f = new Fila();
        f.agregar(regalo("Rose", 1, 7), 50);
        f.agregar(new Evento("t", Evento.Tipo.BITS, "42", "a", 100, null, null, 0), 50);
        java.util.UUID id = java.util.UUID.randomUUID();
        g.guardar(id, f);
        Fila leida = g.leerTodas(50, new ArrayList<>()).get(id);
        List<Evento> l = new ArrayList<>(leida.eventos());
        assertEquals(7, l.get(0).cantidad());
        assertEquals(Evento.Plataforma.TIKTOK, l.get(0).plataforma());
        assertEquals(1, l.get(1).cantidad());
        assertEquals(Evento.Plataforma.TWITCH, l.get(1).plataforma());
    }

    // ---- la conexión, con un conector y un reloj falsos ----

    /** Un ScheduledExecutorService a mano: guarda las tareas y las corre cuando el test dice. */
    static final class Agenda {
        final List<long[]> esperas = new ArrayList<>();
        final List<Runnable> tareas = new ArrayList<>();
        final ScheduledExecutorService servicio = (ScheduledExecutorService) Proxy.newProxyInstance(
                Agenda.class.getClassLoader(), new Class<?>[]{ScheduledExecutorService.class}, (proxy, m, args) -> {
                    if (m.getName().equals("schedule") && args[0] instanceof Runnable r) {
                        long ms = ((TimeUnit) args[2]).toMillis((Long) args[1]);
                        esperas.add(new long[]{ms});
                        tareas.add(r);
                        return (ScheduledFuture<?>) Proxy.newProxyInstance(Agenda.class.getClassLoader(),
                                new Class<?>[]{ScheduledFuture.class}, (p2, m2, a2) -> m2.getName().equals("cancel") ? Boolean.TRUE : null);
                    }
                    if (m.getName().equals("execute")) {
                        ((Runnable) args[0]).run();
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });

        long ultimaEspera() {
            return esperas.get(esperas.size() - 1)[0];
        }

        void correrUltima() {
            tareas.get(tareas.size() - 1).run();
        }
    }

    static final class Falso implements TikTok.Conector {
        final List<Object> respuestas = new ArrayList<>();
        TikTok.Receptor receptor;
        int intentos;
        int cerradas;

        @Override
        public TikTok.Sesion conectar(String usuario, TikTok.Receptor r) throws TikTok.FalloTikTok {
            intentos++;
            Object o = respuestas.isEmpty() ? "ok" : respuestas.remove(0);
            if (o instanceof TikTok.FalloTikTok f) {
                r.desconectado("Exception: " + f.getMessage()); // como hace la librería antes de lanzar
                throw f;
            }
            if (o instanceof Error e) {
                throw e;
            }
            receptor = r;
            return () -> cerradas++;
        }
    }

    static final class Salida implements ConexionTikTok.Salida {
        final List<Evento> eventos = new ArrayList<>();
        final List<String> info = new ArrayList<>();
        Throwable rota;

        @Override
        public void evento(Evento ev) {
            eventos.add(ev);
        }

        @Override
        public void info(String mensaje) {
            info.add(mensaje);
        }

        @Override
        public void libreriaRota(Throwable t) {
            rota = t;
        }
    }

    @Test
    void conectaYConvierteLosRegalos() {
        Agenda a = new Agenda();
        Falso f = new Falso();
        Salida s = new Salida();
        AtomicLong reloj = new AtomicLong(5000);
        ConexionTikTok c = new ConexionTikTok("loniux", f, a.servicio, reloj::get, 120_000, s);
        c.iniciar();
        assertEquals(0, a.ultimaEspera());
        a.correrUltima();
        assertTrue(c.conectada(), c.estado());
        assertTrue(c.estado().startsWith("conectado a @loniux"));

        f.receptor.regalo("77", "fan_1", "Rose", 1, 5);
        f.receptor.regalo("77", "fan_1", "Rose", 1, 5); // repetido: se descarta
        f.receptor.regalo("0", "fan_2", "Lion", 29999, 1); // sin id: no se puede comparar, pasa
        f.receptor.likes("78", "fan_3", 0); // cero likes: nada
        f.receptor.follow("79", "fan_4");
        assertEquals(3, s.eventos.size());
        Evento rosa = s.eventos.get(0);
        assertEquals(Evento.Tipo.TIKTOK_REGALO, rosa.tipo());
        assertEquals(Evento.Plataforma.TIKTOK, rosa.plataforma());
        assertEquals("loniux", rosa.canalId());
        assertEquals("fan_1", rosa.usuario());
        assertEquals(5, rosa.monto(), "monedas × cantidad");
        assertEquals(5, rosa.cantidad());
        assertEquals("Rose", rosa.recompensa());
        assertEquals(5000, rosa.llegada());
        assertEquals(29999, s.eventos.get(1).monto());
        assertEquals(Evento.Tipo.TIKTOK_FOLLOW, s.eventos.get(2).tipo());
    }

    @Test
    void siNoEstaEnVivoVuelveAMirarMasTarde() {
        Agenda a = new Agenda();
        Falso f = new Falso();
        f.respuestas.add(new TikTok.FalloTikTok(TikTok.Fallo.FUERA_DE_VIVO, "no está en vivo"));
        AtomicLong reloj = new AtomicLong();
        ConexionTikTok c = new ConexionTikTok("loniux", f, a.servicio, reloj::get, 120_000, new Salida());
        c.iniciar();
        a.correrUltima();
        assertEquals(120_000, a.ultimaEspera());
        assertTrue(c.estado().startsWith("esperando que empiece el directo de @loniux"), c.estado());
        reloj.set(40_000);
        assertTrue(c.estado().endsWith("(de nuevo en 80 s)"), c.estado());
        a.correrUltima();
        assertTrue(c.conectada());
    }

    @Test
    void reintentaConEsperaCrecienteHasta5Minutos() {
        Agenda a = new Agenda();
        Falso f = new Falso();
        for (int i = 0; i < 7; i++) {
            f.respuestas.add(new TikTok.FalloTikTok(i == 0 ? TikTok.Fallo.FIRMA : TikTok.Fallo.OTRO, "falló"));
        }
        Salida s = new Salida();
        ConexionTikTok c = new ConexionTikTok("loniux", f, a.servicio, () -> 0, 120_000, s);
        c.iniciar();
        List<Long> esperas = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            a.correrUltima();
            esperas.add(a.ultimaEspera());
            if (i == 0) {
                assertTrue(c.estado().startsWith("error de firma"), c.estado());
            }
        }
        assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), esperas);
        a.correrUltima();
        assertTrue(c.conectada());
        // Conectado: la espera vuelve a 10 s. Si se corta, reconecta.
        f.receptor.desconectado("se fue internet");
        assertFalse(c.conectada());
        assertEquals(10_000, a.ultimaEspera());
        assertEquals(1, f.cerradas);
        TikTok.Receptor viejo = f.receptor;
        a.correrUltima();
        assertTrue(c.conectada());
        viejo.regalo("1", "fan", "Rose", 1, 1);
        assertTrue(s.eventos.isEmpty(), "lo que llega por una conexión vieja se ignora");
    }

    @Test
    void cerrarParaTodo() {
        Agenda a = new Agenda();
        Falso f = new Falso();
        Salida s = new Salida();
        ConexionTikTok c = new ConexionTikTok("loniux", f, a.servicio, () -> 0, 120_000, s);
        c.iniciar();
        a.correrUltima();
        TikTok.Receptor r = f.receptor;
        c.cerrar();
        assertEquals(1, f.cerradas);
        assertEquals("apagado", c.estado());
        r.regalo("1", "fan", "Rose", 1, 1);
        r.desconectado("x");
        assertTrue(s.eventos.isEmpty());
        int tareas = a.tareas.size();
        a.correrUltima();
        assertEquals(1, f.intentos, "una tarea vieja no vuelve a conectar");
        assertEquals(tareas, a.tareas.size());
    }

    @Test
    void unaLibreriaRotaSoloAvisa() {
        Agenda a = new Agenda();
        Falso f = new Falso();
        f.respuestas.add(new NoClassDefFoundError("io/github/jwdeveloper/tiktok/Algo"));
        Salida s = new Salida();
        ConexionTikTok c = new ConexionTikTok("loniux", f, a.servicio, () -> 0, 120_000, s);
        c.iniciar();
        int antes = a.tareas.size();
        a.correrUltima();
        assertNotNull(s.rota);
        assertEquals(antes, a.tareas.size(), "no se reintenta");
        assertTrue(c.estado().contains("librería"), c.estado());
    }

    // ---- el administrador: clave, cuentas y aislamiento ----

    static final class Registro extends Handler {
        final List<String> lineas = new ArrayList<>();

        @Override
        public void publish(LogRecord r) {
            lineas.add(r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    static Logger logger(Registro r) {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        l.addHandler(r);
        return l;
    }

    static MemoryConfiguration config(boolean activo, boolean prueba) {
        MemoryConfiguration m = new MemoryConfiguration();
        m.set("tiktok.activo", activo);
        m.set("tiktok.prueba", prueba);
        m.set("streamers.LONIUX.tiktok", "@LoniuX");
        m.set("streamers.LionGGZ.tiktok", "no vale con espacios");
        return m;
    }

    @Test
    void sinClaveQuedaApagadoYCreaSecretosVacio(@TempDir Path dir) throws Exception {
        Registro reg = new Registro();
        TikTok t = new TikTok(logger(reg), () -> 0, dir, (j, ev) -> { });
        MemoryConfiguration m = config(true, false);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        assertTrue(Files.exists(dir.resolve("secretos.yml")));
        assertEquals("", t.leerClave());
        assertEquals("loniux", t.cuentas().get("loniux"), "el @ se normaliza");
        assertNull(t.cuentas().get("lionggz"), "un @ inválido se ignora");
        List<String> e = t.estado("LONIUX");
        assertTrue(e.get(0).contains("falta la clave"), e.toString());
        assertTrue(e.get(1).contains("falta"), e.toString());
        t.apagar();
    }

    @Test
    void laClaveNuncaSaleEnElEstadoNiEnLosLogs(@TempDir Path dir) throws Exception {
        String clave = "euler_SECRETA_1234567890";
        Files.writeString(dir.resolve("secretos.yml"), "euler-api-key: \"" + clave + "\"\n", StandardCharsets.UTF_8);
        Registro reg = new Registro();
        List<String> recibida = new ArrayList<>();
        // Nunca se carga la librería en las pruebas: un conector falso recibe la clave.
        TikTok t = new TikTok(logger(reg), () -> 0, dir, (j, ev) -> { }, k -> {
            recibida.add(k);
            return new Falso();
        });
        MemoryConfiguration m = config(true, false);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        assertEquals(clave, t.leerClave());
        assertEquals(List.of(clave), recibida, "la clave llega al conector");
        List<String> todo = new ArrayList<>(t.estado("LONIUX"));
        todo.addAll(reg.lineas);
        assertTrue(t.estado("LONIUX").contains("Clave de Euler Stream: configurada"), t.estado("LONIUX").toString());
        for (String linea : todo) {
            assertFalse(linea != null && linea.contains(clave), "la clave apareció en: " + linea);
        }
        t.apagar();
    }

    @Test
    void enModoDePruebaLosEventosLleganAlJugador(@TempDir Path dir) throws Exception {
        List<String> llegaron = new ArrayList<>();
        TikTok t = new TikTok(logger(new Registro()), () -> 0, dir, (j, ev) -> llegaron.add(j + ":" + ev.recompensa() + ":" + ev.monto()));
        MemoryConfiguration m = config(true, true);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        TikTok.Receptor r = esperarReceptor(t, "loniux");
        r.regalo("1", "fan", "Perfume", 20, 5);
        assertEquals(List.of("LONIUX:Perfume:100"), llegaron);
        assertTrue(t.estado("LONIUX").get(0).contains("conectado a @loniux"), t.estado("LONIUX").toString());

        // Cambiar la cuenta desde el comando se guarda y pisa a config.yml.
        t.cambiarCuenta("LionGGZ", "lion.ggz");
        assertEquals("lion.ggz", t.cuentas().get("lionggz"));
        assertTrue(Files.readString(dir.resolve("tiktok-cuentas.yml")).contains("lion.ggz"));
        t.cambiarCuenta("LONIUX", null);
        assertNull(t.cuentas().get("loniux"));
        TikTok t2 = new TikTok(logger(new Registro()), () -> 0, dir, (j, ev) -> { });
        t2.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        assertNull(t2.cuentas().get("loniux"), "lo quitado con el comando sigue quitado al reiniciar");
        assertEquals("lion.ggz", t2.cuentas().get("lionggz"));
        t.apagar();
        t2.apagar();
    }

    @Test
    void siLaLibreriaSeRompeSoloSeApagaTikTok(@TempDir Path dir) throws Exception {
        Registro reg = new Registro();
        TikTok t = new TikTok(logger(reg), () -> 0, dir, (j, ev) -> { });
        MemoryConfiguration m = config(true, true);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        Fila twitch = new Fila();
        twitch.agregar(new Evento("t1", Evento.Tipo.BITS, "42", "a", 600, null, null, 0), 50);
        t.simularLibreriaRota();
        long hasta = System.currentTimeMillis() + 3000;
        while (!t.estado("LONIUX").get(0).contains("librería") && System.currentTimeMillis() < hasta) {
            Thread.sleep(20);
        }
        assertTrue(t.estado("LONIUX").get(0).contains("la librería de TikTok falló"), t.estado("LONIUX").toString());
        assertTrue(t.estado("LONIUX").get(0).contains("Twitch sigue funcionando"));
        assertEquals(1, twitch.tamano(), "la fila de Twitch no se toca");
        t.apagar();
    }

    @Test
    void siLaLibreriaNoCargaSoloSeApagaTikTok(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("secretos.yml"), "euler-api-key: \"x\"\n", StandardCharsets.UTF_8);
        Registro reg = new Registro();
        TikTok t = new TikTok(logger(reg), () -> 0, dir, (j, ev) -> { }, k -> {
            throw new NoClassDefFoundError("io/github/jwdeveloper/tiktok/client/TikTokLive");
        });
        MemoryConfiguration m = config(true, false);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        assertTrue(t.estado("LONIUX").get(0).contains("NoClassDefFoundError"), t.estado("LONIUX").toString());
        assertTrue(reg.lineas.stream().anyMatch(l -> l.contains("Twitch sigue funcionando")));
        t.apagar();
    }

    @Test
    void apagadoNoConecta(@TempDir Path dir) {
        TikTok t = new TikTok(logger(new Registro()), () -> 0, dir, (j, ev) -> { });
        MemoryConfiguration m = config(false, true);
        t.configurar(m.getConfigurationSection("tiktok"), m.getConfigurationSection("streamers"));
        assertNull(t.falso());
        assertTrue(t.estado("LONIUX").get(0).contains("tiktok.activo: false"));
        t.apagar();
    }

    @Test
    void arrobas() {
        assertEquals("loniux", TikTok.arroba(" @LONIUX "));
        assertEquals("lion.ggz_2", TikTok.arroba("lion.ggz_2"));
        assertNull(TikTok.arroba("a"));
        assertNull(TikTok.arroba("con espacio"));
        assertNull(TikTok.arroba("x;rm -rf"));
        assertNull(TikTok.arroba(null));
    }

    @Test
    void tiposDeTikTokEnElComando() {
        assertEquals(Evento.Tipo.TIKTOK_REGALO, Evento.Tipo.desde("tiktok-regalo"));
        assertEquals(Evento.Tipo.TIKTOK_REGALO, Evento.Tipo.desde("tiktok"));
        assertEquals(Evento.Tipo.TIKTOK_LIKES, Evento.Tipo.desde("likes"));
        assertEquals(Evento.Tipo.SUB_REGALO, Evento.Tipo.desde("regalo"), "«regalo» sigue siendo la sub de regalo de Twitch");
    }

    /** El conector de prueba conecta en un hilo de TikTok: se espera un poco a que esté. */
    static TikTok.Receptor esperarReceptor(TikTok t, String arroba) throws InterruptedException {
        long hasta = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < hasta) {
            TikTok.ConectorFalso f = t.falso();
            TikTok.Receptor r = f == null ? null : f.receptor(arroba);
            if (r != null && t.estado("LONIUX").get(0).contains("conectado")) {
                return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no conectó: " + t.estado("LONIUX"));
    }
}
