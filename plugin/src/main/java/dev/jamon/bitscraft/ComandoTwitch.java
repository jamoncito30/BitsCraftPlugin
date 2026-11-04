package dev.jamon.bitscraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * /bits (alias /twitch): vincular Twitch, poner la cuenta de TikTok, estado, pausa y, para el admin, probar
 * eventos, vaciar filas y recargar.
 */
final class ComandoTwitch implements TabExecutor {

    private static final String ADMIN = "bitscraft.admin";

    /** Monedas aproximadas de regalos comunes de TikTok, para /bits probar (se puede dar el valor a mano). */
    static final Map<String, Integer> MONEDAS_CONOCIDAS = Map.ofEntries(
            Map.entry("rose", 1), Map.entry("tiktok", 1), Map.entry("gg", 1), Map.entry("ice cream cone", 1),
            Map.entry("heart me", 1), Map.entry("finger heart", 5), Map.entry("perfume", 20),
            Map.entry("doughnut", 30), Map.entry("hand hearts", 100), Map.entry("corgi", 299),
            Map.entry("money gun", 500), Map.entry("galaxy", 1000), Map.entry("lion", 29999),
            Map.entry("universe", 44999));

    private final Streamers streamers;
    private final TikTok tiktok;
    private final Runnable recargar;
    private final Consumer<Boolean> guardarTikTokActivo;
    private final LongSupplier reloj;

