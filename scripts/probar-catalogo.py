#!/usr/bin/env python3
"""Prueba todo el catálogo en el servidor de PRUEBA local, por RCON.

Necesita el servidor de prueba arrancado y el cliente "LONIUX" conectado (scripts/cliente-prueba.py).
Por cada entrada: borra los mobs, dispara /bits probar-en LONIUX ... (o /bits tiktok simular), espera el aviso y la acción,
y cuenta lo que apareció. Al final imprime un resumen. Solo para el servidor local.
  python scripts/probar-catalogo.py            todo
  python scripts/probar-catalogo.py bits       solo lo que empiece con "bits"
"""
import pathlib, re, socket, struct, sys, time

PROPS = pathlib.Path(__file__).resolve().parent.parent / "servidor-prueba" / "server.properties"
ORIGEN = "0 -60 0"
ESPERA = 4.5  # retardo del aviso (3 s) + margen

# (nombre, comando de prueba, {mob: cantidad esperada}, verificación extra opcional)
CASOS = [
    ("bits 10", "bits 10", {"silverfish": 5}),
    ("bits 50", "bits 50", {"zombie": 5}),
    ("bits 100", "bits 100", {"skeleton": 5, "stray": 2}),
    ("bits 200", "bits 200", {"cave_spider": 5}),
    ("bits 300", "bits 300", {"zombie": 3, "slime": 2}),
    ("bits 400", "bits 400", {"evoker": 1, "pillager": 3}),
    ("bits 500 water drop", "bits 500", {}, "lanzado-sin-balde"),
    ("bits 600", "bits 600", {"creeper": 3}),
    ("bits 700", "bits 700", {"zombie": 10, "witch": 1}),
    ("bits 800 tp-azar", "bits 800", {}, "lejos"),
    ("bits 900", "bits 900", {"ravager": 2, "vex": 5}),
    ("bits 1000", "bits 1000", {"vindicator": 1, "vex": 10}),
    ("bits 1500", "bits 1500", {"wither_skeleton": 3, "blaze": 3, "magma_cube": 2, "ghast": 2}),
    ("bits 2000", "bits 2000", {"creeper": 1, "tnt": 1}),
    ("bits 3000 warden", "bits 3000", {"warden": 1}),
    ("bits 4000", "bits 4000", {"wither": 1}),
    ("bits 5000", "bits 5000", {"ender_dragon": 1}),
    ("bits 10000 nuke", "bits 10000", {"tnt": 100}),
    ("bits 9 (nada)", "bits 9", {"zombie": 0}),
    ("subs 1", "sub 1", {"wolf": 1}),
    ("subs 7 regaladas", "sub-regalo 7", {"wolf": 5, "parrot": 1}),
    ("subs 10", "sub-regalo 10", {"wolf": 10, "bee": 1, "parrot": 1}),
    ("subs 25", "sub-regalo 25", {"wolf": 25, "bee": 1, "parrot": 1, "fox": 1, "panda": 1}),
    ("puntos Dale de comer", "puntos Dale de comer", {}, "pan"),
    ("puntos Saltito", "puntos Saltito", {}, "levitacion"),
    ("puntos Mareo", "puntos Mareo", {}, "nausea"),
    ("puntos Gallinas", "puntos Gallinas", {"chicken": 8}),
    ("puntos Abeja enojada", "puntos Abeja enojada", {"bee": 2}),
    ("puntos Se fue la luz", "puntos Se fue la luz", {}, "oscuridad"),
    ("puntos Sin antorchas", "puntos Sin antorchas", {}, "antorchas"),
    ("puntos Panda", "puntos Panda", {"panda": 1}),
    ("puntos Bicho raro", "puntos Bicho raro", {}, "un-bicho"),
    ("puntos Mob sorpresa", "puntos Mob sorpresa", {}, "un-mob"),
    ("puntos desconocida (nada)", "puntos Hidratarse", {"zombie": 0}),
    ("raid 2 (solo aviso)", "raid 2", {"zombie": 0}),
    ("raid 37", "raid 37", {"zombie": 3}),
    ("follow", "follow", {}),
    # TikTok LIVE: monedas × cantidad = bits (tiktok.bits-por-moneda: 1). "!" = comando /bits tal cual.
    ("tiktok Rose 1 (nada)", "tiktok-regalo Rose 1", {"silverfish": 0}),
    ("tiktok 10 Rosas", "tiktok-regalo Rose 10", {"silverfish": 5}),
    ("tiktok 5 Perfumes", "tiktok-regalo Perfume 5", {"skeleton": 5, "stray": 2}),
    ("tiktok Lion", "tiktok-regalo Lion 1", {"tnt": 100}),
    ("tiktok simulado Galaxy", "!tiktok simular LONIUX regalo Galaxy 1", {"vindicator": 1, "vex": 10}),
    ("tiktok simulado 3 Doughnuts", "!tiktok simular LONIUX regalo Doughnut 3", {"zombie": 5}),
    ("tiktok simulado follow (apagado)", "!tiktok simular LONIUX follow", {"zombie": 0}),
    ("tiktok y twitch a la vez", ["probar-en LONIUX bits 600", "!tiktok simular LONIUX regalo Perfume 5"],
     {"creeper": 3, "skeleton": 5, "stray": 2}),
]


