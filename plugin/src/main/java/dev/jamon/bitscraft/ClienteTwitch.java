package dev.jamon.bitscraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.LongSupplier;

/**
 * Lo que el plugin le pide a Twitch por HTTP: el Device Code Grant (cliente público, sin secret),
 * renovar, validar y revocar tokens, y crear suscripciones de EventSub. Todo es bloqueante: se
 * llama desde los hilos del plugin, nunca desde el hilo principal del servidor.
 * Los errores nunca incluyen tokens ni el cuerpo de lo que se mandó.
 */
final class ClienteTwitch {

    static final List<String> SCOPES = List.of(
            "bits:read", "channel:read:redemptions", "channel:read:subscriptions", "moderator:read:followers");

    /** Respuesta de /oauth2/device. {@link #toString()} oculta el device_code. */
    record Codigo(String deviceCode, String userCode, String urlVerificacion, int intervaloSeg, int expiraSeg) {
        @Override
        public String toString() {
            return "Codigo[" + userCode + ", " + urlVerificacion + "]";
        }
    }

    enum Estado { LISTO, PENDIENTE, LENTO, FALLO }

    /** Resultado de canjear el device code: el token cuando el streamer ya aceptó. */
    record Canje(Estado estado, Token token, String detalle) {
    }

    /** Lo que devuelve /oauth2/validate. */
    record Validacion(String usuarioId, String login, String clientId, List<String> scopes, int expiraSeg) {
    }

    /** Respuesta de crear una suscripción: el código HTTP y el mensaje de error de Twitch, si hubo. */
    record Respuesta(int codigo, String mensaje) {
        boolean ok() {
            return codigo == 202 || codigo == 200 || codigo == 409;
        }
    }

    /** El refresh token ya no sirve: hay que volver a vincular. */
    static final class TokenInvalido extends Exception {
        TokenInvalido(String mensaje) {
            super(mensaje);
        }
    }

    private final HttpClient http;
    private final String clientId;
    private final String urlId;
    private final String urlApi;
    private final LongSupplier reloj;

    ClienteTwitch(HttpClient http, String clientId, String urlId, String urlApi, LongSupplier reloj) {
        this.http = http;
        this.clientId = clientId;
        this.urlId = sinBarra(urlId);
        this.urlApi = sinBarra(urlApi);
        this.reloj = reloj;
    }

