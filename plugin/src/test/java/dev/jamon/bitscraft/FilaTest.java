package dev.jamon.bitscraft;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilaTest {

    static final Ritmo RITMO = new Ritmo(3000, 2000, 5, Map.of("tnt", 2), 30, 300_000, 600_000, 4, 86_400_000L, 10_000);
    static final Function<Evento, Set<String>> SIN_TOPES = e -> Set.of();

    static Evento ev(String id, long llegada) {
        return new Evento(id, Evento.Tipo.BITS, "1", "u", 100, null, null, llegada);
    }

    @Test
    void saleDeAUnoConDosSegundosEntreMedio() {
        Fila f = new Fila();
        f.agregar(ev("a", 0), 10);
        f.agregar(ev("b", 0), 10);
        assertEquals("a", f.siguiente(1000, RITMO, SIN_TOPES).id());
        assertNull(f.siguiente(2999, RITMO, SIN_TOPES));
        assertEquals("b", f.siguiente(3000, RITMO, SIN_TOPES).id());
        assertNull(f.siguiente(10_000, RITMO, SIN_TOPES));
    }

    @Test
    void topePorMinuto() {
        Fila f = new Fila();
        for (int i = 0; i < 7; i++) {
            f.agregar(ev("e" + i, 0), 10);
        }
        long t = 0;
        int salieron = 0;
        for (; t < 60_000; t += 500) {
            if (f.siguiente(t, RITMO, SIN_TOPES) != null) {
                salieron++;
            }
        }
        assertEquals(5, salieron, "como mucho 5 por minuto");
        assertNotNull(f.siguiente(60_000, RITMO, SIN_TOPES), "pasado el minuto sigue");
    }

    @Test
    void topePorTipoEspera() {
        Fila f = new Fila();
        for (int i = 0; i < 3; i++) {
            f.agregar(ev("t" + i, 0), 10);
        }
        Function<Evento, Set<String>> tnt = e -> Set.of("tnt");
        assertNotNull(f.siguiente(0, RITMO, tnt));
        assertNotNull(f.siguiente(2000, RITMO, tnt));
        assertNull(f.siguiente(4000, RITMO, tnt), "tercera TNT en el mismo minuto espera");
        assertEquals(1, f.tamano());
        assertNotNull(f.siguiente(60_000, RITMO, tnt));
    }

    @Test
    void pausaYGracia() {
        Fila f = new Fila();
        f.agregar(ev("a", 0), 10);
        f.pausar(true);
        assertNull(f.siguiente(0, RITMO, SIN_TOPES));
        f.pausar(false);
        f.gracia(10_000);
        assertNull(f.siguiente(9_999, RITMO, SIN_TOPES));
        assertNotNull(f.siguiente(10_000, RITMO, SIN_TOPES));
    }

    @Test
    void filaLlenaDescartaElMasViejo() {
        Fila f = new Fila();
        for (int i = 0; i < 4; i++) {
            assertNull(f.agregar(ev("e" + i, i), 4));
        }
        Evento fuera = f.agregar(ev("e4", 4), 4);
        assertEquals("e0", fuera.id());
        assertEquals(4, f.tamano());
    }

    @Test
    void quitaLoMuyViejoYDevolverVuelveAlPrincipio() {
        Fila f = new Fila();
        f.agregar(ev("viejo", 0), 10);
        f.agregar(ev("nuevo", 90_000_000L), 10);
        assertEquals(1, f.quitarViejos(90_000_001L, 86_400_000L).size());
        Evento e = f.siguiente(90_000_001L, RITMO, SIN_TOPES);
        assertEquals("nuevo", e.id());
        f.devolver(e);
        assertEquals(1, f.tamano());
        assertTrue(f.tomarCambio());
        assertSame(false, f.tomarCambio());
    }

    @Test
    void enfriamientoDeFollows() {
        Fila f = new Fila();
        assertTrue(f.tomarFollow(0, 30_000));
        assertEquals(false, f.tomarFollow(29_999, 30_000));
        assertTrue(f.tomarFollow(30_000, 30_000));
    }
}
