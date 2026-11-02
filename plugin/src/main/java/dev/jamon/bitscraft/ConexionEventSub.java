package dev.jamon.bitscraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * La conexión de EventSub por WebSocket de un streamer: al recibir el welcome se suscribe a sus
 * eventos, sigue los session_reconnect de Twitch sin perder mensajes, descarta repetidos y, si se
 * corta o deja de recibir keepalives, se vuelve a conectar con espera creciente (hasta 60 s).
 */
final class ConexionEventSub {

    /** A quién se le entregan los eventos y los avisos. Se llama desde hilos del plugin. */
    interface Receptor {
        void evento(Evento ev);

        void info(String mensaje);

        /** Twitch revocó el permiso (el streamer desconectó la app): hay que volver a vincular. */
        void revocado();
    }

    /** El token actual del streamer y cómo renovarlo cuando Twitch responde 401. */
    interface Credencial {
        String acceso();

        boolean renovar();
    }

    private final HttpClient http;
    private final ClienteTwitch cliente;
    private final String urlInicial;
    private final String canalId;
    private final Credencial credencial;
    private final Receptor receptor;
    private final Repetidos repetidos;
    private final ScheduledExecutorService hilos;
    private final LongSupplier reloj;

    private WebSocket actual;
    private String sesion;
    private long ultimoMensaje;
    private int keepaliveSeg = 10;
    private int intentos;
    private boolean reconexionProgramada;
    private boolean cerrada;
    private ScheduledFuture<?> vigilante;
    private volatile String estado = "conectando";

    ConexionEventSub(HttpClient http, ClienteTwitch cliente, String urlInicial, String canalId, Credencial credencial,
                     Receptor receptor, Repetidos repetidos, ScheduledExecutorService hilos, LongSupplier reloj) {
        this.http = http;
        this.cliente = cliente;
        this.urlInicial = urlInicial;
        this.canalId = canalId;
        this.credencial = credencial;
        this.receptor = receptor;
        this.repetidos = repetidos;
        this.hilos = hilos;
        this.reloj = reloj;
    }

    synchronized void iniciar() {
        vigilante = hilos.scheduleAtFixedRate(this::vigilar, 5, 5, TimeUnit.SECONDS);
        hilos.execute(() -> abrir(urlInicial, false));
    }

    String estado() {
        return estado;
    }

    synchronized void cerrar() {
        cerrada = true;
        estado = "cerrada";
        if (vigilante != null) {
            vigilante.cancel(false);
        }
        if (actual != null) {
            actual.sendClose(WebSocket.NORMAL_CLOSURE, "adios");
            actual = null;
        }
    }

