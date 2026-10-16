#!/usr/bin/env bash
# Plain-javac build against an installed Papyrus (no Maven needed).
# Usage: PAPYRUS=/path/to/Papyrus ./build.sh   -> dist/org.papyrus.mcp_0.1.0.jar
set -euo pipefail
cd "$(dirname "$0")"
PAPYRUS=${PAPYRUS:-/workspace/papyrus-app/Papyrus}
CP=$(find "$PAPYRUS/plugins" -maxdepth 1 \( -name '*.jar' ! -name '*.source_*' \) | tr '\n' ':')
for d in "$PAPYRUS"/plugins/*/; do CP="$CP$d:"; for j in "$d"*.jar "$d"lib/*.jar; do [ -f "$j" ] && CP="$CP$j:"; done; done
rm -rf bin dist && mkdir -p bin dist
javac --release 21 -nowarn -cp "$CP" -d bin $(find src -name '*.java')
VER=0.1.0.$(date +%Y%m%d%H%M)
sed "s/0.1.0.qualifier/$VER/" META-INF/MANIFEST.MF > bin/MANIFEST.tmp
cp plugin.xml bin/
jar cfm "dist/org.papyrus.mcp_$VER.jar" bin/MANIFEST.tmp -C bin org -C bin plugin.xml
echo "Built dist/org.papyrus.mcp_$VER.jar"
