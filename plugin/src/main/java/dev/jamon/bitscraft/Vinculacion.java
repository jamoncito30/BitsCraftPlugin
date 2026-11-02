package dev.jamon.bitscraft;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * El Device Code Grant de un streamer: pide el código, se lo muestra, y pregunta cada pocos segundos
 * si ya aceptó en twitch.tv/activate. Cuando acepta, valida el token para saber de qué canal es.
 */
final class Vinculacion {

    interface Salida {
        /** Mostrar el enlace y el código al streamer. */
        void codigo(ClienteTwitch.Codigo codigo);

        /** Aceptó: el token ya trae el id y el login del canal. */
        void listo(Token token);

        void fallo(String motivo);
    }

    private final ClienteTwitch cliente;
    private final ScheduledExecutorService hilos;
    private final LongSupplier reloj;
    private final Salida salida;
    private volatile boolean cancelada;
    private volatile ScheduledFuture<?> siguiente;
    private long vence;

    Vinculacion(ClienteTwitch cliente, ScheduledExecutorService hilos, LongSupplier reloj, Salida salida) {
        this.cliente = cliente;
        this.hilos = hilos;
        this.reloj = reloj;
        this.salida = salida;
    }

    void iniciar() {
        hilos.execute(() -> {
            try {
                ClienteTwitch.Codigo c = cliente.pedirCodigo();
                vence = reloj.getAsLong() + c.expiraSeg() * 1000L;
                salida.codigo(c);
                programar(c, Math.max(1, c.intervaloSeg()));
            } catch (IOException e) {
                salida.fallo(e.getMessage());
            }
        });
    }

    void cancelar() {
        cancelada = true;
        ScheduledFuture<?> s = siguiente;
        if (s != null) {
            s.cancel(false);
        }
    }

    private void programar(ClienteTwitch.Codigo c, int intervalo) {
        if (cancelada) {
            return;
        }
        siguiente = hilos.schedule(() -> preguntar(c, intervalo), intervalo, TimeUnit.SECONDS);
    }

    private void preguntar(ClienteTwitch.Codigo c, int intervalo) {
        if (cancelada) {
            return;
        }
        if (reloj.getAsLong() > vence) {
            salida.fallo("el código venció sin que lo aceptaras");
            return;
        }
        try {
            ClienteTwitch.Canje r = cliente.canjear(c);
            switch (r.estado()) {
                case PENDIENTE -> programar(c, intervalo);
                case LENTO -> programar(c, intervalo + 5);
                case FALLO -> salida.fallo(r.detalle());
                case LISTO -> {
                    ClienteTwitch.Validacion v = cliente.validar(r.token().acceso());
                    if (v == null) {
                        salida.fallo("Twitch entregó un token que no sirve");
                        return;
                    }
                    salida.listo(new Token(r.token().acceso(), r.token().renovacion(), r.token().expira(),
                            v.usuarioId(), v.login(), false));
                }
            }
        } catch (IOException e) {
            // Un corte de red no debería botar la vinculación: se reintenta.
            programar(c, intervalo + 5);
        }
    }
}
