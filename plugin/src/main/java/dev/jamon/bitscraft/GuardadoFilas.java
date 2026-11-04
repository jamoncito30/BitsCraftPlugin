package dev.jamon.bitscraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Guarda la fila de cada streamer en filas/&lt;uuid&gt;.json, para que sobreviva a un reinicio. */
final class GuardadoFilas {

    private final Path carpeta;

    GuardadoFilas(Path carpeta) throws IOException {
        this.carpeta = carpeta;
        Files.createDirectories(carpeta);
    }

    void guardar(UUID jugador, Fila fila) throws IOException {
        Path destino = carpeta.resolve(jugador + ".json");
        if (fila.tamano() == 0 && !fila.enPausa()) {
            Files.deleteIfExists(destino);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("pausa", fila.enPausa());
        JsonArray lista = new JsonArray();
        for (Evento ev : fila.eventos()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", ev.id());
            e.addProperty("tipo", ev.tipo().name());
            e.addProperty("canal_id", ev.canalId());
            e.addProperty("usuario", ev.usuario());
            e.addProperty("monto", ev.monto());
            e.addProperty("recompensa", ev.recompensa());
            e.addProperty("mensaje", ev.mensaje());
            e.addProperty("llegada", ev.llegada());
            e.addProperty("cantidad", ev.cantidad());
            lista.add(e);
        }
        o.add("eventos", lista);
        Path tmp = carpeta.resolve(jugador + ".json.tmp");
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Las filas guardadas, con un máximo de {@code maximo} eventos cada una. */
    Map<UUID, Fila> leerTodas(int maximo, List<String> malos) {
        Map<UUID, Fila> m = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(carpeta, "*.json")) {
            for (Path p : ds) {
                String nombre = p.getFileName().toString();
                try {
                    UUID id = UUID.fromString(nombre.substring(0, nombre.length() - 5));
                    JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    Fila f = new Fila();
                    f.pausar(o.has("pausa") && o.get("pausa").getAsBoolean());
                    for (JsonElement el : o.getAsJsonArray("eventos")) {
                        JsonObject e = el.getAsJsonObject();
                        f.agregar(new Evento(texto(e, "id"), Evento.Tipo.valueOf(e.get("tipo").getAsString()),
                                texto(e, "canal_id"), texto(e, "usuario"), e.get("monto").getAsInt(),
                                texto(e, "recompensa"), texto(e, "mensaje"), e.get("llegada").getAsLong(),
                                e.has("cantidad") ? e.get("cantidad").getAsInt() : 1), maximo);
                    }
                    f.tomarCambio();
                    m.put(id, f);
                } catch (RuntimeException | IOException e) {
                    malos.add(nombre);
                }
            }
        } catch (IOException e) {
            malos.add(carpeta.toString());
        }
        return m;
    }

    private static String texto(JsonObject o, String campo) {
        return o.has(campo) && !o.get(campo).isJsonNull() ? o.get(campo).getAsString() : null;
    }
}
