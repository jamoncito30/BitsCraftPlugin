package dev.jamon.bitscraft;

/**
 * Algo que pasó en el directo de un streamer, ya traducido desde EventSub (Twitch), desde TikTok LIVE
 * o simulado con /bits probar. Twitch y TikTok entran a la misma fila del streamer.
 *
 * @param id          id del mensaje (para descartar repetidos); en pruebas, uno inventado
 * @param tipo        qué pasó (y en qué plataforma: {@link Tipo#plataforma})
 * @param canalId     id de Twitch del canal del streamer, o el @ de TikTok (sin @, en minúsculas)
 * @param usuario     quién lo hizo (null si fue anónimo)
 * @param monto       bits, subs regaladas, espectadores de la raid, monedas de TikTok (ya multiplicadas
 *                    por la cantidad) o likes; 1 en subs, canjes y follows
 * @param recompensa  título de la recompensa canjeada ({@link Tipo#PUNTOS}) o nombre del regalo de TikTok
 * @param mensaje     texto que acompaña los bits (puede ser null); nunca se usa en comandos
 * @param llegada     hora de llegada en ms (para descartar lo muy viejo de la fila)
 * @param cantidad    cuántos regalos iguales mandó de una vez (combo de TikTok); 1 en lo demás
 */
record Evento(String id, Tipo tipo, String canalId, String usuario, int monto, String recompensa,
              String mensaje, long llegada, int cantidad) {

    Evento(String id, Tipo tipo, String canalId, String usuario, int monto, String recompensa,
           String mensaje, long llegada) {
        this(id, tipo, canalId, usuario, monto, recompensa, mensaje, llegada, 1);
    }

    enum Plataforma {
        TWITCH("Twitch"), TIKTOK("TikTok");

        final String nombre;

        Plataforma(String nombre) {
            this.nombre = nombre;
        }
    }

    Plataforma plataforma() {
        return tipo.plataforma;
    }

    enum Tipo {
        BITS("bits", Plataforma.TWITCH), SUB("sub", Plataforma.TWITCH), SUB_REGALO("sub-regalo", Plataforma.TWITCH),
        PUNTOS("puntos", Plataforma.TWITCH), RAID("raid", Plataforma.TWITCH), FOLLOW("follow", Plataforma.TWITCH),
        TIKTOK_REGALO("tiktok-regalo", Plataforma.TIKTOK), TIKTOK_FOLLOW("tiktok-follow", Plataforma.TIKTOK),
        TIKTOK_SHARE("tiktok-share", Plataforma.TIKTOK), TIKTOK_LIKES("tiktok-likes", Plataforma.TIKTOK);

        /** Nombre en config.yml (avisos.detalle) y en /bits probar. */
        final String clave;
        final Plataforma plataforma;

        Tipo(String clave, Plataforma plataforma) {
            this.clave = clave;
            this.plataforma = plataforma;
        }

        static Tipo desde(String texto) {
            String t = texto.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (Tipo tipo : values()) {
                if (tipo.clave.equals(t)) {
                    return tipo;
                }
            }
            return switch (t) {
                case "cheer" -> BITS;
                case "regalo", "regalos", "gift" -> SUB_REGALO;
                case "canje", "recompensa" -> PUNTOS;
                case "tiktok", "tiktok-gift" -> TIKTOK_REGALO;
                case "tiktok-like", "likes" -> TIKTOK_LIKES;
                default -> null;
            };
        }
    }
}
