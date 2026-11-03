# BitsCraftPlugin — plan de implementación

## Contexto

LONIUX y LionGGZ arman un servidor de contenido y pidieron un plugin que conecte sus canales de
Twitch con la partida (ej.: 100 bits en el canal de LONIUX → creeper junto a LONIUX). La etapa 0
ya está cerrada en `C:\Users\jorge\IdeaProjects\jamon-twitch\PLAN.md` (eventos, varios streamers,
límites "moderado", cola guardada, catálogo propuesto). Este plan dice **cómo** se construye, para
aprobarlo antes de escribir código. No se despliega en el Servidor Jamón.

Terminado = un streamer vincula su canal en el juego, los eventos de la Twitch CLI disparan las
acciones configuradas, y hay README en español para los creadores.

## Base que se reutiliza (de jamon-mercado y jamon-elytras)

- `jamon-mercado/scripts/compilar.sh`: `javac --release 25 -Werror` + JUnit standalone 1.13.4
  (bajado de Maven Central con verificación SHA-1) + `jar`. Se copia y adapta (nombre, classpath).
- `jamon-mercado/scripts/rcon.py` y `arrancar-prueba.sh`: se copian tal cual (cambia el puerto).
- `jamon-elytras/scripts/cliente-prueba.py`: cliente vanilla 26.3 sin cuenta contra el servidor
  offline, con carpeta propia por nombre → dos clientes, "LONIUX" y "LionGGZ".
- Jars de WorldGuard 7.0.20, WorldEdit 7.4.6 y GriefPrevention 16.18.7 de
  `jamon-mercado/servidor-prueba/plugins/` (local, ya revisados) para compilar y probar.
- Mismo estilo: paquete `dev.jamon.bitscraft`, nombres en español, `plugin.yml` con `${version}`,
  `VERSION`, `.gitignore` con `servidor-prueba/`, `build/`, `herramientas/`, `capturas/`,
  `_copias-otras-sesiones/`.

Herramientas locales: JDK 25.0.4, Python, Node 24. **Falta la Twitch CLI.**

## Estructura

```
jamon-twitch/
  PLAN.md  README.md  VERSION  .gitignore
  plugin/src/main/java/dev/jamon/bitscraft/
    BitsCraftPlugin.java            arranque, carga de config, registro de comandos y listeners
    ComandoTwitch.java          /twitch vincular|desvincular|estado|pausa|reanudar|probar|vaciar|recargar
    Config.java                 lee config.yml → registros inmutables (URLs, topes, textos, catálogo)
    auth/DeviceCode.java        POST /oauth2/device, sondeo de /oauth2/token (authorization_pending, slow_down, expired)
    auth/Tokens.java            guardar/leer tokens/<uuid>.json, permisos solo-dueño (POSIX 600 o ACL en Windows)
    auth/Renovador.java         refresh (cliente público, sin secret), /oauth2/validate cada hora, revoke
    eventsub/Conexion.java      WebSocket (java.net.http) por streamer: welcome, keepalive, reconnect, revocation
    eventsub/Suscripciones.java POST /helix/eventsub/subscriptions de los 7 tipos dentro de los 10 s del welcome
    eventsub/Mensajes.java      JSON (Gson de Paper) → EventoTwitch {tipo, canal, usuario, monto, recompensa, nivel}
    eventsub/Repetidos.java     descarta message_id ya vistos (últimos 500)
    catalogo/Catalogo.java      evento → lista de acciones (escalón más alto ≤ monto, repetir con tope)
    cola/ColaStreamer.java      cola por streamer: ritmo mínimo, topes por minuto y por tipo, pausa, gracia
    cola/Guardado.java          cola/<uuid>.json, tope 50, descarta > 24 h
    acciones/*.java             Mob, Efecto, Dar, Quitar, Rayo, Tnt, Sonido, Particulas, Fuegos, Comando, Aleatorio, NochePropia
    acciones/LugarSeguro.java   punto de aire con suelo a 3–6 bloques, sin lava ni vacío
    acciones/Placeholders.java  {streamer} {canal} {usuario} {monto} {recompensa} {nivel}, saneo a [A-Za-z0-9_]
    proteccion/Protecciones.java     interfaz + EntityExplodeEvent/BlockIgniteEvent/EntityDeathEvent de lo marcado
    proteccion/ConWorldGuard.java    solo se carga si WorldGuard está (región ≠ __global__)
    proteccion/ConGriefPrevention.java solo se carga si GP está (getClaimAt)
    Avisos.java                 título al streamer + línea de chat para todos + sonido, retardo configurable
  plugin/src/main/resources/{plugin.yml, config.yml}
  plugin/src/test/java/dev/jamon/bitscraft/...
  scripts/ compilar.sh  armar-servidor-prueba.sh  arrancar-prueba.sh  rcon.py  cliente-prueba.py  twitch-mock.sh
  docs/ E1.md … E5.md (qué se hizo y cómo se probó en cada etapa)
```

