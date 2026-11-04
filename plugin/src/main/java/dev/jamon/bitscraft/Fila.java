package dev.jamon.bitscraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * La fila de eventos de un streamer. Sale de a uno, respetando la pausa, la gracia al volver,
 * el tiempo mínimo entre acciones y los topes por minuto. Solo se usa desde el hilo principal.
 */
final class Fila {

    private final Deque<Evento> eventos = new ArrayDeque<>();
    private final Deque<Long> ultimoMinuto = new ArrayDeque<>();
    private final Map<String, Deque<Long>> ultimoMinutoPorTipo = new HashMap<>();
    private boolean pausa;
    private long graciaHasta;
    private long proximo;
    private final Map<Evento.Tipo, Long> ultimos = new java.util.EnumMap<>(Evento.Tipo.class);
    private long likes;
    private boolean cambio;

    /**
     * Agrega al final. Si la fila pasa de {@code maximo}, se descarta el más viejo y se devuelve
     * (para avisarlo en la consola); si no, null.
     */
    Evento agregar(Evento ev, int maximo) {
        eventos.addLast(ev);
        cambio = true;
        return eventos.size() > maximo ? eventos.pollFirst() : null;
    }

    /** Vuelve a poner un evento al principio (el streamer se fue antes de que se ejecutara). */
    void devolver(Evento ev) {
        eventos.addFirst(ev);
        cambio = true;
    }

    /** Saca lo que lleva más de {@code edadMaxima} ms esperando y lo devuelve. */
    List<Evento> quitarViejos(long ahora, long edadMaxima) {
        List<Evento> fuera = new ArrayList<>();
        for (Iterator<Evento> it = eventos.iterator(); it.hasNext(); ) {
            Evento ev = it.next();
            if (ahora - ev.llegada() > edadMaxima) {
                it.remove();
                fuera.add(ev);
            }
        }
        if (!fuera.isEmpty()) {
            cambio = true;
        }
        return fuera;
    }

    /**
     * El próximo evento que toca ejecutar ahora, o null si hay que esperar.
     *
     * @param tiposDe tipos con tope (tnt, rayo...) que dispararía un evento
     */
    Evento siguiente(long ahora, Ritmo ritmo, Function<Evento, Set<String>> tiposDe) {
        if (pausa || eventos.isEmpty() || ahora < graciaHasta || ahora < proximo) {
            return null;
        }
        limpiar(ultimoMinuto, ahora);
        if (ultimoMinuto.size() >= ritmo.accionesPorMinuto()) {
            return null;
        }
        Evento ev = eventos.peekFirst();
        Set<String> tipos = tiposDe.apply(ev);
        for (String t : tipos) {
            int tope = ritmo.topeDe(t);
            if (tope >= 0) {
                Deque<Long> d = ultimoMinutoPorTipo.computeIfAbsent(t, k -> new ArrayDeque<>());
                limpiar(d, ahora);
                if (d.size() >= tope) {
                    return null;
                }
            }
        }
        eventos.pollFirst();
        cambio = true;
        ultimoMinuto.addLast(ahora);
        for (String t : tipos) {
            ultimoMinutoPorTipo.computeIfAbsent(t, k -> new ArrayDeque<>()).addLast(ahora);
        }
        proximo = ahora + ritmo.entreAccionesMs();
        return ev;
    }

    private static void limpiar(Deque<Long> d, long ahora) {
        while (!d.isEmpty() && ahora - d.peekFirst() >= 60_000) {
            d.pollFirst();
        }
    }

    /** true si el follow puede hacer su efecto (y marca el enfriamiento). */
    boolean tomarFollow(long ahora, long enfriamiento) {
        return tomarEnfriamiento(Evento.Tipo.FOLLOW, ahora, enfriamiento);
    }

    /** true (y empieza a contar) si ya pasó el enfriamiento de ese tipo de evento para este streamer. */
    boolean tomarEnfriamiento(Evento.Tipo tipo, long ahora, long enfriamiento) {
        Long ultimo = ultimos.get(tipo);
        if (ultimo != null && ahora - ultimo < enfriamiento) {
            return false;
        }
        ultimos.put(tipo, ahora);
        return true;
    }

    /**
     * Suma likes de TikTok (llegan de a montones). Devuelve cuántos likes completos de {@code cada} se
     * juntaron (0 si todavía no llega a {@code cada}); el resto queda guardado para la próxima vez.
     */
    long sumarLikes(long nuevos, int cada) {
        likes += Math.max(0, nuevos);
        long grupos = likes / cada;
        likes -= grupos * cada;
        return grupos * cada;
    }

    void pausar(boolean p) {
        pausa = p;
    }

    boolean enPausa() {
        return pausa;
    }

    /** Después de volver (entrar, quitar la pausa) la fila espera un poco antes de seguir. */
    void gracia(long hasta) {
        graciaHasta = Math.max(graciaHasta, hasta);
    }

    int tamano() {
        return eventos.size();
    }

    List<Evento> eventos() {
        return List.copyOf(eventos);
    }

    void vaciar() {
        if (!eventos.isEmpty()) {
            eventos.clear();
            cambio = true;
        }
    }

    /** Si cambió desde la última vez que se preguntó (para guardarla en disco). */
    boolean tomarCambio() {
        boolean c = cambio;
        cambio = false;
        return c;
    }
}