    ComandoTwitch(Streamers streamers, TikTok tiktok, Runnable recargar, Consumer<Boolean> guardarTikTokActivo,
                  LongSupplier reloj) {
        this.streamers = streamers;
        this.tiktok = tiktok;
        this.recargar = recargar;
        this.guardarTikTokActivo = guardarTikTokActivo;
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
                    List<String> l = new ArrayList<>(streamers.estado(objetivo));
                    l.addAll(1, tiktok.estado(Streamers.nombre(objetivo)));
                    l.forEach(x -> s.sendMessage(Component.text(x, NamedTextColor.LIGHT_PURPLE)));
                }
            }
            case "pausa", "reanudar" -> {
                UUID objetivo = objetivo(s, args, 1);
                if (objetivo != null) {
                    boolean pausa = sub.equals("pausa");
                    streamers.pausar(objetivo, pausa);
                    ok(s, pausa ? "En pausa: lo que llegue se guarda (hasta " + streamers.ritmo().colaMaxima()
                            + ") y sale cuando uses /bits reanudar."
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
                        error(s, "Uso: /bits probar-en <jugador> <tipo> [monto, recompensa o regalo]");
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
                        error(s, "Uso: /bits vaciar <jugador>");
                    } else {
                        int n = streamers.fila(j).tamano();
                        streamers.fila(j).vaciar();
                        ok(s, "Fila de " + Streamers.nombre(j) + " vaciada (" + n + " evento(s)).");
                    }
                }
            }
            case "tiktok" -> tiktok(s, Arrays.copyOfRange(args, 1, args.length));
            case "recargar" -> {
                if (admin(s)) {
                    recargar.run();
                    ok(s, "Configuración recargada (los avisos de la consola dicen si algo del catálogo está mal).");
                }
            }
            case "mock" -> {
                if (admin(s)) {
                    if (args.length < 3) {
                        error(s, "Uso: /bits mock <jugador> <user-id> [login]  (solo con las URLs de Twitch apuntando a 127.0.0.1)");
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
            error(s, "Uso: probar <tipo> [monto o recompensa] [usuario]   ·   probar tiktok-regalo <Regalo> [cantidad] [monedas c/u]");
            return;
        }
        Evento.Tipo tipo = Evento.Tipo.desde(args[0]);
        if (tipo == null) {
            error(s, "Tipo desconocido: " + args[0]);
            return;
        }
        int monto = 1;
        int cantidad = 1;
        String recompensa = null;
        String usuario = "Prueba";
        if (tipo == Evento.Tipo.TIKTOK_REGALO) {
            if (args.length < 2) {
                error(s, "Falta el regalo (con _ en vez de espacios): probar tiktok-regalo Rose 5");
                return;
            }
            recompensa = args[1].replace('_', ' ');
            try {
                cantidad = args.length > 2 ? Math.max(1, Integer.parseInt(args[2])) : 1;
                Integer conocido = MONEDAS_CONOCIDAS.get(Catalogo.normalizar(recompensa));
                int cadaUno = args.length > 3 ? Integer.parseInt(args[3].replace(".", "")) : conocido == null ? 1 : conocido;
                monto = (int) Math.min(Integer.MAX_VALUE, (long) cadaUno * cantidad);
            } catch (NumberFormatException e) {
                error(s, "La cantidad y las monedas son números.");
                return;
            }
        } else if (tipo == Evento.Tipo.PUNTOS) {
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
        String canalId;
        if (tipo.plataforma == Evento.Plataforma.TIKTOK) {
            String a = tiktok.cuentas().get(Streamers.nombre(j).toLowerCase(Locale.ROOT));
            canalId = a == null ? "prueba" : a;
        } else {
            canalId = v == null ? "prueba" : v.token.usuarioId();
        }
        Evento ev = new Evento("prueba-" + System.nanoTime(), tipo, canalId, usuario, monto, recompensa, null,
                reloj.getAsLong(), cantidad);
        Catalogo.Entrada e = streamers.catalogo().elegir(ev);
        streamers.recibir(j, ev);
        String que = tipo == Evento.Tipo.TIKTOK_REGALO
                ? cantidad + "× " + recompensa + " (" + monto + " monedas = " + streamers.catalogo().bitsDeRegalo(ev) + " bits)"
                : recompensa == null ? String.valueOf(monto) : recompensa;
        ok(s, "Evento de prueba para " + Streamers.nombre(j) + ": " + tipo.clave + " " + que
                + (e == null ? " (no hace nada en el catálogo: solo aviso)" : " → " + e.nombre()));
    }

    // ---- /bits tiktok ----

    private void tiktok(CommandSender s, String[] args) {
        String sub = args.length == 0 ? "estado" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "estado" -> {
                UUID objetivo = objetivo(s, args, 1);
                if (objetivo != null) {
                    tiktok.estado(Streamers.nombre(objetivo)).forEach(l -> s.sendMessage(Component.text(l, NamedTextColor.AQUA)));
                }
            }
            case "cuenta" -> {
                if (args.length < 2) {
                    error(s, "Uso: /bits tiktok cuenta <@usuario|quitar> [jugador]");
                    return;
                }
                UUID objetivo = objetivo(s, args, 2);
                if (objetivo == null) {
                    return;
                }
                boolean quitar = args[1].equalsIgnoreCase("quitar");
                String a = quitar ? null : TikTok.arroba(args[1]);
                if (!quitar && a == null) {
                    error(s, "Ese no parece un usuario de TikTok (letras, números, _ y punto; sin espacios).");
                    return;
                }
                try {
                    tiktok.cambiarCuenta(Streamers.nombre(objetivo), a);
                } catch (IOException e) {
                    error(s, "No se pudo guardar: " + e.getMessage());
                    return;
                }
                ok(s, quitar ? "Listo: " + Streamers.nombre(objetivo) + " ya no tiene cuenta de TikTok."
                        : "Listo: los regalos del directo de TikTok @" + a + " le llegan a " + Streamers.nombre(objetivo)
                        + (tiktok.activo() ? "." : ". (TikTok está apagado: /bits tiktok activar)"));
            }
            case "activar", "desactivar" -> {
                if (admin(s)) {
                    boolean si = sub.equals("activar");
                    guardarTikTokActivo.accept(si);
                    tiktok.activar(si);
                    ok(s, si ? "TikTok activado (mira /bits estado)." : "TikTok apagado. Twitch sigue igual.");
                }
            }
            case "recargar" -> {
                if (admin(s)) {
                    tiktok.reiniciar();
                    ok(s, "TikTok: se volvió a leer la clave y se reconectó todo.");
                }
            }
            case "simular" -> {
                if (admin(s)) {
                    simular(s, Arrays.copyOfRange(args, 1, args.length));
                }
            }
            default -> {
                // "/bits tiktok @usuario [jugador]" también sirve para poner la cuenta.
                String[] nuevo = new String[args.length + 1];
                nuevo[0] = "cuenta";
                System.arraycopy(args, 0, nuevo, 1, args.length);
                tiktok(s, nuevo);
            }
        }
    }

    /** Lo que mandaría TikTok, por el mismo camino que un directo real (solo con tiktok.prueba: true). */
    private void simular(CommandSender s, String[] args) {
        TikTok.ConectorFalso f = tiktok.falso();
        if (f == null) {
            error(s, "Solo en modo de prueba: tiktok.activo: true y tiktok.prueba: true en config.yml.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("romper")) {
            tiktok.simularLibreriaRota();
            ok(s, "Simulando que la librería de TikTok no carga: TikTok debería quedar apagado y Twitch seguir igual.");
            return;
        }
        if (args.length < 2) {
            error(s, "Uso: /bits tiktok simular <jugador> <regalo <Regalo> [cantidad] [monedas]|follow|share|likes <n>|repetir|cortar>  ·  simular romper");
            return;
        }
        String a = tiktok.cuentas().get(args[0].toLowerCase(Locale.ROOT));
        TikTok.Receptor r = a == null ? null : f.receptor(a);
        if (r == null) {
            error(s, args[0] + " no tiene una cuenta de TikTok conectada (mira /bits tiktok estado " + args[0] + ").");
            return;
        }
        String id = String.valueOf(System.nanoTime());
        try {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "regalo" -> {
                    String regalo = args.length > 2 ? args[2].replace('_', ' ') : "Rose";
                    int cantidad = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                    Integer conocido = MONEDAS_CONOCIDAS.get(Catalogo.normalizar(regalo));
                    int monedas = args.length > 4 ? Integer.parseInt(args[4]) : conocido == null ? 1 : conocido;
                    r.regalo(id, "fan_de_prueba", regalo, monedas, cantidad);
                }
                case "follow" -> r.follow(id, "fan_de_prueba");
                case "share" -> r.share(id, "fan_de_prueba");
                case "likes" -> r.likes(id, "fan_de_prueba", args.length > 2 ? Integer.parseInt(args[2]) : 100);
                case "repetir" -> {
                    // El mismo id dos veces: el segundo se descarta.
                    r.regalo("repetido-" + id, "fan_de_prueba", "Rose", 1, 1);
                    r.regalo("repetido-" + id, "fan_de_prueba", "Rose", 1, 1);
                }
                case "cortar" -> r.desconectado("prueba");
                default -> {
                    error(s, "Qué simular: regalo, follow, share, likes, repetir, cortar (o simular romper).");
                    return;
                }
            }
        } catch (NumberFormatException e) {
            error(s, "La cantidad, las monedas y los likes son números.");
            return;
        }
        ok(s, "Simulado en @" + a + ": " + String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
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
                "/bits vincular — conectar tu canal de Twitch",
                "/bits tiktok cuenta <@usuario> — conectar tu directo de TikTok",
                "/bits estado — Twitch, TikTok y la fila",
                "/bits pausa · /bits reanudar — parar y seguir (lo que llega se guarda)",
                "/bits desvincular — desconectar tu canal de Twitch"));
        if (s.hasPermission(ADMIN)) {
            l.addAll(List.of(
                    "/bits probar <tipo> [monto|recompensa] — simular un evento en ti",
                    "/bits probar-en <jugador> <tipo> [...] · tiktok-regalo <Regalo> [cantidad] [monedas]",
                    "/bits pausa|reanudar|estado <jugador> · /bits vaciar <jugador>",
                    "/bits tiktok activar|desactivar|recargar · cuenta <@usuario> <jugador> · simular",
                    "/bits recargar · /bits mock <jugador> <user-id> [login]"));
        }
        l.forEach(x -> s.sendMessage(Component.text(x, NamedTextColor.LIGHT_PURPLE)));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String etiqueta, String[] args) {
        boolean admin = s.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("vincular", "desvincular", "estado", "pausa", "reanudar", "tiktok"));
            if (admin) {
                subs.addAll(List.of("probar", "probar-en", "vaciar", "recargar", "mock"));
            }
            return filtrar(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("tiktok")) {
            return completarTikTok(admin, args);
        }
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

    private static List<String> completarTikTok(boolean admin, String[] args) {
        if (args.length == 2) {
            List<String> l = new ArrayList<>(List.of("estado", "cuenta"));
            if (admin) {
                l.addAll(List.of("activar", "desactivar", "recargar", "simular"));
            }
            return filtrar(l, args[1]);
        }
        if (!admin) {
            return List.of();
        }
        boolean simular = args[1].equalsIgnoreCase("simular");
        if (simular && args.length == 3) {
            List<String> l = new ArrayList<>(jugadores());
            l.add("romper");
            return filtrar(l, args[2]);
        }
        if (simular && args.length == 4) {
            return filtrar(List.of("regalo", "follow", "share", "likes", "repetir", "cortar"), args[3]);
        }
        if (args.length == 4 && (args[1].equalsIgnoreCase("cuenta") || args[1].equalsIgnoreCase("estado"))) {
            return filtrar(jugadores(), args[3]);
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
