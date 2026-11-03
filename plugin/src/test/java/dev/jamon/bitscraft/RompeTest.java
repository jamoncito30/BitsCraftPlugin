package dev.jamon.bitscraft;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Los modos de rompe-bloques y de quién son las zonas que se pueden romper. */
class RompeTest {

    static final UUID LONIUX = UUID.randomUUID();
    static final UUID LION = UUID.randomUUID();

    @Test
    void leeLoQueSeEscribeEnElConfig() {
        assertEquals(Rompe.NO, Rompe.desde(false, Rompe.FUERA_DE_ZONAS));
        assertEquals(Rompe.FUERA_DE_ZONAS, Rompe.desde(true, Rompe.NO));
        assertEquals(Rompe.STREAMER, Rompe.desde("streamer", Rompe.NO));
        assertEquals(Rompe.STREAMERS, Rompe.desde(" Streamers ", Rompe.NO));
        assertEquals(Rompe.SOLO_STREAMER, Rompe.desde("solo-streamer", Rompe.NO));
        assertEquals(Rompe.NO, Rompe.desde(null, Rompe.NO));
        assertEquals(Rompe.FUERA_DE_ZONAS, Rompe.desde("cualquier cosa", Rompe.FUERA_DE_ZONAS));
    }

    /** Una zona de LONIUX, una de LionGGZ, una sin dueño (admin) y el campo abierto. */
    enum Lugar { BASE_LONIUX, BASE_LION, SIN_DUENO, AFUERA }

    static boolean permite(Rompe r, Lugar l) {
        UUID dueno = switch (l) {
            case BASE_LONIUX -> LONIUX;
            case BASE_LION -> LION;
            default -> null;
        };
        boolean enZona = l != Lugar.AFUERA;
        return r.permite(enZona, permitidos -> enZona && (dueno == null || !permitidos.contains(dueno)),
                LONIUX, Set.of(LONIUX, LION));
    }

    @Test
    void dondeRompeCadaModoCuandoLeDonanALoniux() {
        record Caso(Rompe modo, boolean baseLoniux, boolean baseLion, boolean sinDueno, boolean afuera) {
        }
        for (Caso c : java.util.List.of(
                new Caso(Rompe.NO, false, false, false, false),
                new Caso(Rompe.FUERA_DE_ZONAS, false, false, false, true),
                new Caso(Rompe.STREAMER, true, false, false, true),
                new Caso(Rompe.STREAMERS, true, true, false, true),
                new Caso(Rompe.SOLO_STREAMER, true, false, false, false))) {
            assertEquals(c.baseLoniux(), permite(c.modo(), Lugar.BASE_LONIUX), c.modo() + " en la base de LONIUX");
            assertEquals(c.baseLion(), permite(c.modo(), Lugar.BASE_LION), c.modo() + " en la base de LionGGZ");
            assertEquals(c.sinDueno(), permite(c.modo(), Lugar.SIN_DUENO), c.modo() + " en una zona sin dueño");
            assertEquals(c.afuera(), permite(c.modo(), Lugar.AFUERA), c.modo() + " afuera");
        }
        assertEquals(false, Rompe.SOLO_STREAMER.permite(true, p -> false, null, Set.of()), "sin streamer no rompe");
    }
}