class Rcon:
    def __init__(self):
        cfg = dict(l.split("=", 1) for l in PROPS.read_text().splitlines() if "=" in l and not l.startswith("#"))
        self.s = socket.create_connection(("127.0.0.1", int(cfg["rcon.port"])), timeout=10)
        self.i = 1
        self._enviar(3, cfg["rcon.password"])
        if self._recibir()[0] == -1:
            sys.exit("Clave RCON incorrecta")

    def _enviar(self, tipo, cuerpo):
        self.i += 1
        d = struct.pack("<ii", self.i, tipo) + cuerpo.encode() + b"\x00\x00"
        self.s.sendall(struct.pack("<i", len(d)) + d)

    def _exacto(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise ConnectionError("RCON cerró la conexión")
            b += c
        return b

    def _recibir(self):
        d = self._exacto(struct.unpack("<i", self._exacto(4))[0])
        return struct.unpack("<i", d[:4])[0], d[8:-2].decode(errors="replace")

    def __call__(self, cmd):
        self._enviar(2, cmd)
        return re.sub("§.", "", self._recibir()[1])


def contar(r, tipo):
    t = r(f"execute if entity @e[type={tipo}]")
    m = re.search(r"count: (\d+)", t, re.I)
    return int(m.group(1)) if m else 0


def limpiar(r):
    for _ in range(3):  # los slimes que no son del plugin se dividen al morir
        r("kill @e[type=!player]")
        time.sleep(0.2)
    r(f"tp LONIUX {ORIGEN}")
    r("effect clear LONIUX")
    r("clear LONIUX")


def extra(r, cual):
    if cual == "lanzado-sin-balde":
        y = float(re.search(r"\[[^,]+, ([-\d.]+)d", r("data get entity LONIUX Pos")).group(1))
        balde = "1" in r("clear LONIUX water_bucket 0")
        return y > -45 and not balde, f"altura {y:.0f}, balde {'sí' if balde else 'no'}"
    if cual == "lejos":
        time.sleep(3)
        pos = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", r("data get entity LONIUX Pos"))
        x, z = float(pos.group(1)), float(pos.group(3))
        d = (x * x + z * z) ** 0.5
        return 850 < d < 1150, f"a {d:.0f} bloques"
    if cual == "pan":
        t = r("clear LONIUX bread 0")
        return "8" in t, t
    if cual in ("levitacion", "nausea", "oscuridad"):
        efecto = {"levitacion": "levitation", "nausea": "nausea", "oscuridad": "darkness"}[cual]
        t = r(f"data get entity LONIUX active_effects")
        return efecto in t, "efecto " + ("presente" if efecto in t else "ausente")
    if cual == "antorchas":
        return True, "(se revisa aparte)"
    if cual == "un-bicho":
        n = sum(contar(r, t) for t in ("breeze", "bogged", "creaking"))
        return n >= 1, f"{n} bicho(s)"
    if cual == "un-mob":
        n = sum(contar(r, t) for t in ("creeper", "spider", "witch", "slime"))
        return n == 1, f"{n} mob"
    return True, ""


def hoyos(r, cx, cz):
    """Cuántos bloques del suelo (3 capas) faltan alrededor de cx,cz; de paso los rellena."""
    n = 0
    for y, bloque in ((-61, "grass_block"), (-62, "dirt"), (-63, "dirt")):
        t = r(f"fill {cx - 12} {y} {cz - 12} {cx + 12} {y} {cz + 12} {bloque} replace air")
        m = re.search(r"(\d+) block", t)
        n += int(m.group(1)) if m else 0
    return n


def zonas(r):
    """Creepers (rompen bloques) dentro de la región de WorldGuard "protegida" y fuera de ella."""
    r("gamemode survival LONIUX")
    resultados = []
    for nombre, cz, quiere in (("dentro de la región", 50, False), ("fuera de la región", -50, True)):
        limpiar(r)
        r("gamemode survival LONIUX")
        r("effect give LONIUX resistance infinite 255 true")
        r("effect give LONIUX regeneration infinite 255 true")
        r(f"tp LONIUX 0 -60 {cz}")
        hoyos(r, 0, cz)
        time.sleep(2.2)
        r("twitch probar-en LONIUX bits 600 Pepito")
        time.sleep(16)
        h = hoyos(r, 0, cz)
        bien = (h > 0) == quiere
        resultados.append(bien)
        print(("bien " if bien else "MAL  ") + f"creepers {nombre}: {h} bloques rotos")
    # rompe-bloques: solo-streamer, con una TNT de prueba agregada al config solo mientras dura esto.
    config = pathlib.Path(__file__).resolve().parent.parent / "servidor-prueba/plugins/BitsCraftPlugin/config.yml"
    original = config.read_text(encoding="utf-8")
    prueba = ("  bits:\n    1:\n      nombre: prueba-tnt\n      acciones:\n"
              "        - {tipo: tnt, cantidad: 1, mecha-ticks: 40, distancia: 2, rompe-bloques: solo-streamer}\n")
    config.write_text(original.replace("  bits:\n", prueba, 1), encoding="utf-8")
    try:
        r("twitch recargar")
        for nombre, cx, cz, quiere in (("en la base de LONIUX", 120, 50, True), ("en la región sin dueño", 0, 50, False),
                                       ("afuera", 0, -50, False)):
            limpiar(r)
            r("gamemode survival LONIUX")
            r("effect give LONIUX resistance infinite 255 true")
            r(f"tp LONIUX {cx} -60 {cz}")
            hoyos(r, cx, cz)
            time.sleep(2.2)
            r("twitch probar-en LONIUX bits 1 Pepito")
            time.sleep(7)
            h = hoyos(r, cx, cz)
            bien = (h > 0) == quiere
            resultados.append(bien)
            print(("bien " if bien else "MAL  ") + f"TNT rompe-bloques: solo-streamer {nombre}: {h} bloques rotos")
    finally:
        config.write_text(original, encoding="utf-8")
        r("twitch recargar")
    r("gamemode creative LONIUX")
    limpiar(r)
    return all(resultados)


def main(filtro):
    sys.stdout.reconfigure(encoding="utf-8")
    r = Rcon()
    r("gamemode creative LONIUX")
    r("time set day")
    r("gamerule spawn_mobs false")  # en el mundo plano aparecen slimes solos y ensucian el conteo
    r("gamerule spawn_monsters false")
    ok = mal = 0
    if filtro == "zonas":
        sys.exit(0 if zonas(r) else 1)
    for caso in CASOS:
        nombre, prueba, esperado = caso[0], caso[1], caso[2]
        if filtro and not nombre.startswith(filtro):
            continue
        limpiar(r)
        time.sleep(2.2)
        if nombre == "puntos Sin antorchas":
            r("give LONIUX torch 20")
        pruebas = prueba if isinstance(prueba, list) else [prueba]
        for p in pruebas:
            if p.startswith("!"):
                r("bits " + p[1:])
            elif p.startswith("probar-en "):
                r("bits " + p)
            else:
                r(f"bits probar-en LONIUX {p}")
        time.sleep(ESPERA + 2.5 * (len(pruebas) - 1))
        problemas = []
        for mob, n in esperado.items():
            hay = contar(r, mob)
            if hay != n:
                problemas.append(f"{mob}: {hay} (esperaba {n})")
        detalle = ""
        if len(caso) > 3:
            bien, detalle = extra(r, caso[3])
            if not bien:
                problemas.append(detalle)
        if nombre == "puntos Sin antorchas":
            t = r("clear LONIUX torch 0")
            if "4" not in t:
                problemas.append("antorchas: " + t)
            detalle = t
        if problemas:
            mal += 1
            print(f"MAL  {nombre}: " + "; ".join(problemas))
        else:
            ok += 1
            print(f"bien {nombre} {detalle}".rstrip())
    limpiar(r)
    print(f"\n{ok} bien, {mal} mal")
    sys.exit(1 if mal else 0)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
