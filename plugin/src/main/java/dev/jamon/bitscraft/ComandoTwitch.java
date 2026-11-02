package dev.jamon.bitscraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;

/** /twitch: vincular, estado, pausa y, para el admin, probar eventos, vaciar filas y recargar. */
final class ComandoTwitch implements TabExecutor {

    private static final String ADMIN = "bitscraft.admin";

    private final Streamers streamers;
    private final Runnable recargar;
    private final LongSupplier reloj;

    ComandoTwitch(Streamers streamers, Runnable recargar, LongSupplier reloj) {
        this.streamers = streamers;
        this.recargar = recargar;
        this.reloj = reloj;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String etiqueta, String[] args) {
        String sub = args.length == 0 ? "estado" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "vincular" -> {
                Player p = jugador(s);
                if (p != null) {
                    streamers.vincular(p);
                }
            }
            case "desvincular" -> {
                Player p = jugador(s);
                if (p != null) {
                    if (streamers.vinculo(p.getUniqueId()) == null) {
                        error(s, "No tienes ningún canal vinculado.");
                    } else {
                        streamers.desvincular(p.getUniqueId());
                        ok(s, "Listo: tu canal quedó desconectado y el permiso de Twitch, revocado.");
                    }
                }
            }
            case "estado" -> {
                UUID objetivo = objetivo(s, args, 1);
                if (objetivo != null) {
                    streamers.estado(objetivo).forEach(l -> s.sendMessage(Component.text(l, NamedTextColor.LIGHT_PURPLE)));
                }
            }
            case "pausa", "reanudar" -> {
                UUID objetivo = objetivo(s, args, 1);
                if (objetivo != null) {
                    boolean pausa = sub.equals("pausa");
                    streamers.pausar(objetivo, pausa);
                    ok(s, pausa ? "En pausa: lo que llegue se guarda (hasta " + streamers.ritmo().colaMaxima()
                            + ") y sale cuando uses /twitch reanudar."
                            : "Seguimos: la fila vuelve a salir en " + streamers.ritmo().graciaMs() / 1000 + " s.");
                }
            }
            case "probar" -> {
                if (admin(s)) {
                    Player p = jugador(s);
                    if (p != null) {
                        probar(s, p.getUniqueId(), Arrays.copyOfRange(args, 1, args.length));
                    }
                }
            }
            case "probar-en" -> {
                if (admin(s)) {
                    if (args.length < 3) {
                        error(s, "Uso: /twitch probar-en <jugador> <bits|sub|sub-regalo|puntos|raid|follow> [monto o recompensa]");
                    } else {
                        UUID j = uuid(args[1]);
                        if (j == null) {
                            error(s, "No conozco a " + args[1] + ".");
                        } else {
                            probar(s, j, Arrays.copyOfRange(args, 2, args.length));
                        }
                    }
                }
            }
            case "vaciar" -> {
                if (admin(s)) {
                    UUID j = args.length > 1 ? uuid(args[1]) : s instanceof Player p ? p.getUniqueId() : null;
                    if (j == null) {
                        error(s, "Uso: /twitch vaciar <jugador>");
                    } else {
                        int n = streamers.fila(j).tamano();
                        streamers.fila(j).vaciar();
                        ok(s, "Fila de " + Streamers.nombre(j) + " vaciada (" + n + " evento(s)).");
                    }
                }
            }
            case "recargar" -> {
                if (admin(s)) {
                    recargar.run();
                    ok(s, "Configuración recargada (los avisos de la consola dicen si algo del catálogo está mal).");
                }
            }
            case "mock" -> {
                if (admin(s)) {
                    if (args.length < 3) {
                        error(s, "Uso: /twitch mock <jugador> <user-id> [login]  (solo con las URLs de Twitch apuntando a 127.0.0.1)");
                    } else {
                        UUID j = uuid(args[1]);
                        String login = args.length > 3 ? args[3] : "mock_" + args[2];
                        if (j == null) {
                            error(s, "No conozco a " + args[1] + ".");
                        } else if (!args[2].matches("[0-9]{1,20}")) {
                            error(s, "El user-id es un número.");
                        } else if (streamers.mock(j, args[2], login)) {
                            ok(s, Streamers.nombre(j) + " quedó vinculado al canal de prueba " + login + " (" + args[2] + ").");
                        } else {
                            error(s, "Solo se puede con twitch.url-eventsub apuntando a 127.0.0.1 (Twitch CLI).");
                        }
                    }
                }
            }
            default -> ayuda(s);
        }
        return true;
    }

    private void probar(CommandSender s, UUID j, String[] args) {
        if (args.length == 0) {
            error(s, "Uso: probar <bits|sub|sub-regalo|puntos|raid|follow> [monto o recompensa] [usuario]");
            return;
        }
        Evento.Tipo tipo = Evento.Tipo.desde(args[0]);
        if (tipo == null) {
            error(s, "Tipo desconocido: " + args[0]);
            return;
        }
        int monto = 1;
        String recompensa = null;
        String usuario = "Prueba";
        if (tipo == Evento.Tipo.PUNTOS) {
            if (args.length < 2) {
                error(s, "Falta el título de la recompensa.");
                return;
            }
            recompensa = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        } else {
            if (args.length > 1) {
                try {
                    monto = Integer.parseInt(args[1].replace(".", ""));
                } catch (NumberFormatException e) {
                    error(s, "El monto tiene que ser un número.");
                    return;
                }
            } else if (tipo == Evento.Tipo.BITS) {
                monto = 100;
            }
            if (args.length > 2) {
                usuario = args[2];
            }
        }
        Streamers.Vinculo v = streamers.vinculo(j);
        String canalId = v == null ? "prueba" : v.token.usuarioId();
        Evento ev = new Evento("prueba-" + System.nanoTime(), tipo, canalId, usuario, monto, recompensa, null, reloj.getAsLong());
        Catalogo.Entrada e = streamers.catalogo().elegir(ev);
        streamers.recibir(j, ev);
        ok(s, "Evento de prueba para " + Streamers.nombre(j) + ": " + tipo.clave + " " + (recompensa == null ? monto : recompensa)
                + (e == null ? " (no hace nada en el catálogo: solo aviso)" : " → " + e.nombre()));
    }

    /** El jugador sobre el que actúa: uno mismo, u otro si se nombra y quien lo pide es admin. */
    private UUID objetivo(CommandSender s, String[] args, int indice) {
        if (args.length > indice) {
            if (!s.hasPermission(ADMIN)) {
                error(s, "Solo el admin puede hacerlo con otro jugador.");
                return null;
            }
            UUID j = uuid(args[indice]);
            if (j == null) {
                error(s, "No conozco a " + args[indice] + ".");
            }
            return j;
        }
        Player p = jugador(s);
        return p == null ? null : p.getUniqueId();
    }

    private static UUID uuid(String nombre) {
        Player p = Bukkit.getPlayerExact(nombre);
        if (p != null) {
            return p.getUniqueId();
        }
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(nombre);
        return o == null ? null : o.getUniqueId();
    }

    private static Player jugador(CommandSender s) {
        if (s instanceof Player p) {
            return p;
        }
        error(s, "Esto se usa dentro del juego (o con el nombre de un jugador).");
        return null;
    }

    private static boolean admin(CommandSender s) {
        if (s.hasPermission(ADMIN)) {
            return true;
        }
        error(s, "No tienes permiso para eso.");
        return false;
    }

    private static void ok(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.GREEN));
    }

    private static void error(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.RED));
    }

    private static void ayuda(CommandSender s) {
        List<String> l = new ArrayList<>(List.of(
                "/twitch vincular — conectar tu canal de Twitch",
                "/twitch estado — tu canal, la conexión y la fila",
                "/twitch pausa · /twitch reanudar — parar y seguir (lo que llega se guarda)",
                "/twitch desvincular — desconectar tu canal"));
        if (s.hasPermission(ADMIN)) {
            l.addAll(List.of(
                    "/twitch probar <tipo> [monto|recompensa] — simular un evento en ti",
                    "/twitch probar-en <jugador> <tipo> [monto|recompensa]",
                    "/twitch pausa|reanudar|estado <jugador> · /twitch vaciar <jugador>",
                    "/twitch recargar · /twitch mock <jugador> <user-id> [login]"));
        }
        l.forEach(x -> s.sendMessage(Component.text(x, NamedTextColor.LIGHT_PURPLE)));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String etiqueta, String[] args) {
        boolean admin = s.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("vincular", "desvincular", "estado", "pausa", "reanudar"));
            if (admin) {
                subs.addAll(List.of("probar", "probar-en", "vaciar", "recargar", "mock"));
            }
            return filtrar(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!admin) {
            return List.of();
        }
        List<String> tipos = Arrays.stream(Evento.Tipo.values()).map(t -> t.clave).toList();
        if (sub.equals("probar")) {
            return args.length == 2 ? filtrar(tipos, args[1]) : args.length == 3 ? ejemplos(args[1], args[2]) : List.of();
        }
        if (sub.equals("probar-en")) {
            return switch (args.length) {
                case 2 -> filtrar(jugadores(), args[1]);
                case 3 -> filtrar(tipos, args[2]);
                case 4 -> ejemplos(args[2], args[3]);
                default -> List.of();
            };
        }
        if (args.length == 2 && List.of("estado", "pausa", "reanudar", "vaciar", "mock").contains(sub)) {
            List<String> l = new ArrayList<>(jugadores());
            streamers.nombresVinculados().forEach(n -> {
                if (!l.contains(n)) {
                    l.add(n);
                }
            });
            return filtrar(l, args[1]);
        }
        return List.of();
    }

    private List<String> ejemplos(String tipo, String prefijo) {
        Evento.Tipo t = Evento.Tipo.desde(tipo);
        return t == null ? List.of() : filtrar(streamers.catalogo().ejemplos(t), prefijo);
    }

    private static List<String> jugadores() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filtrar(List<String> l, String prefijo) {
        String p = prefijo.toLowerCase(Locale.ROOT);
        return l.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
