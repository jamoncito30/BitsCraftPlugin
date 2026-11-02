package dev.jamon.bitscraft;

/**
 * Credenciales de un streamer. {@link #toString()} nunca muestra los tokens, para que no terminen
 * en un log por accidente.
 *
 * @param expira  hora (ms) en que vence el access token
 * @param mock    vínculo de prueba contra la Twitch CLI (no se guarda ni se renueva)
 */
record Token(String acceso, String renovacion, long expira, String usuarioId, String login, boolean mock) {

    Token conAcceso(String acceso, String renovacion, long expira) {
        return new Token(acceso, renovacion, expira, usuarioId, login, mock);
    }

    @Override
    public String toString() {
        return "Token[canal=" + login + " (" + usuarioId + "), vence=" + expira + (mock ? ", mock" : "") + "]";
    }
}
