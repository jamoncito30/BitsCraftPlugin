#!/usr/bin/env python3
"""EventSub de MENTIRA en 127.0.0.1:8080, para probar el plugin sin Twitch ni la Twitch CLI.

Imita lo que usa el plugin del servidor de la Twitch CLI (`twitch event websocket start-server`):
  ws://127.0.0.1:8080/ws                      el WebSocket de EventSub (manda session_welcome y keepalives)
  POST http://127.0.0.1:8080/eventsub/subscriptions   acepta las suscripciones (202)
y agrega una puerta para disparar eventos:
  POST http://127.0.0.1:8080/disparar?tipo=cheer&canal=42&monto=600&usuario=Pepito

Uso:
  python scripts/twitch-mock.py servir                      (deja corriendo; Ctrl+C para parar)
  python scripts/twitch-mock.py disparar cheer 42 600 [usuario]
  python scripts/twitch-mock.py disparar puntos 42 "Saltito" [usuario]
  python scripts/twitch-mock.py disparar sub|regalo|raid|follow 42 [monto] [usuario]
  python scripts/twitch-mock.py disparar repetir           (manda otra vez el último evento, mismo id)
  python scripts/twitch-mock.py disparar reconectar        (session_reconnect: el plugin cambia de conexión)

En el plugin (servidor de prueba): twitch.url-eventsub = ws://127.0.0.1:8080/ws,
twitch.url-api = http://127.0.0.1:8080, /twitch recargar y /twitch mock <jugador> 42 <login>.
Solo escucha en 127.0.0.1.
"""
import base64, hashlib, json, socket, struct, sys, threading, time, urllib.parse, urllib.request, uuid

PUERTO = 8080
clientes = []  # sockets WebSocket abiertos
candado = threading.Lock()
ultimo = {"msg": None}


def ahora():
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


def marco(texto):
    d = texto.encode()
    if len(d) < 126:
        cab = struct.pack("!BB", 0x81, len(d))
    elif len(d) < 65536:
        cab = struct.pack("!BBH", 0x81, 126, len(d))
    else:
        cab = struct.pack("!BBQ", 0x81, 127, len(d))
    return cab + d


def enviar_a_todos(texto):
    with candado:
        for c in list(clientes):
            try:
                c.sendall(marco(texto))
            except OSError:
                clientes.remove(c)
    return len(clientes)


def bienvenida(sesion):
    return json.dumps({"metadata": {"message_id": str(uuid.uuid4()), "message_type": "session_welcome",
                                    "message_timestamp": ahora()},
                       "payload": {"session": {"id": sesion, "status": "connected", "connected_at": ahora(),
                                               "keepalive_timeout_seconds": 10, "reconnect_url": None}}})


def evento(tipo, canal, valor, usuario):
    u = {"user_id": "1234", "user_login": usuario.lower(), "user_name": usuario}
    b = {"broadcaster_user_id": canal, "broadcaster_user_login": "canal", "broadcaster_user_name": "Canal"}
    if tipo == "cheer":
        return "channel.cheer", {**u, **b, "is_anonymous": False, "bits": int(valor or 100), "message": "Cheer hola"}
    if tipo == "puntos":
        return ("channel.channel_points_custom_reward_redemption.add",
                {**u, **b, "id": str(uuid.uuid4()), "user_input": "", "status": "unfulfilled",
                 "reward": {"id": "r1", "title": valor or "Saltito", "cost": 1000, "prompt": ""}, "redeemed_at": ahora()})
    if tipo == "sub":
        return "channel.subscribe", {**u, **b, "tier": "1000", "is_gift": False}
    if tipo == "regalo":
        return "channel.subscription.gift", {**u, **b, "total": int(valor or 5), "tier": "1000",
                                              "cumulative_total": None, "is_anonymous": False}
    if tipo == "raid":
        return "channel.raid", {"from_broadcaster_user_id": "999", "from_broadcaster_user_login": usuario.lower(),
                                "from_broadcaster_user_name": usuario, "to_broadcaster_user_id": canal,
                                "to_broadcaster_user_login": "canal", "to_broadcaster_user_name": "Canal",
                                "viewers": int(valor or 37)}
    if tipo == "follow":
        return "channel.follow", {**u, **b, "followed_at": ahora()}
    raise ValueError("tipo desconocido: " + tipo)


def notificacion(tipo_sub, ev):
    return json.dumps({"metadata": {"message_id": str(uuid.uuid4()), "message_type": "notification",
                                    "message_timestamp": ahora(), "subscription_type": tipo_sub,
                                    "subscription_version": "2" if tipo_sub == "channel.follow" else "1"},
                       "payload": {"subscription": {"id": str(uuid.uuid4()), "type": tipo_sub, "status": "enabled"},
                                   "event": ev}})


