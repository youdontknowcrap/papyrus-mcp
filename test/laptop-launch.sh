#!/usr/bin/env bash
# Launch YOUR laptop Papyrus with the papyrus-mcp plugin, WITHOUT modifying the Papyrus install.
#
# How: copies Papyrus' configuration/ folder to a throwaway folder in /tmp and loads the plugin jar
# from there (osgi.bundles). The install folder is only read. Delete /tmp/papyrus-mcp-cfg to undo
# everything. Your normal workspace and model files are used as usual (nothing is copied or moved).
#
# Usage:   bash laptop-launch.sh                # MCP on 127.0.0.1:7341
#          PORT=7350 TOKEN=secret bash laptop-launch.sh
# Env:     PAPYRUS  Papyrus folder   (default /home/rambo/SYSML/Papyrus)
#          JARDIR   where the jar is (default /home/rambo/SYSML/papyrus-mcp; also looks in dist/)
#          WORKSPACE optional -data workspace folder
set -euo pipefail
PAPYRUS=${PAPYRUS:-/home/rambo/SYSML/Papyrus}
JARDIR=${JARDIR:-/home/rambo/SYSML/papyrus-mcp}
PORT=${PORT:-7341}
CFG=/tmp/papyrus-mcp-cfg

[ -x "$PAPYRUS/papyrus-desktop" ] || { echo "papyrus-desktop not found in $PAPYRUS"; exit 1; }
JAR=$(ls -t "$JARDIR"/org.papyrus.mcp_*.jar "$JARDIR"/dist/org.papyrus.mcp_*.jar "$JARDIR"/org.papyrus.mcp/dist/org.papyrus.mcp_*.jar 2>/dev/null | head -1 || true)
[ -n "$JAR" ] || { echo "No org.papyrus.mcp_*.jar in $JARDIR (or its dist/)"; exit 1; }
case "$JAR" in *" "*) echo "Jar path must not contain spaces: $JAR"; exit 1;; esac
echo "Plugin jar: $JAR"

# Refuse to start a second copy on the same throwaway config.
if pgrep -f -- "-configuration $CFG" >/dev/null; then echo "A test Papyrus is already running (config $CFG). Close it first."; exit 1; fi

rm -rf "$CFG" && cp -r "$PAPYRUS/configuration" "$CFG"
rm -rf "$CFG/org.eclipse.osgi" "$CFG/org.eclipse.core.runtime"   # caches; rebuilt on start
# Add our bundle to osgi.bundles and stop simpleconfigurator from uninstalling it
# (p2 rewrites bundles.info at startup, so editing that file does not stick).
sed -i "s|^osgi.bundles=.*|&,reference\\\\:file\\\\:$JAR@4|" "$CFG/config.ini"
echo "org.eclipse.equinox.simpleconfigurator.exclusiveInstallation=false" >> "$CFG/config.ini"

ARGS=(-configuration "$CFG")
[ -n "${WORKSPACE:-}" ] && ARGS+=(-data "$WORKSPACE")
VMARGS=(-Dpapyrus.mcp.port="$PORT")
[ -n "${TOKEN:-}" ] && VMARGS+=(-Dpapyrus.mcp.token="$TOKEN")

cd "$PAPYRUS"
echo "Starting Papyrus... MCP will listen on http://127.0.0.1:$PORT/mcp once it is up."
echo "Then run: bash $(dirname "$0")/laptop-smoke.sh"
exec ./papyrus-desktop "${ARGS[@]}" -vmargs "${VMARGS[@]}"
