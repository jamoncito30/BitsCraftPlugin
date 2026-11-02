#!/usr/bin/env python3
"""Manda comandos de consola al servidor de PRUEBA local por RCON (127.0.0.1).

Uso: python scripts/rcon.py "twitch probar-en LONIUX bits 600" "list"
Lee el puerto y la clave de servidor-prueba/server.properties. Solo para el servidor local.
"""
import pathlib, re, socket, struct, sys

PROPS = pathlib.Path(__file__).resolve().parent.parent / "servidor-prueba" / "server.properties"


def main(comandos):
    sys.stdout.reconfigure(encoding="utf-8")
    cfg = dict(l.split("=", 1) for l in PROPS.read_text().splitlines() if "=" in l and not l.startswith("#"))
    s = socket.create_connection(("127.0.0.1", int(cfg["rcon.port"])), timeout=10)

    def enviar(rid, tipo, cuerpo):
        d = struct.pack("<ii", rid, tipo) + cuerpo.encode() + b"\x00\x00"
        s.sendall(struct.pack("<i", len(d)) + d)

    def exacto(n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise ConnectionError("RCON cerró la conexión")
            b += c
        return b

    def recibir():
        d = exacto(struct.unpack("<i", exacto(4))[0])
        return struct.unpack("<i", d[:4])[0], d[8:-2].decode(errors="replace")

    enviar(1, 3, cfg["rcon.password"])
    if recibir()[0] == -1:
        sys.exit("Clave RCON incorrecta")
    for i, cmd in enumerate(comandos, start=2):
        enviar(i, 2, cmd)
        texto = recibir()[1]
        print(f"> {cmd}\n{re.sub('§.', '', texto)}")


if __name__ == "__main__":
    main(sys.argv[1:])
