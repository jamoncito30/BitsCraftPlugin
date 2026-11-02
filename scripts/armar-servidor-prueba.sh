#!/usr/bin/env bash
# Arma servidor-prueba/: Paper 26.3 oficial (fill.papermc.io, con su SHA-256), WorldGuard,
# WorldEdit y GriefPrevention (copiados de jamon-mercado/servidor-prueba), mundo plano, dificultad
# normal, solo en 127.0.0.1:25568, RCON local con clave al azar y una región de WorldGuard de prueba
# ("protegida", de -20,-64,30 a 20,320,70, sin dueño) y otra de LONIUX ("base_loniux", de 100,-64,30
# a 140,320,70; LONIUX en modo offline tiene el UUID 40ed09fc-…) para probar rompe-bloques: streamer.
# Las dos permiten explosiones en WorldGuard, para que lo que se prueba sea el plugin.
#   scripts/armar-servidor-prueba.sh --acepto-eula
# --acepto-eula escribe eula=true (equivale a aceptar el EULA de Minecraft); sin él, edítalo a mano.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
SV="$RAIZ/servidor-prueba"
EULA=0
[ "${1:-}" = "--acepto-eula" ] && EULA=1

if [ -e "$SV/world" ]; then
  echo "servidor-prueba/ ya tiene un mundo. Bórralo a mano si quieres armarlo de cero." >&2
  exit 1
fi
mkdir -p "$SV/plugins"
cd "$SV"

if [ ! -f paper.jar ]; then
  echo "== Bajando Paper 26.3 (última build estable)"
  INFO="$(curl -fsSL -H 'User-Agent: bitscraftplugin-pruebas' https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest)"
  URL="$(python -c 'import json,sys; d=json.loads(sys.argv[1]); print(d["downloads"]["server:default"]["url"])' "$INFO")"
  SHA="$(python -c 'import json,sys; d=json.loads(sys.argv[1]); print(d["downloads"]["server:default"]["checksums"]["sha256"])' "$INFO")"
  curl -fsSL -o paper.jar.tmp "$URL"
  [ "$(sha256sum paper.jar.tmp | cut -c1-64)" = "$SHA" ] || { rm -f paper.jar.tmp; echo "SHA-256 de Paper no coincide" >&2; exit 1; }
  mv paper.jar.tmp paper.jar
fi
[ -d libraries ] || java -Dpaperclip.patchonly=true -jar paper.jar

for j in worldguard-bukkit-7.0.20.jar worldedit-bukkit-7.4.6.jar GriefPrevention-16.18.7.jar; do
  [ -f "plugins/$j" ] || cp "$RAIZ/../jamon-mercado/servidor-prueba/plugins/$j" plugins/
done

echo "== server.properties local (127.0.0.1:25568, RCON local con clave nueva)"
CLAVE="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
cat > server.properties <<EOP
# Servidor de PRUEBA local de BitsCraftPlugin. Solo escucha en esta PC.
server-ip=127.0.0.1
server-port=25568
online-mode=false
enforce-secure-profile=false
white-list=false
motd=BitsCraftPlugin - PRUEBA LOCAL
level-name=world
level-type=minecraft\:flat
generate-structures=false
difficulty=normal
force-gamemode=false
gamemode=survival
spawn-protection=0
view-distance=8
simulation-distance=6
max-players=10
enable-rcon=true
rcon.port=25578
rcon.password=$CLAVE
EOP
[ "$EULA" = 1 ] && echo "eula=true" > eula.txt

echo "== Región de WorldGuard de prueba"
mkdir -p plugins/WorldGuard/worlds/world
cat > plugins/WorldGuard/worlds/world/regions.yml <<'EOR'
regions:
    protegida:
        type: cuboid
        min: {x: -20.0, y: -64.0, z: 30.0}
        max: {x: 20.0, y: 320.0, z: 70.0}
        members: {}
        flags: {tnt: allow, creeper-explosion: allow, other-explosion: allow}
        owners: {}
        priority: 0
    base_loniux:
        type: cuboid
        min: {x: 100.0, y: -64.0, z: 30.0}
        max: {x: 140.0, y: 320.0, z: 70.0}
        members: {}
        flags: {tnt: allow, creeper-explosion: allow, other-explosion: allow}
        owners: {unique-ids: [40ed09fc-a5fe-3561-be93-88e09550f789]}
        priority: 0
EOR
echo "== Listo. Compila e instala el plugin con scripts/compilar.sh --instalar y arranca con scripts/arrancar-prueba.sh"