    private static String sinBarra(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    String clientId() {
        return clientId;
    }

    Codigo pedirCodigo() throws IOException {
        HttpResponse<String> r = enviar(formulario(urlId + "/device",
                Map.of("client_id", clientId, "scopes", String.join(" ", SCOPES))));
        JsonObject o = json(r);
        if (r.statusCode() != 200 || !o.has("device_code")) {
            throw new IOException("Twitch no dio el código (HTTP " + r.statusCode() + ": " + mensaje(o) + ")");
        }
        return new Codigo(o.get("device_code").getAsString(), o.get("user_code").getAsString(),
                o.get("verification_uri").getAsString(), entero(o, "interval", 5), entero(o, "expires_in", 1800));
    }

    /** Pregunta si el streamer ya aceptó. Si aceptó, el token viene sin usuarioId ni login: falta validarlo. */
    Canje canjear(Codigo codigo) throws IOException {
        HttpResponse<String> r = enviar(formulario(urlId + "/token", Map.of(
                "client_id", clientId,
                "scopes", String.join(" ", SCOPES),
                "device_code", codigo.deviceCode(),
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code")));
        JsonObject o = json(r);
        if (r.statusCode() == 200 && o.has("access_token")) {
            return new Canje(Estado.LISTO, tokenDe(o, null), "");
        }
        String m = mensaje(o).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("pending")) {
            return new Canje(Estado.PENDIENTE, null, m);
        }
        if (m.contains("slow")) {
            return new Canje(Estado.LENTO, null, m);
        }
        return new Canje(Estado.FALLO, null, "HTTP " + r.statusCode() + ": " + mensaje(o));
    }

    /** Pide un access token nuevo. En clientes públicos el refresh token también cambia (sirve una vez). */
    Token renovar(Token viejo) throws IOException, TokenInvalido {
        HttpResponse<String> r = enviar(formulario(urlId + "/token", Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", viejo.renovacion())));
        JsonObject o = json(r);
        if (r.statusCode() == 200 && o.has("access_token")) {
            return tokenDe(o, viejo);
        }
        if (r.statusCode() == 400 || r.statusCode() == 401) {
            throw new TokenInvalido("Twitch rechazó la renovación (" + mensaje(o) + ")");
        }
        throw new IOException("No se pudo renovar (HTTP " + r.statusCode() + ")");
    }

    /** null si el token ya no sirve (401). */
    Validacion validar(String acceso) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(urlId + "/validate"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "OAuth " + acceso)
                .GET().build();
        HttpResponse<String> r = enviar(req);
        if (r.statusCode() == 401) {
            return null;
        }
        JsonObject o = json(r);
        if (r.statusCode() != 200 || !o.has("user_id")) {
            throw new IOException("No se pudo validar el token (HTTP " + r.statusCode() + ")");
        }
        List<String> scopes = new java.util.ArrayList<>();
        if (o.has("scopes") && o.get("scopes").isJsonArray()) {
            o.getAsJsonArray("scopes").forEach(e -> scopes.add(e.getAsString()));
        }
        return new Validacion(o.get("user_id").getAsString(), o.get("login").getAsString(),
                texto(o, "client_id"), List.copyOf(scopes), entero(o, "expires_in", 0));
    }

    void revocar(String acceso) throws IOException {
        enviar(formulario(urlId + "/revoke", Map.of("client_id", clientId, "token", acceso)));
    }

    Respuesta suscribir(String acceso, String tipo, String version, Map<String, String> condicion, String sesion)
            throws IOException {
        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("type", tipo);
        cuerpo.addProperty("version", version);
        JsonObject c = new JsonObject();
        condicion.forEach(c::addProperty);
        cuerpo.add("condition", c);
        JsonObject transporte = new JsonObject();
        transporte.addProperty("method", "websocket");
        transporte.addProperty("session_id", sesion);
        cuerpo.add("transport", transporte);
        HttpRequest req = HttpRequest.newBuilder(URI.create(urlApi + "/eventsub/subscriptions"))
                .timeout(Duration.ofSeconds(15))
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer " + acceso)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build();
        HttpResponse<String> r = enviar(req);
        return new Respuesta(r.statusCode(), r.statusCode() / 100 == 2 ? "" : mensaje(json(r)));
    }

    private Token tokenDe(JsonObject o, Token viejo) {
        long expira = reloj.getAsLong() + entero(o, "expires_in", 3600) * 1000L;
        String renovacion = o.has("refresh_token") ? o.get("refresh_token").getAsString()
                : viejo == null ? "" : viejo.renovacion();
        String acceso = o.get("access_token").getAsString();
        return viejo == null ? new Token(acceso, renovacion, expira, null, null, false)
                : viejo.conAcceso(acceso, renovacion, expira);
    }

    private static HttpRequest formulario(String url, Map<String, String> campos) {
        StringJoiner j = new StringJoiner("&");
        campos.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(j.toString()))
                .build();
    }

    private HttpResponse<String> enviar(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrumpido", e);
        }
    }

    private static JsonObject json(HttpResponse<String> r) {
        try {
            JsonElement e = JsonParser.parseString(r.body() == null ? "" : r.body());
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /** El campo "message" de un error de Twitch (nunca trae tokens). */
    private static String mensaje(JsonObject o) {
        String m = texto(o, "message");
        if (m.isEmpty()) {
            m = texto(o, "error");
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static String texto(JsonObject o, String campo) {
        return o.has(campo) && o.get(campo).isJsonPrimitive() ? o.get(campo).getAsString() : "";
    }

    private static int entero(JsonObject o, String campo, int porDefecto) {
        try {
            return o.has(campo) ? o.get(campo).getAsInt() : porDefecto;
        } catch (RuntimeException e) {
            return porDefecto;
        }
    }
}
