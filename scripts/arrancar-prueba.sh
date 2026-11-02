#!/usr/bin/env bash
# Arranca el servidor de PRUEBA local (127.0.0.1:25568). La consola queda en esta terminal:
# escribe "stop" para apagarlo, o desde otra: python scripts/rcon.py stop
set -euo pipefail
cd "$(dirname "$0")/../servidor-prueba"
exec java -Xms1G -Xmx2G --enable-native-access=ALL-UNNAMED -jar paper.jar --nogui