Reglas transversales:
- Todo lo de Twitch (HTTP, WebSocket, refresco) corre fuera del hilo principal; las acciones
  siempre se ejecutan en el hilo principal con el scheduler de Bukkit.
- Los mobs, TNT y rayos del plugin llevan una marca (PersistentDataContainer) con el UUID del
  streamer: así se cuentan los mobs vivos, se quitan drops/XP, se borran a los 5 min y se decide
  si una explosión rompe bloques.
- Tokens: nunca en logs, chat, excepciones ni archivos de prueba. Los `toString()` de los
  registros con tokens los ocultan; los errores HTTP se registran sin cuerpo de petición.
- URLs de Twitch configurables (`twitch.url-id`, `url-api`, `url-eventsub`). `/twitch mock
  <jugador> <user-id>` crea un vínculo falso **solo** si esas URLs apuntan a 127.0.0.1.

Cambio al catálogo de PLAN.md: "noche de golpe" (2.500 bits) cambia la hora del mundo para todos,
lo que choca con "solo al streamer". Se reemplaza por `Player#setPlayerTime` (noche solo para él,
60 s). Se corrige en PLAN.md §4 al empezar la E1.

## Etapas

Cada una termina con su prueba, su `docs/En.md` y un commit (git se inicia en la E1).

**E1 — Plugin sin Twitch.** Esqueleto, `config.yml` con todo el catálogo de la §4, todas las
acciones, cola con topes y pausa, avisos, protecciones de explosión/drops, `/twitch probar`.
- Servidor de prueba: **Paper 26.3** oficial (fill.papermc.io), en `servidor-prueba/`, puerto
  25567, offline, RCON local con clave al azar, más WorldGuard/WorldEdit/GriefPrevention copiados
  de jamon-mercado. Se compila contra la `paper-api` de ese servidor (así el jar sirve en Paper y
  en Purpur).
- Listo cuando: `/twitch probar` dispara cada entrada del catálogo con el cliente "LONIUX", los
  topes cortan lo que se pasa, y la TNT no rompe dentro de un claim de GP ni de una región de WG.

**E2 — Vincular con Device Code Grant.** `auth/*`, `/twitch vincular|desvincular|estado`, enlace
clicable a `twitch.tv/activate` con el código, refresco, validate horario, aviso de re-vincular.
- JUnit contra un servidor HTTP falso (`com.sun.net.httpserver`) para el sondeo, slow_down,
  expiración, refresh y 401.
- Prueba real: necesita **una app tuya en dev.twitch.tv** (cliente público, Device Code Grant) y
  que pegues el **client id** en `servidor-prueba/plugins/BitsCraftPlugin/config.yml`. Tú apruebas el
  vínculo con tu cuenta de Twitch en el navegador.

**E3 — EventSub.** `eventsub/*` y unión con la cola.
- Twitch CLI (release oficial de github.com/twitchdev/twitch-cli) en `herramientas/`;
  `scripts/twitch-mock.sh` levanta `twitch event websocket start-server` y dispara
  `twitch event trigger <evento> --transport=websocket` con el user-id del streamer falso
  (vinculado con `/twitch mock`).
- Listo cuando: los 7 tipos disparan su acción; un evento repetido no se ejecuta dos veces; un
  `session_reconnect` del mock no pierde eventos; con dos streamers falsos cada uno recibe solo lo suyo.
- Prueba real complementaria: un follow desde otra cuenta y un canje de puntos en tu canal.

**E4 — Robustez.** Cola guardada en disco (reinicio a mitad), pausa y desconexión con tope 50 y
24 h, gracia de 10 s al volver, mundos excluidos, `/twitch pausa|vaciar <jugador>` del admin,
`/twitch recargar`.

**E5 — README en español** para los creadores: registrar la app, instalar, dar el permiso con
LuckPerms, vincular, crear las recompensas con el mismo título, ajustar el catálogo y los topes,
qué hacer si pide volver a vincular. Capturas del aviso en pantalla.

## Descargas que pediré aprobar en su momento

- Paper 26.3 (última build estable) desde fill.papermc.io, con su SHA-256.
- Twitch CLI desde las releases oficiales de twitchdev en GitHub, con su checksum.
- JUnit 1.13.4 (se copia de `jamon-mercado/herramientas/` si está; si no, Maven Central).

## Verificación final

1. `scripts/compilar.sh` → compila sin advertencias y pasan las pruebas JUnit
   (escalones, topes, cola/pausa, repetidos, saneo de placeholders, lectura de los JSON de ejemplo
   de EventSub, flujo del Device Code contra el HTTP falso).
2. Servidor Paper local + clientes "LONIUX" y "LionGGZ": `/twitch probar` de todo el catálogo.
3. `/twitch vincular` real con tu app → "vinculado" y el archivo de token con permisos solo-dueño.
4. Twitch CLI mock: cada `twitch event trigger` dispara la acción en el streamer correcto.
5. Capturas en `capturas/` y resumen en `docs/`.

