#!/usr/bin/env bash
# Compila BitsCraftPlugin con el JDK local, corre las pruebas y deja el jar en build/.
# Compila contra la paper-api del servidor de prueba (Paper 26.3) y los jars de WorldGuard,
# WorldEdit y GriefPrevention que están en servidor-prueba/plugins/ (son opcionales en el servidor).
#   scripts/compilar.sh            compila, prueba y arma el jar
#   scripts/compilar.sh --sin-pruebas
#   scripts/compilar.sh --instalar copia además el jar a servidor-prueba/plugins/
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
SV="$RAIZ/servidor-prueba"
BUILD="$RAIZ/build"
VERSION="$(tr -d '[:space:]' < "$RAIZ/plugin/VERSION")"
JUNIT_VER=1.13.4
JUNIT="$RAIZ/herramientas/junit-platform-console-standalone-$JUNIT_VER.jar"
PRUEBAS=1
INSTALAR=0
for a in "$@"; do
  case "$a" in
    --sin-pruebas) PRUEBAS=0 ;;
    --instalar) INSTALAR=1 ;;
    *) echo "Opción desconocida: $a" >&2; exit 2 ;;
  esac
done

[ -d "$SV/libraries" ] || { echo "Falta servidor-prueba/ (scripts/armar-servidor-prueba.sh)" >&2; exit 1; }

# Rutas para javac en Windows: estilo C:/... y separador ';'.
ruta() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
SEP=":"; command -v cygpath >/dev/null && SEP=";"

CP=""
while IFS= read -r jar; do CP+="$(ruta "$jar")$SEP"; done < <(
  find "$SV/libraries" -name '*.jar' ;
  ls "$SV"/plugins/worldguard-bukkit-*.jar "$SV"/plugins/worldedit-bukkit-*.jar "$SV"/plugins/GriefPrevention-*.jar )

rm -rf "$BUILD/clases" "$BUILD/clases-prueba"
mkdir -p "$BUILD/clases" "$BUILD/clases-prueba"

echo "== Compilando BitsCraftPlugin $VERSION"
find "$RAIZ/plugin/src/main/java" -name '*.java' | while read -r f; do ruta "$f"; done > "$BUILD/fuentes.txt"
javac --release 25 -encoding UTF-8 -Xlint:all,-processing,-serial,-path,-classfile -Werror \
  -cp "$CP" -d "$(ruta "$BUILD/clases")" @"$(ruta "$BUILD/fuentes.txt")"
cp -r "$RAIZ/plugin/src/main/resources/." "$BUILD/clases/"
sed -i "s/\${version}/$VERSION/" "$BUILD/clases/plugin.yml"

if [ "$PRUEBAS" = 1 ]; then
  if [ ! -f "$JUNIT" ]; then
    echo "== Bajando JUnit $JUNIT_VER de Maven Central"
    mkdir -p "$RAIZ/herramientas"
    URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VER/junit-platform-console-standalone-$JUNIT_VER.jar"
    curl -fsSL -o "$JUNIT.tmp" "$URL"
    ESPERADO="$(curl -fsSL "$URL.sha1" | cut -c1-40)"
    REAL="$(sha1sum "$JUNIT.tmp" | cut -c1-40)"
    [ "$ESPERADO" = "$REAL" ] || { rm -f "$JUNIT.tmp"; echo "SHA-1 de JUnit no coincide" >&2; exit 1; }
    mv "$JUNIT.tmp" "$JUNIT"
  fi
  echo "== Pruebas"
  find "$RAIZ/plugin/src/test/java" -name '*.java' | while read -r f; do ruta "$f"; done > "$BUILD/fuentes-prueba.txt"
  CPP="$(ruta "$BUILD/clases")$SEP$(ruta "$JUNIT")$SEP$CP"
  javac --release 25 -encoding UTF-8 -cp "$CPP" -d "$(ruta "$BUILD/clases-prueba")" @"$(ruta "$BUILD/fuentes-prueba.txt")"
  cp -r "$RAIZ/plugin/src/test/resources/." "$BUILD/clases-prueba/" 2>/dev/null || true
  java --enable-native-access=ALL-UNNAMED -jar "$(ruta "$JUNIT")" execute --disable-banner --details=${DETALLE:-summary} --fail-if-no-tests \
    -cp "$(ruta "$BUILD/clases-prueba")$SEP$CPP" --scan-classpath "$(ruta "$BUILD/clases-prueba")"
fi

JAR="$BUILD/BitsCraftPlugin-$VERSION.jar"
rm -f "$JAR"
jar --create --file "$(ruta "$JAR")" -C "$(ruta "$BUILD/clases")" .
echo "== Listo: $JAR"

if [ "$INSTALAR" = 1 ]; then
  rm -f "$SV"/plugins/BitsCraftPlugin-*.jar
  cp "$JAR" "$SV/plugins/"
  echo "== Copiado a servidor-prueba/plugins/ (se carga al reiniciar el servidor de prueba)"
fi
