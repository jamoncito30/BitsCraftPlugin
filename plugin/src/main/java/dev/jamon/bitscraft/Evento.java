package dev.jamon.bitscraft;

/**
 * Algo que pasó en el canal de un streamer, ya traducido desde EventSub (o simulado con /twitch probar).
 *
 * @param id          id del mensaje de EventSub (para descartar repetidos); en pruebas, uno inventado
 * @param tipo        qué pasó
 * @param canalId     id de Twitch del canal del streamer
 * @param usuario     quién lo hizo (null si fue anónimo)
 * @param monto       bits, subs regaladas, espectadores de la raid; 1 en subs, canjes y follows
 * @param recompensa  título de la recompensa canjeada (solo en {@link Tipo#PUNTOS})
 * @param mensaje     texto que acompaña los bits (puede ser null); nunca se usa en comandos
 * @param llegada     hora de llegada en ms (para descartar lo muy viejo de la fila)
 */
record Evento(String id, Tipo tipo, String canalId, String usuario, int monto, String recompensa,
              String mensaje, long llegada) {

    enum Tipo {
        BITS("bits"), SUB("sub"), SUB_REGALO("sub-regalo"), PUNTOS("puntos"), RAID("raid"), FOLLOW("follow");

        /** Nombre en config.yml (avisos.detalle). */
        final String clave;

        Tipo(String clave) {
            this.clave = clave;
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
                default -> null;
            };
        }
    }
}
