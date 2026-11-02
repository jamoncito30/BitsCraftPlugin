# Qué se probó

## 0.2.0 (2026-11-02): respuestas de LONIUX

- JUnit: 27 pruebas (2 nuevas de `rompe-bloques`).
- `scripts/probar-catalogo.py`: **37 de 37** (water drop sin balde, warden a los 3.000 bits, panda en
  las subs de 25 y en el canje «Panda», canje «Bicho raro»).
- `scripts/probar-catalogo.py zonas`: creepers dentro de la región sin dueño → 0 bloques; fuera → 85;
  TNT con `rompe-bloques: streamer` en la región de LONIUX → 93; en la región sin dueño → 0
  (las dos regiones con explosiones permitidas en WorldGuard, para que decida el plugin).
- Encontrado: WorldGuard frena las explosiones en cualquier región si no tiene `tnt`/`creeper-explosion`/
  `other-explosion allow`, y GriefPrevention en los claims sin `/claimexplosions`. Con `streamer(s)`, el
  dueño de la zona tiene que permitirlas.

## 0.1.0 (2026-11-02)

Servidor de prueba: Paper 26.3 build 237 (SHA-256 `57d8774b…a56e`), WorldGuard 7.0.20,
WorldEdit 7.4.6, GriefPrevention 16.18.7, Java 25.0.4. Clientes vanilla 26.3 «LONIUX» y «LionGGZ».

### JUnit (25 pruebas, `scripts/compilar.sh`)

- **Catálogo real de `config.yml`:** escalones de bits (9 → nada, 10, 49, 50, 350, 599, 3000, 4999,
  9999, 50000), subs y regalos (1, 7, 10, 100), recompensas sin importar mayúsculas ni espacios,
  raid chica sin acción, horda de raid escalada (5 → 2, 37 → 3, 1000 → 15), enfriamiento de follows,
  topes que dispara cada entrada, ritmo por defecto y un «aleatorio» sin opciones marcado como error.
- **Fila:** 2 s entre acciones, tope por minuto, tope de TNT que hace esperar, pausa, gracia, fila
  llena que descarta el más viejo, descarte de lo de más de 24 h, devolver al principio.
- **Varios:** repetidos de EventSub, placeholders que no dejan meter comandos, traducción de los
  7 tipos de EventSub (incluida la sub de regalo que no se cuenta dos veces), tokens en disco con
  permisos solo para el dueño (ACL en Windows, 600 en Linux), filas guardadas y leídas.
- **Twitch falso** (HTTP + WebSocket en 127.0.0.1): Device Code Grant completo (pendiente → aceptado
  → validado), renovar (los dos tokens cambian, un refresh malo pide re-vincular), 401 al validar,
  código inválido; EventSub: suscripción a los 7 eventos con el session_id, 401 → renueva y reintenta,
  evento repetido descartado, `session_reconnect` sin volver a suscribirse, reconexión sola si se
  corta, y que ningún mensaje de log tenga tokens.

### En el servidor

- `scripts/probar-catalogo.py`: **35 de 35** entradas del catálogo hacen lo esperado (cantidad y tipo
  de mobs, water drop con balde, tp-azar a ~1.000 bloques, efectos, panes, antorchas quitadas,
  mascotas, wither, dragón, nuke de 100 TNT, raid chica sin horda, recompensa desconocida sin acción).
- `scripts/probar-catalogo.py zonas`: 3 creepers dentro de la región de WorldGuard → 0 bloques rotos;
  fuera → 91 bloques rotos.
- EventSub falso (`scripts/twitch-mock.py`) con dos streamers vinculados con `/twitch mock`
  (LONIUX = 42, LionGGZ = 77): los 7 eventos suscritos por cada uno; 600 bits al 42 → 3 creepers
  junto a LONIUX y ninguno junto a LionGGZ; 300 bits al 77 → slimes junto a LionGGZ; el mismo
  mensaje repetido no hace nada; raid de 37 al 77 → 3 zombis; canje «Saltito»; el segundo follow
  dentro de los 30 s solo sale en el chat; `session_reconnect` → las dos conexiones pasan a la nueva
  y los eventos siguen llegando.
- Fila en pausa con 3 eventos → servidor reiniciado → al entrar avisa «Tienes 3 evento(s)… en pausa»
  → `/twitch reanudar` → a los 10 s salen los 3, en orden.

### Encontrado en el camino (no es del plugin)

- GriefPrevention en modo **Creative** (lo pone solo si el servidor arranca en creativo) borra todos los
  mobs fuera de los claims, incluso los de `/summon`.
- GriefPrevention trae `BlockSurfaceCreeperExplosions: true`: los creepers no rompen la superficie en
  ningún lado. Se apagó en el servidor de prueba para probar las zonas del plugin.
- En el mundo plano aparecen slimes solos; el script de prueba apaga `spawn_mobs` y `spawn_monsters`.

### Falta

- **Vincular con Twitch de verdad** (`/twitch vincular` con una app real): necesita el Client ID de
  la app registrada en dev.twitch.tv.
- **Twitch CLI:** no se pudo bajar y ejecutar desde la sesión. Se probó con `scripts/twitch-mock.py`,
  que imita su servidor de WebSocket. Falta repetirlo con `twitch event websocket start-server`.
- Prueba real complementaria: un follow desde otra cuenta y un canje de puntos en un canal real.
