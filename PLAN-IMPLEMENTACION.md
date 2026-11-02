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
