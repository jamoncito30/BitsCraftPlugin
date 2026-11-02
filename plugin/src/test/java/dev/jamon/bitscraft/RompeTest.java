package dev.jamon.bitscraft;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(Rompe.NO, Rompe.desde(null, Rompe.NO));
        assertEquals(Rompe.FUERA_DE_ZONAS, Rompe.desde("cualquier cosa", Rompe.FUERA_DE_ZONAS));
    }

    @Test
    void duenosPermitidosSegunElModo() {
        Set<UUID> vinculados = Set.of(LONIUX, LION);
        assertNull(Rompe.NO.duenosPermitidos(LONIUX, vinculados), "no rompe nada");
        assertEquals(Set.of(), Rompe.FUERA_DE_ZONAS.duenosPermitidos(LONIUX, vinculados), "ninguna zona");
        assertEquals(Set.of(LONIUX), Rompe.STREAMER.duenosPermitidos(LONIUX, vinculados));
        assertEquals(Set.of(LONIUX, LION), Rompe.STREAMERS.duenosPermitidos(LONIUX, vinculados));
        assertEquals(Set.of(LONIUX), Rompe.STREAMERS.duenosPermitidos(LONIUX, Set.of()),
                "el streamer afectado cuenta aunque no esté vinculado (pruebas)");
    }
}
