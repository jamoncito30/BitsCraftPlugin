package dev.jamon.bitscraft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Un Twitch de mentira en 127.0.0.1 para las pruebas: el servidor HTTP de id.twitch.tv y de la
 * Helix API (lo justo para el Device Code Grant, renovar, validar y suscribirse) y un servidor de
 * WebSocket mínimo para EventSub.
 */
final class TwitchFalso implements AutoCloseable {

    final HttpServer http;
    final AtomicInteger canjes = new AtomicInteger();
    final List<String> suscripciones = new CopyOnWriteArrayList<>();
    volatile int pendientesAntesDeAceptar = 1;

    final ServerSocket ws;
    final BlockingQueue<Conexion> conexiones = new LinkedBlockingQueue<>();

    TwitchFalso() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/oauth2/device", x -> responder(x, 200, """
                {"device_code":"DEV-SECRETO","user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH","interval":1,"expires_in":60}"""));
        http.createContext("/oauth2/token", this::token);
        http.createContext("/oauth2/validate", x -> {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if ("OAuth AT1".equals(auth) || "OAuth AT2".equals(auth)) {
                responder(x, 200, """
                        {"client_id":"cliente","login":"loniux","scopes":["bits:read"],"user_id":"42","expires_in":5000}""");
            } else {
                responder(x, 401, "{\"status\":401,\"message\":\"invalid access token\"}");
            }
        });
        http.createContext("/oauth2/revoke", x -> responder(x, 200, ""));
        http.createContext("/helix/eventsub/subscriptions", x -> {
            String cuerpo = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("Bearer AT_VIEJO".equals(x.getRequestHeaders().getFirst("Authorization"))) {
                responder(x, 401, "{\"status\":401,\"message\":\"Invalid OAuth token\"}");
                return;
            }
            suscripciones.add(cuerpo);
            responder(x, 202, "{\"data\":[{\"status\":\"enabled\"}]}");
        });
        http.start();

        ws = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!ws.isClosed()) {
                try {
                    Socket s = ws.accept();
                    conexiones.add(new Conexion(s));
                } catch (IOException e) {
                    return;
                }
            }
        }, "ws-falso");
        t.setDaemon(true);
        t.start();
    }

    String urlId() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/oauth2";
    }

    String urlApi() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/helix";
    }

    String urlWs() {
        return "ws://127.0.0.1:" + ws.getLocalPort() + "/ws";
    }

    private void token(HttpExchange x) throws IOException {
        Map<String, String> f = formulario(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (f.get("grant_type").endsWith("device_code")) {
            if (!"DEV-SECRETO".equals(f.get("device_code"))) {
                responder(x, 400, "{\"status\":400,\"message\":\"invalid device code\"}");
            } else if (canjes.incrementAndGet() <= pendientesAntesDeAceptar) {
                responder(x, 400, "{\"status\":400,\"message\":\"authorization_pending\"}");
            } else {
                responder(x, 200, """
                        {"access_token":"AT1","refresh_token":"RT1","expires_in":14000,"scope":["bits:read"],"token_type":"bearer"}""");
            }
        } else if ("RT1".equals(f.get("refresh_token"))) {
            responder(x, 200, """
                    {"access_token":"AT2","refresh_token":"RT2","expires_in":14000,"token_type":"bearer"}""");
        } else {
            responder(x, 400, "{\"status\":400,\"message\":\"Invalid refresh token\"}");
        }
    }

    private static Map<String, String> formulario(String s) {
        Map<String, String> m = new HashMap<>();
        for (String par : s.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void responder(HttpExchange x, int codigo, String cuerpo) throws IOException {
        byte[] b = cuerpo.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(codigo, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        }
        x.close();
    }

    @Override
    public void close() throws IOException {
        http.stop(0);
        ws.close();
    }

    /** Una conexión WebSocket aceptada. El servidor manda texto; del cliente solo se detecta el cierre. */
    static final class Conexion {
        final Socket socket;
        final String ruta;
        volatile boolean cerrada;
        private final OutputStream out;

        Conexion(Socket s) throws IOException {
            this.socket = s;
            InputStream in = s.getInputStream();
            ByteArrayOutputStream cab = new ByteArrayOutputStream();
            int prev3 = 0;
            while (true) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("cerrado en el handshake");
                }
                cab.write(b);
                prev3 = (prev3 << 8 | b) & 0xFFFFFFFF;
                if (prev3 == 0x0D0A0D0A) {
                    break;
                }
            }
            String pedido = cab.toString(StandardCharsets.ISO_8859_1);
            ruta = pedido.split(" ")[1];
            String clave = null;
            for (String l : pedido.split("\r\n")) {
                if (l.toLowerCase().startsWith("sec-websocket-key:")) {
                    clave = l.substring(18).trim();
                }
            }
            String acepta;
            try {
                acepta = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((clave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acepta + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread lector = new Thread(() -> leer(in), "ws-falso-lector");
            lector.setDaemon(true);
            lector.start();
        }

        private void leer(InputStream in) {
            try {
                while (true) {
                    int b0 = in.read();
                    int b1 = in.read();
                    if (b0 < 0 || b1 < 0) {
                        break;
                    }
                    long largo = b1 & 0x7F;
                    if (largo == 126) {
                        largo = (in.read() << 8) | in.read();
                    } else if (largo == 127) {
                        largo = 0;
                        for (int i = 0; i < 8; i++) {
                            largo = (largo << 8) | in.read();
                        }
                    }
                    in.readNBytes(4 + (int) largo);
                    if ((b0 & 0x0F) == 8) {
                        synchronized (this) {
                            out.write(new byte[]{(byte) 0x88, 0});
                            out.flush();
                        }
                        break;
                    }
                }
            } catch (IOException ignorado) {
                // El socket se cerró.
            }
            cerrada = true;
            try {
                socket.close();
            } catch (IOException ignorado) {
                // Nada que hacer.
            }
        }

        synchronized void enviar(String texto) throws IOException {
            byte[] d = texto.getBytes(StandardCharsets.UTF_8);
            out.write(0x81);
            if (d.length < 126) {
                out.write(d.length);
            } else {
                out.write(126);
                out.write(d.length >> 8);
                out.write(d.length & 0xFF);
            }
            out.write(d);
            out.flush();
        }
    }
}