def responder(c, codigo, cuerpo=""):
    d = cuerpo.encode()
    c.sendall(f"HTTP/1.1 {codigo} OK\r\nContent-Type: application/json\r\nContent-Length: {len(d)}\r\n"
              f"Connection: close\r\n\r\n".encode() + d)
    c.close()


def atender(c):
    datos = b""
    while b"\r\n\r\n" not in datos:
        p = c.recv(4096)
        if not p:
            c.close()
            return
        datos += p
    cab, _, resto = datos.partition(b"\r\n\r\n")
    lineas = cab.decode(errors="replace").split("\r\n")
    metodo, ruta, _ = lineas[0].split(" ", 2)
    h = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lineas[1:] if ":" in l}
    largo = int(h.get("content-length", "0"))
    while len(resto) < largo:
        resto += c.recv(4096)
    if h.get("upgrade", "").lower() == "websocket":
        acepta = base64.b64encode(hashlib.sha1((h["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                               .encode()).digest()).decode()
        c.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Accept: {acepta}\r\n\r\n").encode())
        sesion = "sesion_" + uuid.uuid4().hex[:8]
        c.sendall(marco(bienvenida(sesion)))
        with candado:
            clientes.append(c)
        print(f"[mock] conexión WebSocket nueva ({ruta}), sesión {sesion}; {len(clientes)} abierta(s)")
        threading.Thread(target=leer_ws, args=(c,), daemon=True).start()
        return
    if metodo == "POST" and ruta.startswith("/eventsub/subscriptions"):
        cuerpo = json.loads(resto or b"{}")
        print(f"[mock] suscripción {cuerpo.get('type')} {cuerpo.get('condition')}")
        responder(c, 202, json.dumps({"data": [{"id": str(uuid.uuid4()), "status": "enabled", **cuerpo}],
                                      "total": 1, "total_cost": 0, "max_total_cost": 10}))
        return
    if metodo == "POST" and ruta.startswith("/disparar"):
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(ruta).query))
        if q.get("tipo") == "repetir":
            texto = ultimo["msg"]
        elif q.get("tipo") == "reconectar":
            texto = json.dumps({"metadata": {"message_id": str(uuid.uuid4()), "message_type": "session_reconnect",
                                             "message_timestamp": ahora()},
                                "payload": {"session": {"id": "x", "status": "reconnecting",
                                                        "reconnect_url": f"ws://127.0.0.1:{PUERTO}/ws?reconectado"}}})
        else:
            tipo_sub, ev = evento(q["tipo"], q.get("canal", "42"), q.get("valor"), q.get("usuario", "Pepito"))
            texto = notificacion(tipo_sub, ev)
            ultimo["msg"] = texto
        n = enviar_a_todos(texto) if texto else 0
        responder(c, 200, json.dumps({"enviado_a": n}))
        return
    responder(c, 404, "{}")


def leer_ws(c):
    try:
        while True:
            b = c.recv(2)
            if len(b) < 2:
                break
            largo = b[1] & 0x7F
            if largo == 126:
                largo = struct.unpack("!H", c.recv(2))[0]
            elif largo == 127:
                largo = struct.unpack("!Q", c.recv(8))[0]
            faltan = 4 + largo
            while faltan > 0:
                faltan -= len(c.recv(faltan))
            if b[0] & 0x0F == 8:
                c.sendall(b"\x88\x00")
                break
    except OSError:
        pass
    with candado:
        if c in clientes:
            clientes.remove(c)
    c.close()
    print(f"[mock] se cerró una conexión; {len(clientes)} abierta(s)")


def keepalives():
    while True:
        time.sleep(8)
        enviar_a_todos(json.dumps({"metadata": {"message_id": str(uuid.uuid4()), "message_type": "session_keepalive",
                                                "message_timestamp": ahora()}, "payload": {}}))


def servir():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", PUERTO))
    s.listen()
    threading.Thread(target=keepalives, daemon=True).start()
    print(f"[mock] EventSub falso en ws://127.0.0.1:{PUERTO}/ws", flush=True)
    while True:
        c, _ = s.accept()
        threading.Thread(target=atender, args=(c,), daemon=True).start()


def disparar(args):
    tipo = args[0]
    q = {"tipo": tipo}
    if tipo not in ("repetir", "reconectar"):
        q["canal"] = args[1]
        if len(args) > 2:
            q["valor"] = args[2]
        if len(args) > 3:
            q["usuario"] = args[3]
    url = f"http://127.0.0.1:{PUERTO}/disparar?" + urllib.parse.urlencode(q)
    print(urllib.request.urlopen(urllib.request.Request(url, method="POST"), timeout=5).read().decode())


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    if len(sys.argv) > 1 and sys.argv[1] == "disparar":
        disparar(sys.argv[2:])
    else:
        servir()
