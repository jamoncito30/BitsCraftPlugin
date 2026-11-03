# BitsCraftPlugin — plan

> **Nombre:** el plugin se llama **BitsCraftPlugin** (decidido por el usuario el 2026-11-02; antes "JamonTwitch"). La carpeta sigue siendo `jamon-twitch` y el comando sigue siendo `/twitch`.

Plugin de Paper que conecta los canales de Twitch de los streamers con lo que les pasa en Minecraft.
Ejemplo: alguien dona 100 bits en el canal de LONIUX y aparece un creeper junto a LONIUX.

- **Estado:** etapa 0 cerrada el 2026-11-02. Plan de implementación aprobado el 2026-11-02 en `PLAN-IMPLEMENTACION.md`. **0.3.0 el 2026-11-02** con el catálogo confirmado por LONIUX y LionGGZ (§13). Ver §12 y `docs/PRUEBAS.md`. El **catálogo de la §4 es una propuesta** que el
  usuario les pasa a LONIUX y LionGGZ; lo que digan se cambia en `config.yml`, sin tocar código.
- **Para quién:** el servidor de contenido de los creadores. **No se despliega en el Servidor Jamón.**
- **Regla de seguridad:** nunca se leen, muestran ni escriben en logs los tokens. El plugin no usa
  client secret.

---

## 0. Decisiones tomadas por el usuario (2026-11-02)

| Tema | Decisión |
|---|---|
| Servidor de los creadores | Todavía no se sabe. Se compila contra la **API de Paper 26.3** (como el Servidor Jamón), así corre en Paper y en Purpur 26.3. Hay que confirmarlo con ellos antes de entregar |
| Eventos | **Todos:** bits, canjes de puntos del canal, subs (nuevas, resubs y regaladas), raids y follows |
| Streamers | **Varios a la vez.** Cada streamer vincula su canal a su propio jugador |
| Alcance | Lo de un canal **le pasa solo a su streamer**. Los mobs y la TNT aparecen junto a él, aunque igual pueden alcanzar a quien esté cerca |
| Límites | **Moderado:** mobs, TNT y rayos sí, pero sin romper bloques en zonas protegidas, sin quitar ítems valiosos, con cola y topes por minuto |
| Streamer desconectado o en pausa | **Se guarda** lo que llega (tope de 50 por streamer) y se aplica de a poco al volver. Nadie pierde lo que donó |
| Catálogo | Lo propongo yo (§4); el usuario se lo pasa a los creadores para que opinen |

---

## 1. Cómo funciona

```
Twitch ──EventSub WebSocket──▶ BitsCraftPlugin ──▶ cola del streamer ──▶ aviso ──(retardo)──▶ acción en el juego
            (1 conexión por streamer)              (topes, pausa,
                                                    guardado en disco)
```

1. **Vincular:** el streamer escribe `/twitch vincular` en el juego. El plugin pide a Twitch un
   código (Device Code Grant) y le muestra en el chat un enlace clicable a `twitch.tv/activate` y
   el código. El streamer lo aprueba en el navegador; el plugin se entera solo. **No hace falta
   ninguna web con redirect** ni abrir puertos.
2. **Escuchar:** por cada streamer vinculado, el plugin abre una conexión WebSocket a EventSub y se
   suscribe a sus eventos. Si se cae, reconecta con espera creciente; si Twitch manda
   `session_reconnect`, cambia de conexión sin perder eventos.
3. **Cola:** cada evento se traduce a una o varias acciones (§4) y entra a la cola de su streamer.
   La cola respeta topes (§5) y la pausa. Se guarda en disco para que sobreviva a un reinicio.
4. **Aviso y acción:** se muestra en pantalla (título + chat) quién donó qué. Tras un retardo
   (por defecto 3 s) se ejecuta la acción, para que el streamer y su público alcancen a ver el aviso.

### Eventos de EventSub

