package dev.jamon.bitscraft;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * La conexión a TikTok LIVE de un streamer. No sabe nada de la librería: habla con un
 * {@link TikTok.Conector}. Si no está en vivo, vuelve a mirar cada tanto; si se corta o falla,
 * reintenta con espera creciente (10 s → 5 min). Los regalos se convierten en {@link Evento}.
 */
final class ConexionTikTok {

    static final long ESPERA_INICIAL_MS = 10_000;
    static final long ESPERA_MAXIMA_MS = 300_000;

    /** A dónde van los eventos y los problemas. Se llama desde hilos de TikTok, no del servidor. */
    interface Salida {
        void evento(Evento ev);

        void info(String mensaje);

        /** La librería no carga o se rompió por dentro (LinkageError): hay que apagar TikTok. */
        void libreriaRota(Throwable t);
    }

    private final String usuario;
    private final TikTok.Conector conector;
    private final ScheduledExecutorService hilos;
    private final LongSupplier reloj;
    private final long revisarMs;
    private final Salida salida;
    private final Repetidos repetidos = new Repetidos(1000);

    private int generacion;
    private boolean cerrada;
    private ScheduledFuture<?> proximo;
    private TikTok.Sesion sesion;
    private String estado = "iniciando";
    private long espera = ESPERA_INICIAL_MS;
    private long proximoIntento;

    ConexionTikTok(String usuario, TikTok.Conector conector, ScheduledExecutorService hilos, LongSupplier reloj,
                   long revisarMs, Salida salida) {
        this.usuario = usuario;
        this.conector = conector;
        this.hilos = hilos;
        this.reloj = reloj;
        this.revisarMs = revisarMs;
        this.salida = salida;
    }

    String usuario() {
        return usuario;
    }

    synchronized void iniciar() {
        programar(0, "conectando");
    }

    synchronized void cerrar() {
        cerrada = true;
        generacion++;
        if (proximo != null) {
            proximo.cancel(false);
        }
        cerrarSesion();
        estado = "apagado";
    }

    /** Para /bits estado. */
    synchronized String estado() {
        if (proximoIntento > 0 && !cerrada) {
            long faltan = Math.max(0, proximoIntento - reloj.getAsLong()) / 1000;
            return estado + " (de nuevo en " + faltan + " s)";
        }
        return estado;
    }

    synchronized boolean conectada() {
        return sesion != null && estado.startsWith("conectado");
    }

    private void programar(long ms, String nuevoEstado) {
        if (cerrada) {
            return;
        }
        estado = nuevoEstado;
        int gen = ++generacion;
        proximoIntento = ms == 0 ? 0 : reloj.getAsLong() + ms;
        proximo = hilos.schedule(() -> intentar(gen), ms, TimeUnit.MILLISECONDS);
    }

    private void cerrarSesion() {
        TikTok.Sesion s = sesion;
        sesion = null;
        if (s != null) {
            try {
                s.cerrar();
            } catch (RuntimeException | LinkageError ignorado) {
                // Ya está cortada o la librería falló al cerrar: no importa.
            }
        }
    }

