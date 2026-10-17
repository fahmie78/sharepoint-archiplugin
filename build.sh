#!/bin/bash
# Build com.archimatetool.sharepoint plugin jar + .archiplugin package
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
TOOL="$ROOT/toolchain"
JDK="$(ls -d /tmp/amazon-corretto-21* /tmp/jdk-21* 2>/dev/null | head -1)"
[ -z "$JDK" ] && { echo "JDK21 missing in /tmp"; exit 1; }
JAVAC="$JDK/bin/javac"; JAR="$JDK/bin/jar"
CP=$(ls "$TOOL"/archi-plugins/*.jar | tr '\n' ':')$(ls "$TOOL"/deps/lib/*.jar | tr '\n' ':')
cd "$HERE"
echo "== compile =="
rm -rf /tmp/sp-bin /tmp/sp-dist && mkdir -p /tmp/sp-bin /tmp/sp-dist
find src -name '*.java' > /tmp/sp-sources.txt
"$JAVAC" -encoding UTF-8 -d /tmp/sp-bin -cp "$CP" @/tmp/sp-sources.txt
echo "== jar =="
mkdir -p /tmp/sp-dist/lib && cp "$TOOL"/deps/lib/*.jar /tmp/sp-dist/lib/
cp -r META-INF /tmp/sp-dist/ && cp plugin.xml /tmp/sp-dist/
cp -r /tmp/sp-bin/com /tmp/sp-dist/
(cd /tmp/sp-dist && "$JAR" cfm "$HERE/com.archimatetool.sharepoint_1.2.0.jar" META-INF/MANIFEST.MF .)
echo "== .archiplugin =="
rm -rf /tmp/sp-pkg && mkdir -p /tmp/sp-pkg
echo 'Magic file to signify this is an Archi plug-in bundle.' > /tmp/sp-pkg/archi-plugin
cp "$HERE/com.archimatetool.sharepoint_1.2.0.jar" /tmp/sp-pkg/
(cd /tmp/sp-pkg && zip -q "$HERE/com.archimatetool.sharepoint_1.2.0.archiplugin" archi-plugin com.archimatetool.sharepoint_1.2.0.jar)
echo "BUILT:"; ls -la com.archimatetool.sharepoint_1.2.0.jar com.archimatetool.sharepoint_1.2.0.archiplugin
