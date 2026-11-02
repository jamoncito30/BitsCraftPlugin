package dev.jamon.bitscraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Device Code Grant, tokens y EventSub contra un Twitch de mentira en 127.0.0.1. */
class TwitchTest {

    TwitchFalso twitch;
    HttpClient http;
    ClienteTwitch cliente;
    ScheduledExecutorService hilos;

    @BeforeEach
    void antes() throws Exception {
        twitch = new TwitchFalso();
        http = HttpClient.newHttpClient();
        cliente = new ClienteTwitch(http, "cliente", twitch.urlId(), twitch.urlApi(), () -> 1_000_000L);
        hilos = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void despues() throws Exception {
        hilos.shutdownNow();
        twitch.close();
    }

    @Test
    void vinculacionCompleta() throws Exception {
        CompletableFuture<ClienteTwitch.Codigo> codigo = new CompletableFuture<>();
        CompletableFuture<Token> listo = new CompletableFuture<>();
        new Vinculacion(cliente, hilos, System::currentTimeMillis, new Vinculacion.Salida() {
            @Override
            public void codigo(ClienteTwitch.Codigo c) {
                codigo.complete(c);
            }

            @Override
            public void listo(Token token) {
                listo.complete(token);
            }

            @Override
            public void fallo(String motivo) {
                listo.completeExceptionally(new AssertionError(motivo));
            }
        }).iniciar();
        ClienteTwitch.Codigo c = codigo.get(5, TimeUnit.SECONDS);
        assertEquals("ABCDEFGH", c.userCode());
        assertFalse(c.toString().contains("DEV-SECRETO"), "el device code no se muestra");
        Token t = listo.get(10, TimeUnit.SECONDS);
        assertEquals("AT1", t.acceso());
        assertEquals("RT1", t.renovacion());
        assertEquals("42", t.usuarioId());
        assertEquals("loniux", t.login());
        assertEquals(1_000_000L + 14_000_000L, t.expira());
        assertEquals(2, twitch.canjes.get(), "una vez pendiente, la segunda aceptado");
        assertFalse(t.toString().contains("AT1"));
        assertFalse(t.toString().contains("RT1"));
    }

    @Test
    void renovarCambiaAmbosTokensYUnRefreshMaloPideRevincular() throws Exception {
        Token t = new Token("AT1", "RT1", 0, "42", "loniux", false);
        Token nuevo = cliente.renovar(t);
        assertEquals("AT2", nuevo.acceso());
        assertEquals("RT2", nuevo.renovacion());
        assertEquals("42", nuevo.usuarioId());
        assertEquals(1_000_000L + 14_000_000L, nuevo.expira());
        assertThrows(ClienteTwitch.TokenInvalido.class, () -> cliente.renovar(nuevo));
        assertNotNull(cliente.validar("AT2"));
        assertNull(cliente.validar("otro"), "401 = el token ya no sirve");
    }

    @Test
    void canjeLentoYFallo() throws Exception {
        twitch.pendientesAntesDeAceptar = 100;
        ClienteTwitch.Codigo bueno = cliente.pedirCodigo();
        assertEquals(ClienteTwitch.Estado.PENDIENTE, cliente.canjear(bueno).estado());
        ClienteTwitch.Codigo malo = new ClienteTwitch.Codigo("otro", "X", "u", 1, 60);
        ClienteTwitch.Canje r = cliente.canjear(malo);
        assertEquals(ClienteTwitch.Estado.FALLO, r.estado());
        assertTrue(r.detalle().contains("invalid device code"), r.detalle());
    }

    @Test
    void eventSubSuscribeRecibeDescartaRepetidosYSigueElReconnect() throws Exception {
        BlockingQueue<Evento> eventos = new LinkedBlockingQueue<>();
        List<String> infos = new CopyOnWriteArrayList<>();
        AtomicReference<String> acceso = new AtomicReference<>("AT_VIEJO");
        AtomicInteger renovaciones = new AtomicInteger();
        ConexionEventSub c = new ConexionEventSub(http, cliente, twitch.urlWs(), "42",
                new ConexionEventSub.Credencial() {
                    @Override
                    public String acceso() {
                        return acceso.get();
                    }

                    @Override
                    public boolean renovar() {
                        renovaciones.incrementAndGet();
                        acceso.set("AT1");
                        return true;
                    }
                },
                new ConexionEventSub.Receptor() {
                    @Override
                    public void evento(Evento ev) {
                        eventos.add(ev);
                    }

                    @Override
                    public void info(String mensaje) {
                        infos.add(mensaje);
                    }

                    @Override
                    public void revocado() {
                        infos.add("REVOCADO");
                    }
                }, new Repetidos(100), hilos, System::currentTimeMillis);
        c.iniciar();
        TwitchFalso.Conexion uno = twitch.conexiones.poll(5, TimeUnit.SECONDS);
        assertNotNull(uno);
        uno.enviar(bienvenida("S1"));
        esperar(() -> twitch.suscripciones.size() == 7);
        assertTrue(twitch.suscripciones.stream().allMatch(s -> s.contains("\"session_id\":\"S1\"")));
        assertTrue(twitch.suscripciones.stream().anyMatch(s -> s.contains("channel.follow") && s.contains("moderator_user_id")));
        assertEquals(1, renovaciones.get(), "un 401 renueva el token una vez y reintenta");
        esperar(() -> c.estado().contains("7/7"));

        uno.enviar(cheer("n1", 600));
        uno.enviar(cheer("n1", 600));
        Evento ev = eventos.poll(5, TimeUnit.SECONDS);
        assertEquals(600, ev.monto());
        assertEquals("42", ev.canalId());

        uno.enviar("""
                {"metadata":{"message_id":"r1","message_type":"session_reconnect","message_timestamp":"x"},
                 "payload":{"session":{"id":"S1","status":"reconnecting","reconnect_url":"%s?reconectar"}}}"""
                .formatted(twitch.urlWs()));
        TwitchFalso.Conexion dos = twitch.conexiones.poll(5, TimeUnit.SECONDS);
        assertNotNull(dos);
        assertTrue(dos.ruta.contains("reconectar"));
        dos.enviar(bienvenida("S2"));
        esperar(() -> uno.cerrada);
        dos.enviar(cheer("n2", 100));
        assertEquals(100, eventos.poll(5, TimeUnit.SECONDS).monto());
        assertNull(eventos.poll(300, TimeUnit.MILLISECONDS), "el n1 repetido no llegó dos veces");
        assertEquals(7, twitch.suscripciones.size(), "tras un session_reconnect no se vuelve a suscribir");

        dos.socket.close();
        TwitchFalso.Conexion tres = twitch.conexiones.poll(5, TimeUnit.SECONDS);
        assertNotNull(tres, "si se corta, vuelve a conectar solo");
        tres.enviar(bienvenida("S3"));
        esperar(() -> twitch.suscripciones.size() == 14);
        c.cerrar();
        assertTrue(infos.stream().noneMatch(i -> i.contains("AT1") || i.contains("AT_VIEJO")), infos.toString());
    }

    static String bienvenida(String sesion) {
        return """
                {"metadata":{"message_id":"w-%s","message_type":"session_welcome","message_timestamp":"x"},
                 "payload":{"session":{"id":"%s","status":"connected","keepalive_timeout_seconds":10,"reconnect_url":null}}}"""
                .formatted(sesion, sesion);
    }

    static String cheer(String id, int bits) {
        return """
                {"metadata":{"message_id":"%s","message_type":"notification","message_timestamp":"x",
                  "subscription_type":"channel.cheer","subscription_version":"1"},
                 "payload":{"subscription":{"type":"channel.cheer"},
                  "event":{"is_anonymous":false,"user_name":"Pepito","broadcaster_user_id":"42","bits":%d,"message":"hola"}}}"""
                .formatted(id, bits);
    }

    static void esperar(java.util.function.BooleanSupplier condicion) throws InterruptedException {
        long fin = System.currentTimeMillis() + 10_000;
        while (!condicion.getAsBoolean()) {
            if (System.currentTimeMillis() > fin) {
                throw new AssertionError("no se cumplió a tiempo");
            }
            Thread.sleep(50);
        }
    }
}