    private void abrir(String url, boolean reconexion) {
        synchronized (this) {
            if (cerrada) {
                return;
            }
        }
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Oyente(reconexion))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        synchronized (this) {
                            estado = "sin conexión (" + causa(error) + ")";
                            programarReconexion();
                        }
                    }
                });
    }

    private static String causa(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }

    private final class Oyente implements WebSocket.Listener {
        private final boolean reconexion;
        private StringBuilder buffer = new StringBuilder();

        Oyente(boolean reconexion) {
            this.reconexion = reconexion;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence datos, boolean ultimo) {
            buffer.append(datos);
            if (ultimo) {
                String m = buffer.toString();
                buffer = new StringBuilder();
                try {
                    procesar(ws, m, reconexion);
                } catch (RuntimeException e) {
                    receptor.info("Mensaje de Twitch que no se pudo leer (" + e.getClass().getSimpleName() + ")");
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int codigo, String razon) {
            cortada(ws, "Twitch cerró la conexión (" + codigo + ")");
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            cortada(ws, causa(error));
        }
    }

    private synchronized void procesar(WebSocket ws, String texto, boolean reconexion) {
        JsonObject m = JsonParser.parseString(texto).getAsJsonObject();
        JsonObject meta = m.getAsJsonObject("metadata");
        JsonObject carga = m.has("payload") && m.get("payload").isJsonObject() ? m.getAsJsonObject("payload") : new JsonObject();
        String tipo = MensajesEventSub.texto(meta, "message_type");
        if (ws == actual || reconexion) {
            ultimoMensaje = reloj.getAsLong();
        }
        switch (tipo) {
            case "session_welcome" -> {
                JsonObject s = carga.getAsJsonObject("session");
                sesion = MensajesEventSub.texto(s, "id");
                try {
                    keepaliveSeg = Math.max(10, s.get("keepalive_timeout_seconds").getAsInt());
                } catch (RuntimeException e) {
                    keepaliveSeg = 10;
                }
                WebSocket viejo = actual;
                actual = ws;
                intentos = 0;
                ultimoMensaje = reloj.getAsLong();
                if (reconexion && viejo != null && viejo != ws) {
                    // session_reconnect: las suscripciones se mantienen; se cierra la conexión vieja.
                    viejo.sendClose(WebSocket.NORMAL_CLOSURE, "reconectado");
                    estado = estado.replace("reconectando", "conectado");
                } else {
                    estado = "conectado, suscribiendo";
                    String s0 = sesion;
                    hilos.execute(() -> suscribirTodo(s0));
                }
            }
            case "notification" -> {
                String id = MensajesEventSub.texto(meta, "message_id");
                if (!repetidos.primeraVez(id)) {
                    return;
                }
                String sub = MensajesEventSub.texto(meta, "subscription_type");
                JsonObject ev = carga.has("event") && carga.get("event").isJsonObject() ? carga.getAsJsonObject("event") : null;
                if (ev != null) {
                    Evento e = MensajesEventSub.traducir(sub, ev, id, reloj.getAsLong());
                    if (e != null) {
                        receptor.evento(e);
                    }
                }
            }
            case "session_reconnect" -> {
                String url = MensajesEventSub.texto(carga.getAsJsonObject("session"), "reconnect_url");
                if (!url.isEmpty()) {
                    hilos.execute(() -> abrir(url, true));
                }
            }
            case "revocation" -> {
                JsonObject s = carga.has("subscription") ? carga.getAsJsonObject("subscription") : new JsonObject();
                String estadoSub = MensajesEventSub.texto(s, "status");
                receptor.info("Twitch canceló " + MensajesEventSub.texto(s, "type") + " (" + estadoSub + ")");
                if (estadoSub.equals("authorization_revoked") || estadoSub.equals("user_removed")) {
                    receptor.revocado();
                }
            }
            default -> {
                // session_keepalive y otros: basta con haber actualizado ultimoMensaje.
            }
        }
    }

    private void suscribirTodo(String sesionDeEsta) {
        int ok = 0;
        List<String> fallos = new ArrayList<>();
        List<MensajesEventSub.Suscripcion> subs = MensajesEventSub.suscripciones(canalId);
        for (MensajesEventSub.Suscripcion s : subs) {
            synchronized (this) {
                if (cerrada || !sesionDeEsta.equals(sesion)) {
                    return;
                }
            }
            try {
                ClienteTwitch.Respuesta r = cliente.suscribir(credencial.acceso(), s.tipo(), s.version(), s.condicion(), sesionDeEsta);
                if (r.codigo() == 401 && credencial.renovar()) {
                    r = cliente.suscribir(credencial.acceso(), s.tipo(), s.version(), s.condicion(), sesionDeEsta);
                }
                if (r.ok()) {
                    ok++;
                } else {
                    fallos.add(s.tipo() + " (HTTP " + r.codigo() + (r.mensaje().isEmpty() ? "" : ": " + r.mensaje()) + ")");
                }
            } catch (IOException e) {
                fallos.add(s.tipo() + " (" + e.getMessage() + ")");
            }
        }
        synchronized (this) {
            if (sesionDeEsta.equals(sesion) && !cerrada) {
                estado = "conectado (" + ok + "/" + subs.size() + " eventos)";
            }
        }
        if (!fallos.isEmpty()) {
            receptor.info("Suscrito a " + ok + " de " + subs.size() + " eventos. Fallaron: " + String.join(", ", fallos));
        }
    }

    private synchronized void cortada(WebSocket ws, String motivo) {
        if (cerrada || ws != actual) {
            return;
        }
        actual = null;
        estado = "desconectado (" + motivo + ")";
        programarReconexion();
    }

    private synchronized void vigilar() {
        if (cerrada || actual == null) {
            return;
        }
        if (reloj.getAsLong() - ultimoMensaje > (keepaliveSeg + 10) * 1000L) {
            WebSocket w = actual;
            actual = null;
            w.abort();
            estado = "sin keepalive de Twitch";
            programarReconexion();
        }
    }

    /** Llamar con el lock tomado. */
    private void programarReconexion() {
        if (cerrada || reconexionProgramada) {
            return;
        }
        reconexionProgramada = true;
        intentos++;
        long espera = Math.min(60, 1L << Math.min(6, intentos - 1));
        estado = estado + ", reconectando en " + espera + " s";
        hilos.schedule(() -> {
            synchronized (this) {
                reconexionProgramada = false;
            }
            abrir(urlInicial, false);
        }, espera, TimeUnit.SECONDS);
    }
}