    /** Un intento de conexión (en un hilo de TikTok). */
    private void intentar(int gen) {
        Intento intento;
        synchronized (this) {
            if (cerrada || gen != generacion) {
                return;
            }
            cerrarSesion();
            estado = "conectando";
            proximoIntento = 0;
            intento = new Intento(gen);
        }
        TikTok.Sesion s;
        try {
            s = conector.conectar(usuario, intento);
        } catch (TikTok.FalloTikTok f) {
            fallo(gen, f.tipo, f.getMessage());
            return;
        } catch (LinkageError e) {
            synchronized (this) {
                estado = "apagado: la librería de TikTok no funciona";
            }
            salida.libreriaRota(e);
            return;
        } catch (RuntimeException e) {
            fallo(gen, TikTok.Fallo.OTRO, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        synchronized (this) {
            if (cerrada || gen != generacion) {
                cerrarSesionAparte(s);
                return;
            }
            if (intento.cortado) {
                cerrarSesionAparte(s);
                fallo(gen, TikTok.Fallo.OTRO, intento.motivo);
                return;
            }
            sesion = s;
            conectado();
        }
    }

    private static void cerrarSesionAparte(TikTok.Sesion s) {
        if (s != null) {
            try {
                s.cerrar();
            } catch (RuntimeException | LinkageError ignorado) {
                // Nada que hacer.
            }
        }
    }

    private synchronized void conectado() {
        if (!estado.startsWith("conectado")) {
            estado = "conectado a @" + usuario + " (en vivo)";
            espera = ESPERA_INICIAL_MS;
            salida.info("TikTok: conectado al directo de @" + usuario);
        }
    }

    private synchronized void fallo(int gen, TikTok.Fallo tipo, String motivo) {
        if (cerrada || gen != generacion) {
            return;
        }
        switch (tipo) {
            case FUERA_DE_VIVO -> programar(revisarMs, "esperando que empiece el directo de @" + usuario);
            case NO_EXISTE -> {
                salida.info("TikTok: no existe la cuenta @" + usuario + " (revisa el nombre)");
                programar(Math.max(revisarMs, ESPERA_MAXIMA_MS), "no existe la cuenta @" + usuario);
            }
            case FIRMA -> {
                salida.info("TikTok: error de firma con Euler Stream para @" + usuario + ": " + motivo);
                programar(siguienteEspera(), "error de firma (¿clave mala o límite de Euler?)");
            }
            case OTRO -> {
                salida.info("TikTok: no se pudo conectar a @" + usuario + ": " + motivo);
                programar(siguienteEspera(), "reconectando");
            }
        }
    }

    private long siguienteEspera() {
        long e = espera;
        espera = Math.min(ESPERA_MAXIMA_MS, espera * 2);
        return e;
    }

    // ---- lo que llega de un intento ----

    private void emitir(int gen, String id, Evento.Tipo tipo, String fan, int monto, String regalo, int cantidad) {
        synchronized (this) {
            if (cerrada || gen != generacion) {
                return;
            }
        }
        String clave = id == null || id.isBlank() || id.equals("0") ? null : "tt-" + usuario + "-" + id;
        if (clave != null && !repetidos.primeraVez(clave)) {
            return;
        }
        salida.evento(new Evento(clave == null ? "tt-" + System.nanoTime() : clave, tipo, usuario, fan, monto,
                regalo, null, reloj.getAsLong(), cantidad));
    }

    /** El receptor de un intento: lo de intentos viejos se ignora. */
    private final class Intento implements TikTok.Receptor {
        private final int gen;
        volatile boolean cortado;
        volatile String motivo = "se cortó";

        Intento(int gen) {
            this.gen = gen;
        }

        @Override
        public void conectado() {
            synchronized (ConexionTikTok.this) {
                if (!cerrada && gen == generacion && sesion != null) {
                    ConexionTikTok.this.conectado();
                }
            }
        }

        @Override
        public void regalo(String id, String fan, String nombre, int monedasCadaUno, int cantidad) {
            int n = Math.max(1, cantidad);
            long total = (long) Math.max(0, monedasCadaUno) * n;
            emitir(gen, id, Evento.Tipo.TIKTOK_REGALO, fan, (int) Math.min(Integer.MAX_VALUE, total), nombre, n);
        }

        @Override
        public void follow(String id, String fan) {
            emitir(gen, id, Evento.Tipo.TIKTOK_FOLLOW, fan, 1, null, 1);
        }

        @Override
        public void share(String id, String fan) {
            emitir(gen, id, Evento.Tipo.TIKTOK_SHARE, fan, 1, null, 1);
        }

        @Override
        public void likes(String id, String fan, int likes) {
            if (likes > 0) {
                emitir(gen, id, Evento.Tipo.TIKTOK_LIKES, fan, likes, null, 1);
            }
        }

        @Override
        public void desconectado(String porQue) {
            synchronized (ConexionTikTok.this) {
                if (cerrada || gen != generacion) {
                    return;
                }
                if (sesion == null) {
                    // Todavía conectando: el intento terminó mal (la librería avisa antes de lanzar).
                    cortado = true;
                    motivo = porQue == null ? "se cortó" : porQue;
                    return;
                }
                cerrarSesion();
                salida.info("TikTok: se cortó el directo de @" + usuario + (porQue == null ? "" : " (" + porQue + ")"));
                programar(ESPERA_INICIAL_MS, "se cortó; reconectando");
            }
        }
    }
}
