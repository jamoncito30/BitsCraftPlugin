package dev.jamon.bitscraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Una acción del catálogo tal como está en config.yml: el tipo y sus opciones, sin interpretar.
 * El que la ejecuta ({@link Ejecutor}) valida los nombres de mobs, ítems y efectos contra el servidor.
 */
record Accion(String tipo, Map<String, Object> opciones, List<Accion> opcionesAleatorias) {

    /** Tipos que se cuentan aparte en ritmo.por-minuto. */
    static final List<String> TIPOS_CON_TOPE = List.of("tnt", "rayo", "nuke", "jefe");

    static Accion desde(Map<?, ?> mapa) {
        Object t = mapa.get("tipo");
        if (t == null) {
            throw new IllegalArgumentException("acción sin 'tipo'");
        }
        String tipo = t.toString().toLowerCase(Locale.ROOT).trim();
        Map<String, Object> opciones = new java.util.LinkedHashMap<>();
        List<Accion> aleatorias = List.of();
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            String clave = String.valueOf(e.getKey());
            if (clave.equals("tipo") || e.getValue() == null) {
                continue;
            }
            if (clave.equals("opciones") && e.getValue() instanceof List<?> lista) {
                List<Accion> l = new ArrayList<>();
                for (Object o : lista) {
                    if (o instanceof Map<?, ?> m) {
                        l.add(desde(m));
                    }
                }
                aleatorias = List.copyOf(l);
                continue;
            }
            opciones.put(clave, e.getValue());
        }
        if (tipo.equals("aleatorio") && aleatorias.isEmpty()) {
            throw new IllegalArgumentException("'aleatorio' sin opciones");
        }
        return new Accion(tipo, Map.copyOf(opciones), aleatorias);
    }

    String texto(String clave, String porDefecto) {
        Object v = opciones.get(clave);
        return v == null ? porDefecto : v.toString();
    }

    int entero(String clave, int porDefecto) {
        Object v = opciones.get(clave);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignorado) {
                return porDefecto;
            }
        }
        return porDefecto;
    }

    double decimal(String clave, double porDefecto) {
        Object v = opciones.get(clave);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ignorado) {
                return porDefecto;
            }
        }
        return porDefecto;
    }

    boolean si(String clave, boolean porDefecto) {
        Object v = opciones.get(clave);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? porDefecto : Boolean.parseBoolean(v.toString().trim());
    }

    /** Opción [min, max] (o un solo número). */
    int[] rango(String clave, int min, int max) {
        Object v = opciones.get(clave);
        if (v instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            return new int[]{Math.min(a.intValue(), b.intValue()), Math.max(a.intValue(), b.intValue())};
        }
        if (v instanceof Number n) {
            return new int[]{n.intValue(), n.intValue()};
        }
        return new int[]{min, max};
    }

    /**
     * Cuántas veces repetir (mobs). Con por-cada, depende del monto: 1 cada N, entre minimo y maximo.
     * Ej. raid de 37 personas con por-cada 10, minimo 2, maximo 15 → 3.
     */
    int cantidad(int monto) {
        int porCada = entero("por-cada", 0);
        if (porCada > 0) {
            int n = monto / porCada;
            n = Math.max(n, entero("minimo", 1));
            return Math.min(n, entero("maximo", 15));
        }
        return Math.max(1, entero("cantidad", 1));
    }

    /** Tipos (con tope por minuto) que dispara esta acción, mirando dentro de "aleatorio". */
    void tiposConTope(java.util.Set<String> destino) {
        if (TIPOS_CON_TOPE.contains(tipo)) {
            destino.add(tipo);
        }
        for (Accion a : opcionesAleatorias) {
            a.tiposConTope(destino);
        }
    }
}
