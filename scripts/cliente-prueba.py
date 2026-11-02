"""Cliente de PRUEBA de BitsCraftPlugin: un "streamer" para ver los avisos y sacar capturas.

Lanza el Minecraft vanilla 26.3 ya instalado en %APPDATA%\\.minecraft, sin cuenta (el servidor de
prueba es offline), con su propia carpeta de juego (cliente-prueba-<nombre>/, no toca tus opciones ni
mundos), y entra directo a 127.0.0.1:25568 como "LONIUX". No lee credenciales del launcher.
  python scripts/cliente-prueba.py [nombre]     (otro nombre = otro jugador, con su propia carpeta)
Luego, por RCON:   python scripts/rcon.py "twitch probar-en LONIUX bits 600"
y la captura:      .\\scripts\\captura-ventana.ps1 -salida capturas\\x.png   (PowerShell)
"""
import json, os, pathlib, subprocess, sys, uuid

M = pathlib.Path(os.environ["APPDATA"]) / ".minecraft"
NOMBRE = sys.argv[1] if len(sys.argv) > 1 else "LONIUX"
S = pathlib.Path(__file__).resolve().parent.parent / ("cliente-prueba-" + NOMBRE)
(S / "juego").mkdir(parents=True, exist_ok=True)
OPCIONES = """renderDistance:12
simulationDistance:5
pauseOnLostFocus:false
onboardAccessibility:false
tutorialStep:none
narrator:0
soundCategory_master:0.0
guiScale:2
skipMultiplayerWarning:true
joinedFirstServer:true
"""
if not (S / "juego" / "options.txt").exists():
    (S / "juego" / "options.txt").write_text(OPCIONES)

d = json.load(open(M / "versions/26.3/26.3.json"))


def permitido(reglas):
    if not reglas:
        return True
    r = False
    for x in reglas:
        if x.get("features"):
            continue
        so = x.get("os")
        if so and so.get("name") not in (None, "windows"):
            continue
        r = x["action"] == "allow"
    return r


cp = []
for lib in d["libraries"]:
    a = lib.get("downloads", {}).get("artifact")
    if not a or not permitido(lib.get("rules")):
        continue
    nombre = a["path"].split("/")[-1]
    if "arm64" in nombre or "x86." in nombre or nombre.endswith("-x86.jar"):
        continue
    cp.append(str(M / "libraries" / a["path"]))
cp.append(str(M / "versions/26.3/26.3.jar"))

v = {"natives_directory": str(S / "nativos"), "launcher_name": "prueba", "launcher_version": "1",
     "classpath": ";".join(cp), "auth_player_name": NOMBRE, "version_name": "26.3",
     "game_directory": str(S / "juego"), "assets_root": str(M / "assets"),
     "assets_index_name": d["assetIndex"]["id"], "auth_uuid": uuid.uuid3(uuid.NAMESPACE_OID, NOMBRE).hex,
     "auth_access_token": "0", "clientid": "0", "auth_xuid": "0", "version_type": "release"}


def sub(s):
    for k, x in v.items():
        s = s.replace("${" + k + "}", x)
    return s


jvm = [sub(a) for a in d["arguments"]["jvm"] if isinstance(a, str)]
juego = [sub(a) for a in d["arguments"]["game"] if isinstance(a, str)]
juego += ["--width", "1280", "--height", "720", "--quickPlayMultiplayer", "127.0.0.1:25568"]
subprocess.Popen(["java", "-Xmx3G"] + jvm + [d["mainClass"]] + juego, cwd=str(S / "juego"),
                 stdout=open(S / "cliente.log", "w"), stderr=subprocess.STDOUT)
print("Cliente " + NOMBRE + " lanzado; log en " + S.name + "/cliente.log")
