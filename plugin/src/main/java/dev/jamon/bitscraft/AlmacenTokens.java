package dev.jamon.bitscraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guarda los tokens de cada streamer en tokens/&lt;uuid&gt;.json, legibles solo por el usuario que corre
 * el servidor (permisos 600/700 en Linux; en Windows, una ACL con solo el dueño).
 * Los vínculos de prueba (mock) no se guardan.
 */
final class AlmacenTokens {

    private final Path carpeta;

    AlmacenTokens(Path carpeta) throws IOException {
        this.carpeta = carpeta;
        Files.createDirectories(carpeta);
        restringir(carpeta, true);
    }

    void guardar(UUID jugador, Token t) throws IOException {
        if (t.mock()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("acceso", t.acceso());
        o.addProperty("renovacion", t.renovacion());
        o.addProperty("expira", t.expira());
        o.addProperty("usuario_id", t.usuarioId());
        o.addProperty("login", t.login());
        Path tmp = carpeta.resolve(jugador + ".json.tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        restringir(tmp, false);
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        Path destino = carpeta.resolve(jugador + ".json");
        try {
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
        }
        restringir(destino, false);
    }

    /** Todos los tokens guardados. Los archivos que no se pueden leer se saltan (y se informan en {@code malos}). */
    Map<UUID, Token> todos(List<String> malos) {
        Map<UUID, Token> m = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(carpeta, "*.json")) {
            for (Path p : ds) {
                String nombre = p.getFileName().toString();
                try {
                    UUID id = UUID.fromString(nombre.substring(0, nombre.length() - 5));
                    JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    m.put(id, new Token(o.get("acceso").getAsString(), o.get("renovacion").getAsString(),
                            o.get("expira").getAsLong(), o.get("usuario_id").getAsString(),
                            o.get("login").getAsString(), false));
                } catch (RuntimeException | IOException e) {
                    malos.add(nombre);
                }
            }
        } catch (IOException e) {
            malos.add(carpeta.toString());
        }
        return m;
    }

    void borrar(UUID jugador) throws IOException {
        Files.deleteIfExists(carpeta.resolve(jugador + ".json"));
    }

    /** Deja el archivo o la carpeta solo para el dueño. Si el sistema no lo permite, no hace nada. */
    static void restringir(Path p, boolean carpeta) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(carpeta ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal dueno = Files.getOwner(p);
            AclEntry.Builder b = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(dueno)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (carpeta) {
                b.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(b.build()));
        }
    }
}
