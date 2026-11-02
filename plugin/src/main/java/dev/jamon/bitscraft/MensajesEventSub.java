package dev.jamon.bitscraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** Traduce las notificaciones de EventSub a {@link Evento}, y define a qué se suscribe cada canal. */
final class MensajesEventSub {

    /** Una suscripción de EventSub: tipo, versión y la condición para el canal del streamer. */
    record Suscripcion(String tipo, String version, Map<String, String> condicion) {
    }

    private MensajesEventSub() {
    }

    static List<Suscripcion> suscripciones(String canalId) {
        Map<String, String> canal = Map.of("broadcaster_user_id", canalId);
        return List.of(
                new Suscripcion("channel.cheer", "1", canal),
                new Suscripcion("channel.channel_points_custom_reward_redemption.add", "1", canal),
                new Suscripcion("channel.subscribe", "1", canal),
                new Suscripcion("channel.subscription.message", "1", canal),
                new Suscripcion("channel.subscription.gift", "1", canal),
                new Suscripcion("channel.raid", "1", Map.of("to_broadcaster_user_id", canalId)),
                new Suscripcion("channel.follow", "2",
                        Map.of("broadcaster_user_id", canalId, "moderator_user_id", canalId)));
    }

    /**
     * El evento de una notificación, o null si no hace nada (tipo desconocido, o una sub que viene
     * de un regalo: esas ya se cuentan en channel.subscription.gift).
     */
    static Evento traducir(String tipo, JsonObject ev, String id, long ahora) {
        return switch (tipo) {
            case "channel.cheer" -> new Evento(id, Evento.Tipo.BITS, texto(ev, "broadcaster_user_id"),
                    anonimo(ev) ? null : nombre(ev, "user_name", "user_login"),
                    entero(ev, "bits"), null, texto(ev, "message"), ahora);
            case "channel.channel_points_custom_reward_redemption.add" -> {
                JsonObject r = ev.has("reward") && ev.get("reward").isJsonObject() ? ev.getAsJsonObject("reward") : new JsonObject();
                yield new Evento(id, Evento.Tipo.PUNTOS, texto(ev, "broadcaster_user_id"),
                        nombre(ev, "user_name", "user_login"), 1, texto(r, "title"), null, ahora);
            }
            case "channel.subscribe" -> si(ev, "is_gift") ? null
                    : new Evento(id, Evento.Tipo.SUB, texto(ev, "broadcaster_user_id"),
                    nombre(ev, "user_name", "user_login"), 1, null, null, ahora);
            case "channel.subscription.message" -> new Evento(id, Evento.Tipo.SUB, texto(ev, "broadcaster_user_id"),
                    nombre(ev, "user_name", "user_login"), 1, null, null, ahora);
            case "channel.subscription.gift" -> new Evento(id, Evento.Tipo.SUB_REGALO, texto(ev, "broadcaster_user_id"),
                    anonimo(ev) ? null : nombre(ev, "user_name", "user_login"),
                    Math.max(1, entero(ev, "total")), null, null, ahora);
            case "channel.raid" -> new Evento(id, Evento.Tipo.RAID, texto(ev, "to_broadcaster_user_id"),
                    nombre(ev, "from_broadcaster_user_name", "from_broadcaster_user_login"),
                    entero(ev, "viewers"), null, null, ahora);
            case "channel.follow" -> new Evento(id, Evento.Tipo.FOLLOW, texto(ev, "broadcaster_user_id"),
                    nombre(ev, "user_name", "user_login"), 1, null, null, ahora);
            default -> null;
        };
    }

    private static boolean anonimo(JsonObject o) {
        return si(o, "is_anonymous");
    }

    private static String nombre(JsonObject o, String campo, String alternativo) {
        String n = texto(o, campo);
        return n.isEmpty() ? (texto(o, alternativo).isEmpty() ? null : texto(o, alternativo)) : n;
    }

    /** Texto de un campo; "" si falta o es null. Para "message" de cheer acepta {text: ...}. */
    static String texto(JsonObject o, String campo) {
        if (o == null || !o.has(campo)) {
            return "";
        }
        JsonElement e = o.get(campo);
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
            return texto(e.getAsJsonObject(), "text");
        }
        return "";
    }

    private static int entero(JsonObject o, String campo) {
        try {
            return o.has(campo) && o.get(campo).isJsonPrimitive() ? o.get(campo).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean si(JsonObject o, String campo) {
        return o.has(campo) && o.get(campo).isJsonPrimitive() && o.get(campo).getAsBoolean();
    }
}
