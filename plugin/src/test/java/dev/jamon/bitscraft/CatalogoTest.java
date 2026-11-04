package dev.jamon.bitscraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El catálogo real de config.yml (el de la propuesta para los creadores). */
class CatalogoTest {

    static YamlConfiguration config;
    static Catalogo catalogo;

    @BeforeAll
    static void leer() throws Exception {
        try (Reader r = new InputStreamReader(Objects.requireNonNull(
                CatalogoTest.class.getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(r);
        }
        catalogo = Catalogo.leer(config.getConfigurationSection("catalogo"));
    }

    /** Una copia del config.yml real (para pruebas que lo cambian). */
    static YamlConfiguration copiaConfig() {
        try (Reader r = new InputStreamReader(Objects.requireNonNull(
                CatalogoTest.class.getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** El catálogo real con otro tiktok.bits-por-moneda. */
    static Catalogo catalogoReal(double bitsPorMoneda) {
        return Catalogo.leer(copiaConfig().getConfigurationSection("catalogo"), bitsPorMoneda);
    }

    static Evento ev(Evento.Tipo tipo, int monto, String recompensa) {
        return new Evento("x", tipo, "1", "Pepito", monto, recompensa, null, 0);
    }

    static String nombre(Evento e) {
        Catalogo.Entrada en = catalogo.elegir(e);
        return en == null ? null : en.nombre();
    }

    @Test
    void elConfigNoTieneProblemas() {
        assertEquals(java.util.List.of(), catalogo.avisos());
        assertEquals(18 + 4 + 10 + 2, catalogo.todas().size());
    }

    @Test
    void bitsUsanElEscalonDondeCae() {
        assertNull(nombre(ev(Evento.Tipo.BITS, 9, null)));
        assertEquals("5 lepismas", nombre(ev(Evento.Tipo.BITS, 10, null)));
        assertEquals("5 lepismas", nombre(ev(Evento.Tipo.BITS, 49, null)));
        assertEquals("5 zombis", nombre(ev(Evento.Tipo.BITS, 50, null)));
        assertEquals("Zombis y slimes", nombre(ev(Evento.Tipo.BITS, 350, null)));
        assertEquals("¡Water drop!", nombre(ev(Evento.Tipo.BITS, 599, null)));
        assertEquals("¡WARDEN!", nombre(ev(Evento.Tipo.BITS, 3000, null)));
        assertEquals("¡WITHER!", nombre(ev(Evento.Tipo.BITS, 4999, null)));
        assertEquals("¡DRAGÓN DEL END!", nombre(ev(Evento.Tipo.BITS, 9999, null)));
        assertEquals("¡NUKE!", nombre(ev(Evento.Tipo.BITS, 50000, null)));
    }

    @Test
    void subsYRegalos() {
        assertEquals("Un perro", nombre(ev(Evento.Tipo.SUB, 1, null)));
        assertEquals("Perros y loro", nombre(ev(Evento.Tipo.SUB_REGALO, 7, null)));
        assertEquals("Perros, abeja y loro", nombre(ev(Evento.Tipo.SUB_REGALO, 10, null)));
        assertEquals("¡El zoológico!", nombre(ev(Evento.Tipo.SUB_REGALO, 100, null)));
    }

    @Test
    void puntosPorTituloSinImportarMayusculasNiEspacios() {
        assertEquals("Saltito", nombre(ev(Evento.Tipo.PUNTOS, 1, "  saltito ")));
        assertEquals("8 panes", nombre(ev(Evento.Tipo.PUNTOS, 1, "Dale  de COMER")));
        assertNull(nombre(ev(Evento.Tipo.PUNTOS, 1, "Hidratarse")));
        assertNull(nombre(ev(Evento.Tipo.PUNTOS, 1, null)));
    }

    @Test
    void raidChicaSoloAvisaYLaHordaEscala() {
        assertNull(catalogo.elegir(ev(Evento.Tipo.RAID, 2, null)));
        Catalogo.Entrada raid = catalogo.elegir(ev(Evento.Tipo.RAID, 37, null));
        assertNotNull(raid);
        Accion zombis = raid.acciones().get(0);
        assertEquals(3, zombis.cantidad(37));
        assertEquals(2, zombis.cantidad(5));
        assertEquals(15, zombis.cantidad(1000));
    }

    @Test
    void followTieneEnfriamiento() {
        assertNotNull(catalogo.elegir(ev(Evento.Tipo.FOLLOW, 1, null)));
        assertEquals(30_000, catalogo.enfriamientoFollowMs());
    }

    @Test
    void topesQueDisparaCadaEntrada() {
        assertEquals(Set.of("tnt"), Ejecutor.tiposConTope(catalogo.elegir(ev(Evento.Tipo.BITS, 2000, null))));
        assertEquals(Set.of("jefe"), Ejecutor.tiposConTope(catalogo.elegir(ev(Evento.Tipo.BITS, 4000, null))));
        assertEquals(Set.of("nuke"), Ejecutor.tiposConTope(catalogo.elegir(ev(Evento.Tipo.BITS, 10000, null))));
        assertEquals(Set.of(), Ejecutor.tiposConTope(catalogo.elegir(ev(Evento.Tipo.BITS, 100, null))));
    }

    @Test
    void ritmoPorDefecto() {
        Ritmo r = Ritmo.leer(config.getConfigurationSection("ritmo"));
        assertEquals(3000, r.retardoAvisoMs());
        assertEquals(2000, r.entreAccionesMs());
        assertEquals(20, r.accionesPorMinuto());
        assertEquals(3, r.topeDe("tnt"));
        assertEquals(-1, r.topeDe("mob"));
        assertEquals(50, r.colaMaxima());
        assertEquals(24 * 3_600_000L, r.colaEdadMaximaMs());
        assertTrue(r.mobsVivos() > 0);
    }

    @Test
    void aleatorioTieneOpcionesYUnAleatorioVacioEsError() {
        Catalogo.Entrada sorpresa = catalogo.elegir(ev(Evento.Tipo.PUNTOS, 1, "Mob sorpresa"));
        assertEquals(4, sorpresa.acciones().get(0).opcionesAleatorias().size());
        YamlConfiguration y = new YamlConfiguration();
        y.set("bits.10.nombre", "x");
        y.set("bits.10.acciones", java.util.List.of(java.util.Map.of("tipo", "aleatorio")));
        Catalogo c = Catalogo.leer(y);
        assertEquals(1, c.avisos().size());
    }
}