| Evento | Suscripción | Scope |
|---|---|---|
| Bits | `channel.cheer` v1 | `bits:read` |
| Puntos del canal | `channel.channel_points_custom_reward_redemption.add` v1 | `channel:read:redemptions` |
| Sub nueva | `channel.subscribe` v1 (se ignoran las que vienen de un regalo, para no contarlas dos veces) | `channel:read:subscriptions` |
| Resub con mensaje | `channel.subscription.message` v1 | `channel:read:subscriptions` |
| Subs regaladas | `channel.subscription.gift` v1 | `channel:read:subscriptions` |
| Raid entrante | `channel.raid` v1 con `to_broadcaster_user_id` | ninguno |
| Follow | `channel.follow` v2, con `moderator_user_id` = el propio streamer | `moderator:read:followers` |

Eventos repetidos: Twitch puede mandar el mismo mensaje dos veces. Se descartan por
`message_id` (se recuerdan los últimos 500 por streamer).

### Tokens

- **App de Twitch:** el usuario (o los creadores) registran una app en dev.twitch.tv como
  **cliente público** con el Device Code Grant habilitado. Solo se usa el **client id**, que va en
  `config.yml`. No hay client secret.
- **Dónde se guardan:** `plugins/BitsCraftPlugin/tokens/<uuid-del-jugador>.json`, con permisos solo
  para el dueño del proceso (POSIX `600` en Linux; en Windows, ACL solo del usuario actual).
  La carpeta queda fuera de cualquier respaldo que se publique; el README lo avisa.
- **Refresco:** el access token se renueva solo antes de vencer y cuando Twitch responde 401.
  En clientes públicos el refresh token sirve **una sola vez** (cada refresco da uno nuevo) y vence
  si no se usa en 30 días: si eso pasa, el streamer recibe un aviso al entrar para volver a vincular.
