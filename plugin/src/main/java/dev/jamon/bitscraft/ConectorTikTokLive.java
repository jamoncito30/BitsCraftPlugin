package dev.jamon.bitscraft;

import io.github.jwdeveloper.tiktok.api.data.models.users.User;
import io.github.jwdeveloper.tiktok.api.exceptions.TikTokLiveException;
import io.github.jwdeveloper.tiktok.api.exceptions.TikTokLiveOfflineHostException;
import io.github.jwdeveloper.tiktok.api.exceptions.TikTokLiveUnknownHostException;
import io.github.jwdeveloper.tiktok.api.exceptions.TikTokSignServerException;
import io.github.jwdeveloper.tiktok.api.live.LiveClient;
import io.github.jwdeveloper.tiktok.client.TikTokLive;

import java.util.logging.Level;

/**
 * El único archivo que usa TikTokLiveJava (jwdeveloper, MIT). Se carga por nombre desde {@link TikTok}:
 * si la librería falta o cambió, falla al cargar esta clase y solo se apaga TikTok.
 * La clave de Euler Stream se pasa a la librería y se borra de cualquier mensaje antes de mostrarlo.
 */
final class ConectorTikTokLive implements TikTok.Conector {

    private final String clave;

    ConectorTikTokLive(String clave) {
        this.clave = clave;
        // Toca una clase de la librería ya, para que si falta falle al cargar y no a mitad de un directo.
        TikTokLive.class.getName();
    }

    @Override
    public TikTok.Sesion conectar(String usuario, TikTok.Receptor r) throws TikTok.FalloTikTok {
        LiveClient cliente = TikTokLive.newClient(usuario)
                .configure(s -> {
                    s.setApiKey(clave);
                    s.setPrintToConsole(false);
                    s.setLogLevel(Level.OFF);
                    s.setRetryOnConnectionFailure(false);
                    s.setFetchGifts(false);
                    s.setThrowOnAgeRestriction(false);
                })
                // En la 1.13 onGift sale una sola vez por regalo: los combos al terminar, con el total en getCombo().
                .onGift((c, e) -> r.regalo(String.valueOf(e.getMessageId()), nombre(e.getUser()),
                        e.getGift().getName(), e.getGift().getDiamondCost(), Math.max(1, e.getCombo())))
                .onFollow((c, e) -> r.follow(String.valueOf(e.getMessageId()), nombre(e.getUser())))
                .onShare((c, e) -> r.share(String.valueOf(e.getMessageId()), nombre(e.getUser())))
                .onLike((c, e) -> r.likes(String.valueOf(e.getMessageId()), nombre(e.getUser()), e.getLikes()))
                .onConnected((c, e) -> r.conectado())
                .onDisconnected((c, e) -> r.desconectado(ocultar(e.getReason())))
                .onLiveEnded((c, e) -> r.desconectado("terminó el directo"))
                .build();
        try {
            cliente.connect();
        } catch (TikTokLiveOfflineHostException e) {
            throw new TikTok.FalloTikTok(TikTok.Fallo.FUERA_DE_VIVO, "no está en vivo");
        } catch (TikTokLiveUnknownHostException e) {
            throw new TikTok.FalloTikTok(TikTok.Fallo.NO_EXISTE, "no existe esa cuenta");
        } catch (TikTokSignServerException e) {
            throw new TikTok.FalloTikTok(TikTok.Fallo.FIRMA, ocultar(e.getMessage()));
        } catch (TikTokLiveException e) {
            throw new TikTok.FalloTikTok(TikTok.Fallo.OTRO, ocultar(e.getMessage()));
        }
        return () -> cliente.disconnect();
    }

    private static String nombre(User u) {
        if (u == null) {
            return null;
        }
        String n = u.getName();
        return n == null || n.isBlank() ? u.getProfileName() : n;
    }

    /** Quita la clave de cualquier texto y lo recorta, por si la librería la incluyó en un mensaje. */
    private String ocultar(String texto) {
        if (texto == null) {
            return null;
        }
        String t = clave.isEmpty() ? texto : texto.replace(clave, "***");
        t = t.replaceAll("(?i)(api[_-]?key|apiKey)=[^&\\s]+", "$1=***");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
