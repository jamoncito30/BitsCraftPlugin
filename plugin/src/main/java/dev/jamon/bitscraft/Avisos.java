package dev.jamon.bitscraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * El aviso de quién donó qué: título en la pantalla del streamer y una línea en el chat para todos.
 * Lo que viene de Twitch (nombres, mensajes, títulos de recompensas) entra como texto plano, así
 * que nadie puede meter formato ni etiquetas de MiniMessage.
 */
final class Avisos {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final String titulo;
    private final String subtitulo;
    private final String chat;
    private final String chatMensaje;
    private final String chatSoloAviso;
    private final String sonido;
    private final String anonimo;
    private final boolean mostrarMensaje;
    private final Map<Evento.Tipo, String> detalles = new EnumMap<>(Evento.Tipo.class);

    Avisos(ConfigurationSection s) {
        if (s == null) {
            s = new org.bukkit.configuration.MemoryConfiguration();
        }
        titulo = s.getString("titulo", "<light_purple><usuario>");
        subtitulo = s.getString("subtitulo", "<gold><detalle></gold> <gray>»</gray> <white><nombre>");
        chat = s.getString("chat", "<light_purple><usuario></light_purple> → <streamer>: <gold><detalle></gold> (<nombre>)");
        chatMensaje = s.getString("chat-mensaje-bits", "<gray>  “<mensaje>”");
        chatSoloAviso = s.getString("chat-solo-aviso", "<light_purple><usuario></light_purple> → <streamer>: <gold><detalle>");
        sonido = s.getString("sonido", "block.note_block.pling");
        anonimo = s.getString("anonimo", "Anónimo");
        mostrarMensaje = s.getBoolean("mostrar-mensaje-bits", false);
        ConfigurationSection d = s.getConfigurationSection("detalle");
        for (Evento.Tipo t : Evento.Tipo.values()) {
            detalles.put(t, d == null ? t.clave : d.getString(t.clave, t.clave));
        }
    }

    private TagResolver valores(String streamer, Evento ev, String nombreEntrada) {
        String usuario = ev.usuario() == null ? anonimo : ev.usuario();
        TagResolver base = TagResolver.resolver(
                Placeholder.unparsed("usuario", usuario),
                Placeholder.unparsed("streamer", streamer),
                Placeholder.unparsed("monto", String.valueOf(ev.monto())),
                Placeholder.unparsed("recompensa", ev.recompensa() == null ? "" : ev.recompensa()),
                Placeholder.unparsed("mensaje", recortar(ev.mensaje(), 120)),
                Placeholder.unparsed("nombre", nombreEntrada == null ? "" : nombreEntrada));
        Component detalle = MM.deserialize(detalles.get(ev.tipo()), base);
        return TagResolver.resolver(base, Placeholder.component("detalle", detalle));
    }

    private static String recortar(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** Aviso de algo que va a pasar: título al streamer, sonido y línea de chat para todos. */
    void mostrar(Player streamer, Evento ev, String nombreEntrada, long retardoMs) {
        TagResolver v = valores(streamer.getName(), ev, nombreEntrada);
        long estar = Math.max(2000, retardoMs + 1000);
        streamer.showTitle(Title.title(MM.deserialize(titulo, v), MM.deserialize(subtitulo, v),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(estar), Duration.ofMillis(500))));
        try {
            streamer.playSound(Sound.sound(Key.key(sonido), Sound.Source.MASTER, 1f, 1.2f));
        } catch (RuntimeException ignorado) {
            // Un nombre de sonido inválido en la config no debe cortar el aviso.
        }
        Bukkit.broadcast(MM.deserialize(chat, v));
        if (mostrarMensaje && ev.tipo() == Evento.Tipo.BITS && ev.mensaje() != null && !ev.mensaje().isBlank()) {
            Bukkit.broadcast(MM.deserialize(chatMensaje, v));
        }
    }

    /** Solo la línea de chat (raids chicas, follows durante el enfriamiento, recompensas sin acción). */
    void soloChat(String streamer, Evento ev) {
        Bukkit.broadcast(MM.deserialize(chatSoloAviso, valores(streamer, ev, null)));
    }
}