- **En logs y en el chat** solo aparecen el nombre del canal y el estado ("vinculado", "token
  renovado", "hay que volver a vincular"), nunca el token ni el código de dispositivo completo
  fuera del chat privado del propio streamer.
- `/twitch desvincular` revoca el token en Twitch y borra el archivo.

---

## 2. Comandos y permisos

| Comando | Quién | Qué hace |
|---|---|---|
| `/twitch vincular` | streamer (`bitscraft.streamer`) | Empieza el Device Code Grant y vincula su canal a su jugador |
| `/twitch desvincular` | streamer | Revoca y borra su token |
| `/twitch estado` | streamer | Canal vinculado, conexión, eventos en cola, si está en pausa |
| `/twitch pausa` / `/twitch reanudar` | streamer | Deja de ejecutar acciones; lo que llega se guarda (tope 50) |
| `/twitch probar <evento> [monto]` | admin (`bitscraft.admin`) | Simula un evento sin Twitch, para ajustar el catálogo |
| `/twitch pausa <jugador>` | admin | Pausa a otro streamer (por si algo se sale de control) |
| `/twitch vaciar <jugador>` | admin | Vacía la cola de un streamer |
| `/twitch recargar` | admin | Recarga `config.yml` sin reiniciar |

`bitscraft.streamer` es `op` por defecto: el admin se lo da a cada creador con LuckPerms.

---

## 3. Acciones disponibles

Cada entrada del catálogo es una lista de acciones de estos tipos:

| Tipo | Parámetros | Notas |
|---|---|---|
| `mob` | tipo, cantidad, distancia, nombre (con placeholders), cargado (creeper) | Busca un lugar seguro (aire con suelo) a 3–6 bloques. Lleva el nombre del donante encima |
| `efecto` | efecto, segundos, nivel | Sobre el streamer |
| `dar` | ítem, cantidad, nombre | Si el inventario está lleno, cae al suelo junto a él |
| `quitar` | ítem, cantidad | **Solo ítems de la lista `quitar-permitidos`** (comida, antorchas, bloques comunes). Nunca equipo ni shulkers |
| `rayo` | distancia, daño (sí/no) | `distancia: 0` cae encima |
| `tnt` | mecha (ticks), distancia, rompe-bloques | Mecha visible; la TNT se nota y da tiempo a correr |
| `sonido` / `particulas` / `fuegos` | tipo | Para eventos suaves (follows) |
| `comando` | texto con placeholders | Lo corre la consola. Ver §5 sobre placeholders |
| `aleatorio` | lista de acciones | Elige una al azar (p. ej. "mob sorpresa") |

**Placeholders:** `{streamer}` (jugador), `{canal}`, `{usuario}` (quién donó), `{monto}` (bits,
espectadores o subs), `{recompensa}`, `{nivel}` (tier de la sub).

**Montos:** para bits, raids y subs regaladas se usa el escalón más alto que no pase el monto.
Ej.: con escalones 100 y 500, 350 bits → el de 100. Opcional `repetir: true` en un escalón para
que 300 bits con escalón de 100 cuenten como 3 veces (con tope, §5).

---

## 4. Catálogo propuesto (para que opinen los creadores)

Mezcla de **trolleos** y **ayudas**, para que el chat también pueda salvar al streamer. Los costos
de puntos del canal son sugerencias: cada streamer crea las recompensas en su panel de Twitch con
**el mismo título** que en `config.yml`.

### Bits

| Bits | Qué pasa | Tipo |
|---|---|---|
| 50 | Lluvia de partículas y sonido de aldeano con el nombre del donante | suave |
| 100 | **Creeper** junto al streamer (como en el ejemplo) | trolleo |
| 200 | 3 zombis con el nombre del donante | trolleo |
| 300 | Rayo a 3 bloques (sin fuego) | trolleo |
| 500 | **TNT con mecha visible** (4 s) a 3 bloques | trolleo |
| 750 | Kit de ayuda: 1 manzana dorada + 16 filetes | ayuda |
| 1.000 | "Pánico": ceguera 5 s + 4 esqueletos | trolleo |
| 2.500 | 3 esqueletos wither + noche de golpe (solo si el mundo lo permite, si no solo los mobs) | trolleo fuerte |
| 5.000 | Tótem de la inmortalidad + fuegos artificiales | ayuda grande |

### Puntos del canal

| Recompensa (título en Twitch) | Costo sugerido | Qué pasa |
|---|---|---|
| Dale de comer | 500 | 8 panes |
| Saltito | 1.000 | Levitación 3 s |
| Mareo | 2.000 | Náusea 10 s |
| Gallinas | 2.500 | 8 gallinas alrededor |
| Abeja enojada | 3.000 | 2 abejas enojadas |
| Se fue la luz | 5.000 | Oscuridad 8 s |
| Sin antorchas | 8.000 | Le quita 16 antorchas |
| Mob sorpresa | 10.000 | Uno al azar: creeper, araña, bruja o slime grande |

### Subs

| Evento | Qué pasa |
|---|---|
| Sub nueva o resub, nivel 1 | Fuegos + pan dorado (1 zanahoria dorada × 4) |
| Sub nivel 2 | Fuegos + 1 manzana dorada |
| Sub nivel 3 | Fuegos + 3 diamantes |
| 1–4 subs regaladas | 1 mob sorpresa por sub regalada |
| 5–9 subs regaladas | Horda: 6 zombis + 2 creepers |
| 10+ subs regaladas | Horda grande: 8 zombis + 4 esqueletos + 2 creepers, y al streamer resistencia I por 30 s para que tenga chance |

### Raids

| Espectadores | Qué pasa |
|---|---|
| Menos de 3 | Solo el aviso (evita raids de prueba o bots) |
| 3 o más | Horda de raid: 1 zombi cada 10 espectadores, mínimo 2 y **tope 15**, con el nombre del canal que raidea |

### Follows

Partículas y sonido, **sin mobs**, y como mucho uno cada 30 s por streamer (las oleadas de follows
de bots no pueden llenar la cola). Los demás follows de ese lapso solo salen en el chat.

---

## 5. Límites de seguridad (modo moderado)

Todos están en `config.yml`.

| Límite | Valor por defecto |
|---|---|
| Tiempo mínimo entre acciones del mismo streamer | 2 s |
| Acciones por minuto por streamer | 20 |
| TNT por minuto por streamer | 3 |
| Rayos por minuto por streamer | 5 |
| Mobs vivos del plugin por streamer | 30 (si se llega, los nuevos se saltan y queda en el chat) |
| Repeticiones de un escalón con `repetir: true` | 5 |
| Cola guardada (pausa o desconectado) | 50 eventos, se descartan los más viejos de más de 24 h |
| Al volver de pausa o entrar | 10 s de gracia y después la cola sale a ritmo normal |

Otras protecciones:

- **Zonas protegidas:** si el lugar está en una región de **WorldGuard** o un claim de
  **GriefPrevention** (si esos plugins están), la TNT, los creepers y los rayos **no rompen
  bloques ni prenden fuego**: igual hacen daño. Fuera de esas zonas, `rompe-bloques` decide
  (por defecto **no** para la TNT y **sí** para el creeper, como en vanilla).
- **Mundos excluidos:** lista en el config (p. ej. el lobby). Ahí todo se encola como en pausa.
- **Mobs del plugin:** marcados con una etiqueta interna. No sueltan experiencia ni ítems (para que
  no se puedan farmear) y desaparecen a los 5 min si siguen vivos.
- **Lugar seguro:** no se generan mobs ni TNT dentro de bloques, en el vacío ni en lava.
- **Sin texto del chat en comandos:** `{usuario}` y `{canal}` vienen de Twitch y solo pueden tener
  letras, números y `_`; si traen otra cosa se quitan. **El mensaje que acompaña los bits no se
  usa en comandos** y, por defecto, tampoco se muestra en el juego (`mostrar-mensaje: false`).
- **Freno de emergencia del admin:** `/twitch pausa <jugador>` y `/twitch vaciar <jugador>`.

---

## 6. Avisos

- **Título** en la pantalla del streamer: "LionGGZ_fan donó 500 bits" / subtítulo "¡TNT!".
- **Chat** para todos: `[Twitch] LionGGZ_fan → LONIUX: 500 bits (TNT)`.
- **Retardo** entre aviso y acción: 3 s (configurable, 0 para desactivar).
- Sonido corto al llegar el aviso. Todos los textos están en `config.yml`, en español.

---

## 7. Cómo está hecho

- **Java 25** y la **API de Paper 26.3**, compilado con `javac` como JamonMercado
  (`scripts/compilar.sh`), sin Gradle. Sin dependencias extra: `java.net.http` para HTTP y
  WebSocket, Gson (viene con Paper) para JSON.
- WorldGuard y GriefPrevention son **opcionales** (`softdepend`); si no están, se saltan esas
  comprobaciones.
- Partes:
  - `auth/` — Device Code Grant, refresco y guardado de tokens.
  - `eventsub/` — cliente WebSocket, suscripciones, reconexión, descarte de repetidos.
  - `catalogo/` — lectura del config y elección de escalón.
  - `cola/` — cola por streamer, topes, pausa, guardado en disco.
  - `acciones/` — cada tipo de acción, siempre en el hilo principal del servidor.
  - `proteccion/` — WorldGuard/GriefPrevention.
- Las URLs de Twitch (`id.twitch.tv`, `api.twitch.tv`, `eventsub.wss.twitch.tv`) se pueden cambiar
  en el config **solo para pruebas**, para apuntar al mock de la Twitch CLI.

---

## 8. Pruebas

1. **JUnit:** elección de escalones, topes por minuto, cola y pausa, descarte de repetidos,
   saneo de placeholders, lectura de mensajes de EventSub (con los JSON de ejemplo de Twitch).
2. **Twitch CLI:** `twitch event websocket start-server` levanta un EventSub falso en local; el
   plugin se conecta ahí y con `twitch event trigger channel.cheer --transport=websocket` (y los
   demás eventos) se comprueba que cada uno dispara su acción. Para el login se usa
   `twitch mock-api` o una prueba manual con una app real.
3. **Servidor Paper local** (`servidor-prueba/`, fuera de git) con dos jugadores de prueba como
   streamers, para ver que lo de un canal le pasa solo a su streamer. Capturas en `capturas/`.

---

## 9. Etapas

| Etapa | Qué | Terminado cuando |
|---|---|---|
| E0 | Este plan | Hecho (2026-11-02) |
| E1 | Esqueleto del plugin, config, comandos, `/twitch probar` y todas las acciones con la cola y los topes | `/twitch probar` dispara cada acción del catálogo en el servidor local |
| E2 | Device Code Grant y tokens | Un streamer vincula su canal en el juego (con una app real de Twitch) |
| E3 | EventSub por WebSocket | Los eventos de la Twitch CLI disparan las acciones configuradas |
| E4 | WorldGuard/GriefPrevention, guardado de la cola, pulido de avisos | Pruebas en zona protegida y con reinicio |
| E5 | README en español para los creadores | El README explica registrar la app, instalar, vincular y ajustar el catálogo |

---

## 10. Pendiente fuera de la sesión

- **Usuario:** pasarles el catálogo de la §4 a LONIUX y LionGGZ y traer lo que opinen.
- **Usuario:** preguntarles qué servidor y versión usan (si no es 26.3, se recompila contra la suya).
- **Usuario o creadores:** registrar la app en dev.twitch.tv (cliente público, Device Code Grant)
  y pasar solo el **client id**. Para la E2 sirve una app de prueba del propio usuario.

---

## 11. Cambios del 2026-11-02: lista BitsCraft, documento para creadores, TikTok

**Documento para los creadores:** "BitsCraftPlugin by jamon: propuesta para creadores" (Claude Docs,
https://claude.ai/artifact/6awwkE1CgqFmtsf4AfSjgw). Tiene el funcionamiento en lenguaje simple, los
precios y 8 preguntas abiertas para ellos. **Lo que respondan manda sobre la §4.**

**Catálogo de bits:** reemplaza la tabla de bits de la §4 por la lista de la serie anterior (BitsCraft),
con estos cambios propuestos: 10–49 en vez de 10–50; 3.000–3.999 (antes vacío) = 6 phantoms + noche solo
para el streamer 60 s; Wither y dragón no rompen en zonas protegidas y se van a los 10 min; "nuke" 10×10
(100 TNT) en vez de 20×20, a subir si el servidor aguanta. Subs = perros/loro/abeja/zorro domesticados
(1, 5, 10, 25). Puntos del canal, raids y follows quedan como en la §4.

Tipos de acción nuevos que pide esa lista (para la E1):
- `lanzar` (water drop): sube al streamer N bloques, opcionalmente con balde de agua en la mano.
- `tp-azar`: teletransporte a un lugar seguro a ~N bloques.
- `nuke`: cuadrado de TNT de lado N alrededor del streamer, con el tope de TNT aparte.
- `mascota`: mob domesticado por el streamer (lobo, loro, zorro, abeja amigable), que **no** desaparece.
- Jefes (`wither`, `ender_dragon`) con tiempo de vida propio (10 min) y control de rotura de bloques.
  El dragón en el mundo normal hay que probarlo bien (IA de vuelo, rotura de bloques con EntityExplodeEvent).

**Recomendación del amigo (límite de "4 cuentas por IP"; usar RTC u overlay por streamer):** no se adopta.
- BitsCraft era un modpack con TwitchSpawn + StreamElements que corre en el PC de cada streamer; ahí sí
  hay programas cliente por streamer. Este plugin se conecta desde el servidor.
- El límite documentado de EventSub WebSocket es **por cuenta de streamer** (3 conexiones por usuario y
  client id; costo total 10 por usuario), no por IP. No se encontró un límite por IP.
- Un overlay o algo en el PC de cada streamer agrega instalación y puntos de falla.
- Plan B si en las pruebas aparece un límite: **EventSub Conduits** (oficial, token de app, varios
  canales en pocas conexiones). Necesita client secret en el servidor, por eso no es la opción por defecto.

**TikTok Live (después de Twitch, prioridad baja):** no hay API oficial para leer regalos de un live.
Las librerías que existen (p. ej. TikTokLiveJava) no son oficiales: se rompen cuando TikTok cambia y
pueden ir contra sus términos. Se evalúa al terminar la E5; el catálogo usaría monedas como los bits.

---

## 12. Estado de la 0.1.0 (2026-11-02)

Construida con el catálogo de la propuesta tal cual (los creadores todavía no confirman precios ni
categorías; se cambian en `config.yml` sin recompilar).

- **Hecho:** catálogo completo (35 de 35 probadas en el servidor), cola con topes, pausa y gracia,
  avisos, zonas protegidas (WorldGuard/GriefPrevention), Device Code Grant, tokens con permisos solo
  del dueño y renovación, EventSub por WebSocket con reconexión y descarte de repetidos, fila guardada
  en disco, comandos de admin, README para los creadores. 25 pruebas JUnit.
- **Cambios respecto al plan:** paquete plano `dev.jamon.bitscraft` (como JamonMercado) en vez de
  subpaquetes; `/twitch probar-en <jugador>` para probar desde la consola; los slimes del plugin no se
  dividen (los hijos se podrían farmear); `scripts/twitch-mock.py` como EventSub falso propio.
- **Falta (necesita al usuario):** el Client ID de una app real para probar `/twitch vincular`, y la
  Twitch CLI (no se pudo bajar y ejecutar desde la sesión).
- **Para el servidor de los creadores:** GriefPrevention en modo Creative borra los mobs, y su
  `BlockSurfaceCreeperExplosions` impide los cráteres (ver README, «Ojo con GriefPrevention»).

---

## 13. Respuestas de los creadores → 0.2.0 / 0.3.0 — CATÁLOGO CONFIRMADO

> **Confirmado el 2026-11-03:** LionGGZ "está de acuerdo con las cosas que loniux dijo". Las respuestas de
> abajo ya no son preliminares: valen para los dos creadores.

| # | Pregunta | Respuesta (Discord) | Cómo quedó en `config.yml` |
|---|---|---|---|
| 1 | Water drop: ¿balde? | "Que cada quien tenga los recursos para sobrevivir al water" | `balde: false` |
| 2 | Wither y dragón: ¿rompen? | "Si se puede que rompa solo lo nuestro" | El usuario lo aclaró: el dragón (o wither) que invoca el canal de un streamer solo rompe las parcelas o construcciones **de ese streamer**. `rompe-bloques: solo-streamer` (0.3.0): ni afuera, ni en zonas de otros |
| 3 | Nuke 10×10 | "Si está bien intentar" | Sin cambios (`lado: 10`) |
| 4 | 3.000 bits | "Hace falta un warden es castroso" | Warden (enojado con el streamer, vive 5 min) en vez de phantoms con noche |
| 5 | Perros de subs | "Los animales están bien que se queden" | Sin cambios (las mascotas no desaparecen) |
| 6 | Mensaje de bits | "Like" | `mostrar-mensaje-bits: true` (texto plano, máximo 120 letras) |
| 7 | 3 s de aviso | "Like" | Sin cambios |
| 8 | Recompensas | "Algún panda en puestos o subs y un warden o un bicho nuevo que valga la pena" | Panda en las subs de 25 y canje «Panda» (sugerido 3.000 puntos); canje «Bicho raro» (breeze, 2 bogged o creaking; sugerido 6.000) |

Código nuevo para expresarlo: `rompe-bloques` acepta `false`, `true`, `streamer` y `streamers` (clase
`Rompe`; las zonas miran al dueño del claim o de la región); el warden aparece enojado con el streamer.
Probado en `docs/PRUEBAS.md`. Ojo: con `streamer(s)`, GriefPrevention y WorldGuard también frenan las
explosiones en las zonas, así que el dueño tiene que permitirlas (`/claimexplosions`, flags de WG).

### 0.3.0: «solo lo nuestro»

- Modo nuevo `rompe-bloques: solo-streamer` (clase `Rompe`): rompe solo dentro de las zonas cuyo dueño es
  el streamer al que le donaron. El wither y el dragón lo usan.
- Las zonas siguen detrás de la interfaz `Zonas` (hoy WorldGuard y GriefPrevention). Otro plugin de
  protección o de parcelas = una clase nueva que implemente `protegido(lugar, dueños permitidos)`.
- **Pregunta pendiente para los creadores:** ¿qué plugin de protección o de parcelas usa su servidor
  (GriefPrevention, WorldGuard, PlotSquared, Lands, otro, ninguno)? Sin ninguno, `solo-streamer` no rompe
  nada. Y el dueño de cada zona tiene que permitir explosiones en ella (en GP, `/claimexplosions`).

## 14. Qué falta para entregar (2026-11-03)

El catálogo está confirmado y el plugin está probado con Twitch real. Solo falta:
- **Qué plugin de protección o de parcelas usa el servidor del amigo de los creadores** (probablemente en
  México). Si es GriefPrevention o WorldGuard, ya funciona; si es otro, hay que agregar una clase `Zonas`
  para él; si no hay ninguno, el wither y el dragón no rompen nada.
- Confirmar la versión del servidor (se compila contra Paper 26.3).
- Ese amigo instala el jar, pega el Client ID y da `bitscraft.streamer` a LONIUX y LionGGZ (README).