---

## E6 (futura, NO construir hasta que el usuario lo pida): administración desde JamonSync

**Pedido del usuario (2026-11-02):** "para ocupar el launcher que tenemos, puedes crear un apartado de
administracion para configurar ese plugin en especifico? asi como listado por partes de las donaciones
(mas que nada de las ordenes que le llega al plugin,). asi como otras caracteristicas de moderacion,
unicamente que lo pueda ver yo."

Una pestaña «BitsCraft» en la app JamonSync (Tauri; la lleva otra sesión) que **solo ve el usuario**, con:
(a) editar el catálogo, los precios y los límites; (b) el historial de órdenes/donaciones por tipo;
(c) moderación.

### Lado del plugin: API HTTP de administración

- **Escucha solo en 127.0.0.1** (puerto configurable, apagada por defecto: `admin-api.activa: false`), como
  la API de JamonMercado. Para llegar desde afuera, ver «Cómo llega el launcher».
- **Historial:** cada evento recibido se anota en `historial/AAAA-MM-DD.jsonl` (tipo, usuario, monto o
  recompensa, streamer, qué entrada del catálogo tocó, estado: ejecutado, en fila, descartado, cancelado,
  bloqueado). Se guarda N días (configurable). Sin mensajes de bits si `mostrar-mensaje-bits` es false.
- **Endpoints** (JSON):
  - `GET /api/estado`: streamers, conexión con Twitch («7/7 eventos»), fila de cada uno, pausa.
  - `GET /api/eventos?tipo=&streamer=&desde=&hasta=`: historial filtrado y paginado; totales por tipo.
  - `GET /api/catalogo` · `PUT /api/catalogo`: el catálogo y el ritmo como JSON. Antes de guardar se valida
    igual que `/twitch recargar` (mobs, ítems, efectos); si hay errores no se guarda. Copia de respaldo
    `config.yml.AAAA-MM-DD-HHMM` antes de cada cambio y recarga sin reiniciar.
  - Moderación: `POST /api/streamers/{jugador}/pausa|reanudar|vaciar`, `DELETE /api/fila/{jugador}/{evento}`
    (cancelar una orden), `POST /api/eventos/{id}/reintentar`, `GET|POST|DELETE /api/bloqueados`
    (espectadores bloqueados por id de Twitch: sus eventos solo se anotan, sin acción ni aviso),
    `POST /api/probar` (como `/twitch probar-en`).
- **Seguridad:**
  - Emparejar el launcher con un código de un solo uso: `/twitch admin codigo` en el juego o en la consola
    (solo `bitscraft.admin`). Vence en 5 minutos. El launcher lo cambia por un token de sesión.
  - Sesiones guardadas con hash SHA-256, que se pueden revocar (`/twitch admin sesiones|cerrar`).
  - Límite de intentos por IP, CORS solo para el origen de la app Tauri, y registro de cada acción de
    admin (quién, qué, cuándo).
  - **Los tokens de Twitch nunca salen por la API**: no hay endpoint que lea `tokens/` ni que devuelva
    tokens. El client id (público) sí se puede ver.

### Cómo llega el launcher al servidor de los creadores

El plugin corre en **su** servidor, no en serversjamon. Opciones, de más a menos recomendada:
1. **Proxy con HTTPS en su máquina** (Caddy u otro) hacia 127.0.0.1:<puerto>, solo la ruta `/api/`.
2. **Tailscale**: su servidor en el tailnet del usuario y la API sin exponerse a internet (como serversjamon).
3. Si es un hosting con panel, sin proxy ni Tailscale: túnel saliente (por ejemplo, Cloudflare Tunnel).
   Puerto abierto sin TLS: **no**.

### Lado de JamonSync (otra sesión)

- La pestaña aparece solo si hay un servidor emparejado. JamonSync se reparte a otros jugadores, así que
  sin emparejar no se ve nada.
- La dirección del servidor y el token de sesión van en el llavero del sistema (keyring de Tauri), nunca
  en un archivo de configuración.
- Vistas: Estado · Historial (filtros por tipo y streamer, totales) · Catálogo (tabla editable con
  validación) · Moderación (fila de cada streamer, cancelar, reintentar, bloqueados).

### Preguntas abiertas

1. ¿Dónde corre el servidor de los creadores (VPS, hosting con panel, PC de alguien)? ¿Pueden poner un
   proxy con HTTPS o entrar a Tailscale? De eso depende cómo llega el launcher.
2. ¿Solo el usuario administra, o también LONIUX/LionGGZ o un moderador? Hoy el diseño es para uno.
3. ¿Cuántos días de historial se guardan?
4. Bloquear a un espectador: ¿se ignora del todo, o solo sale el aviso en el chat sin la acción?
5. ¿Hace falta ver el historial en vivo (WebSocket o sondeo cada pocos segundos) o basta con recargar?
6. ¿«Cancelar» una orden avisa algo en el chat del juego o es silencioso?
