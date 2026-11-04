#!/usr/bin/env bash
# Compila BitsCraftPlugin con el JDK local, corre las pruebas y deja el jar en build/.
# Compila contra la paper-api del servidor de prueba (Paper 26.3) y los jars de WorldGuard,
# WorldEdit y GriefPrevention que están en servidor-prueba/plugins/ (son opcionales en el servidor).
# TikTokLiveJava y sus dependencias se bajan a herramientas/tiktok/ (SHA-256 fijo) y van DENTRO del jar.
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

# TikTok LIVE: TikTokLiveJava 1.13.1 (jwdeveloper, MIT) y lo que necesita. Gson y slf4j ya vienen con Paper.
# Para actualizar la librería: cambiar la versión y los SHA-256 (sha256sum del jar nuevo, revisado a mano).
TT="$RAIZ/herramientas/tiktok"
JITPACK=https://jitpack.io/com/github/jwdeveloper
CENTRAL=https://repo1.maven.org/maven2
LIBS_TIKTOK=(
  "$JITPACK/TikTok-Live-Java/Client/1.13.1-Release/Client-1.13.1-Release.jar 3d60a6c2487c5fd0bb60caff9df873eed68415eff2e7cad93292dfa1c8ef2c75"
  "$JITPACK/TikTok-Live-Java/API/1.13.1-Release/API-1.13.1-Release.jar f25fe2b3807249fee5c7f9e40b5444c049d8f0e62ca18e2ac8d1c4a84c78a621"
  "$JITPACK/DepenDance/DepenDance-Full/0.0.18-Release/DepenDance-Full-0.0.18-Release.jar 901f983906651033cf4f7c22e6cf5ace6790ff4cbc4e69a46cfef2a2486ac297"
  "$JITPACK/DepenDance/Injector/0.0.18-Release/Injector-0.0.18-Release.jar c6d5dfc22358b3ee984daa5e55d8a23a87c558ce6f16c2aa0f9ac80a51245b91"
  "$JITPACK/DepenDance/Decorator/0.0.18-Release/Decorator-0.0.18-Release.jar 763702ff57ee8bbfd1678be387cd73d0ff33822406e5b78e59145693516fdd7c"
  "$CENTRAL/com/google/protobuf/protobuf-java/4.26.1/protobuf-java-4.26.1.jar 091933e5870af810748326f7ace4a673aca721253177542842f044b546f14282"
  "$CENTRAL/org/java-websocket/Java-WebSocket/1.5.5/Java-WebSocket-1.5.5.jar 0613e7e52971f719e05d85100a49568820e16c516ae1c0e290235cdfdc529e3f"
)
mkdir -p "$TT"
JARS_TIKTOK=()
for linea in "${LIBS_TIKTOK[@]}"; do
  url="${linea%% *}"; sha="${linea##* }"; f="$TT/$(basename "$url")"
  if [ ! -f "$f" ]; then
    echo "== Bajando $(basename "$url")"
    curl -fsSL -o "$f.tmp" "$url"
    mv "$f.tmp" "$f"
  fi
  real="$(sha256sum "$f" | cut -c1-64)"
  [ "$real" = "$sha" ] || { echo "SHA-256 de $(basename "$f") no coincide (¿la cambiaron?): se borra" >&2; rm -f "$f"; exit 1; }
  JARS_TIKTOK+=("$f")
done

# Rutas para javac en Windows: estilo C:/... y separador ';'.
ruta() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
SEP=":"; command -v cygpath >/dev/null && SEP=";"

CP=""
while IFS= read -r jar; do CP+="$(ruta "$jar")$SEP"; done < <(
  find "$SV/libraries" -name '*.jar' ;
  ls "$SV"/plugins/worldguard-bukkit-*.jar "$SV"/plugins/worldedit-bukkit-*.jar "$SV"/plugins/GriefPrevention-*.jar )
# La librería de TikTok solo hace falta para compilar ConectorTikTokLive; las pruebas corren SIN ella
# (así ninguna prueba puede conectarse a TikTok por error).
CP_TIKTOK=""
for jar in "${JARS_TIKTOK[@]}"; do CP_TIKTOK+="$(ruta "$jar")$SEP"; done

rm -rf "$BUILD/clases" "$BUILD/clases-prueba" "$BUILD/libs-tiktok"
mkdir -p "$BUILD/clases" "$BUILD/clases-prueba" "$BUILD/libs-tiktok"

echo "== Compilando BitsCraftPlugin $VERSION"
find "$RAIZ/plugin/src/main/java" -name '*.java' | while read -r f; do ruta "$f"; done > "$BUILD/fuentes.txt"
javac --release 25 -encoding UTF-8 -Xlint:all,-processing,-serial,-path,-classfile -Werror \
  -cp "$CP$CP_TIKTOK" -d "$(ruta "$BUILD/clases")" @"$(ruta "$BUILD/fuentes.txt")"
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

# Las clases de TikTokLiveJava y sus dependencias van dentro del jar (sin META-INF de cada una).
for f in "${JARS_TIKTOK[@]}"; do
  (cd "$BUILD/libs-tiktok" && jar --extract --file "$(ruta "$f")")
done
rm -rf "$BUILD/libs-tiktok/META-INF"
JAR="$BUILD/BitsCraftPlugin-$VERSION.jar"
rm -f "$JAR"
jar --create --file "$(ruta "$JAR")" -C "$(ruta "$BUILD/clases")" . -C "$(ruta "$BUILD/libs-tiktok")" .
echo "== Listo: $JAR"

if [ "$INSTALAR" = 1 ]; then
  rm -f "$SV"/plugins/BitsCraftPlugin-*.jar
  cp "$JAR" "$SV/plugins/"
  echo "== Copiado a servidor-prueba/plugins/ (se carga al reiniciar el servidor de prueba)"
fi
