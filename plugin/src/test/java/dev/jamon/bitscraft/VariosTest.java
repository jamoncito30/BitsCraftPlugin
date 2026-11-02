package dev.jamon.bitscraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repetidos, placeholders, lectura de EventSub, tokens en disco y filas guardadas. */
class VariosTest {

    @Test
    void repetidosRecuerdaLosUltimos() {
        Repetidos r = new Repetidos(2);
        assertTrue(r.primeraVez("a"));
        assertFalse(r.primeraVez("a"));
        assertTrue(r.primeraVez("b"));
        assertTrue(r.primeraVez("c"));
        assertTrue(r.primeraVez("a"), "'a' ya salió de la memoria");
    }

    @Test
    void placeholdersNoDejanMeterComandos() {
        assertEquals("Pepito_123", Placeholders.limpiar("Pepito_123"));
        assertEquals("aopx", Placeholders.limpiar("a; op x"));
        assertEquals("", Placeholders.limpiar(null));
        Evento ev = new Evento("x", Evento.Tipo.PUNTOS, "1", "mal\" ; stop", 1, "x && op yo", null, 0);
        String c = Placeholders.aplicar("say {usuario} canjeó {recompensa} en {canal} para {streamer}",
                Placeholders.de("LONIUX", ev, "loniux"));
        assertEquals("say malstop canjeó xopyo en loniux para LONIUX", c);
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void traduceLosSieteEventos() {
        Evento cheer = MensajesEventSub.traducir("channel.cheer", json("""
                {"is_anonymous":false,"user_id":"1","user_login":"pepito","user_name":"Pepito",
                 "broadcaster_user_id":"42","bits":600,"message":"Cheer600 hola"}"""), "m1", 5);
        assertEquals(new Evento("m1", Evento.Tipo.BITS, "42", "Pepito", 600, null, "Cheer600 hola", 5), cheer);

        Evento anon = MensajesEventSub.traducir("channel.cheer", json("""
                {"is_anonymous":true,"user_id":null,"user_login":null,"user_name":null,
                 "broadcaster_user_id":"42","bits":100,"message":""}"""), "m2", 5);
        assertNull(anon.usuario());

        Evento canje = MensajesEventSub.traducir("channel.channel_points_custom_reward_redemption.add", json("""
                {"broadcaster_user_id":"42","user_name":"Ana","user_input":"",
                 "reward":{"id":"r","title":"Saltito","cost":1000}}"""), "m3", 5);
        assertEquals(Evento.Tipo.PUNTOS, canje.tipo());
        assertEquals("Saltito", canje.recompensa());

        assertNull(MensajesEventSub.traducir("channel.subscribe", json("""
                {"broadcaster_user_id":"42","user_name":"Ana","tier":"1000","is_gift":true}"""), "m4", 5),
                "las subs que vienen de un regalo se cuentan en el regalo");
        assertEquals(1, MensajesEventSub.traducir("channel.subscribe", json("""
                {"broadcaster_user_id":"42","user_name":"Ana","tier":"1000","is_gift":false}"""), "m5", 5).monto());
        assertEquals(Evento.Tipo.SUB, MensajesEventSub.traducir("channel.subscription.message", json("""
                {"broadcaster_user_id":"42","user_name":"Ana","tier":"1000","message":{"text":"hola"},
                 "cumulative_months":15}"""), "m6", 5).tipo());

        Evento regalo = MensajesEventSub.traducir("channel.subscription.gift", json("""
                {"broadcaster_user_id":"42","user_name":"Ana","total":7,"tier":"1000","is_anonymous":false}"""), "m7", 5);
        assertEquals(Evento.Tipo.SUB_REGALO, regalo.tipo());
        assertEquals(7, regalo.monto());

        Evento raid = MensajesEventSub.traducir("channel.raid", json("""
                {"from_broadcaster_user_id":"9","from_broadcaster_user_name":"LionGGZ",
                 "to_broadcaster_user_id":"42","to_broadcaster_user_name":"LONIUX","viewers":37}"""), "m8", 5);
        assertEquals(new Evento("m8", Evento.Tipo.RAID, "42", "LionGGZ", 37, null, null, 5), raid);

        Evento follow = MensajesEventSub.traducir("channel.follow", json("""
                {"user_id":"3","user_name":"Nuevo","broadcaster_user_id":"42","followed_at":"x"}"""), "m9", 5);
        assertEquals(Evento.Tipo.FOLLOW, follow.tipo());

        assertNull(MensajesEventSub.traducir("channel.ban", new JsonObject(), "m10", 5));
        assertEquals(7, MensajesEventSub.suscripciones("42").size());
        assertEquals(Map.of("to_broadcaster_user_id", "42"), MensajesEventSub.suscripciones("42").get(5).condicion());
    }

    @Test
    void tokensQuedanSoloParaElDueno(@TempDir Path dir) throws Exception {
        Path carpeta = dir.resolve("tokens");
        AlmacenTokens a = new AlmacenTokens(carpeta);
        UUID id = UUID.randomUUID();
        Token t = new Token("acceso-secreto", "renovacion-secreta", 123L, "42", "loniux", false);
        a.guardar(id, t);
        a.guardar(UUID.randomUUID(), new Token("m", "", 1, "9", "mock", true));
        List<String> malos = new ArrayList<>();
        Map<UUID, Token> todos = a.todos(malos);
        assertEquals(Map.of(id, t), todos, "el mock no se guarda");
        assertEquals(List.of(), malos);

        Path archivo = carpeta.resolve(id + ".json");
        PosixFileAttributeView posix = Files.getFileAttributeView(archivo, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(archivo, AclFileAttributeView.class);
            assertEquals(1, acl.getAcl().size());
            assertEquals(Files.getOwner(archivo), acl.getAcl().get(0).principal());
        }
        assertFalse(t.toString().contains("secret"), t.toString());
        a.borrar(id);
        assertEquals(Map.of(), a.todos(malos));
    }

    @Test
    void filasSobrevivenAlReinicio(@TempDir Path dir) throws Exception {
        GuardadoFilas g = new GuardadoFilas(dir);
        UUID id = UUID.randomUUID();
        Fila f = new Fila();
        Evento a = new Evento("a", Evento.Tipo.BITS, "42", null, 500, null, "hola", 10);
        Evento b = new Evento("b", Evento.Tipo.PUNTOS, "42", "Ana", 1, "Saltito", null, 11);
        f.agregar(a, 50);
        f.agregar(b, 50);
        f.pausar(true);
        g.guardar(id, f);
        List<String> malos = new ArrayList<>();
        Fila leida = g.leerTodas(50, malos).get(id);
        assertEquals(List.of(a, b), leida.eventos());
        assertTrue(leida.enPausa());
        leida.vaciar();
        leida.pausar(false);
        g.guardar(id, leida);
        assertEquals(Map.of(), g.leerTodas(50, malos), "una fila vacía y sin pausa no deja archivo");
        assertEquals(List.of(), malos);
    }
}
