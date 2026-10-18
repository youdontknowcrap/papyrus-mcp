#!/usr/bin/env bash
# Headless test launch of the installed Papyrus under Xvfb with the freshly built plugin.
# Uses a throwaway configuration (/tmp/pmcfg) whose bundles.info gets our jar appended, so the
# Papyrus install itself is never modified. Opens testmodel/MudHut Opener Am..di via the plugin's test hook.
set -euo pipefail
PAP=${PAPYRUS:-/workspace/papyrus-app/Papyrus}
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAR=$(ls -t "$HERE"/org.papyrus.mcp/dist/*.jar | head -1)
VER=$(basename "$JAR" .jar); VER=${VER#org.papyrus.mcp_}
pkill -f "configuration /tmp/pmcfg" || true; sleep 1
# MODEL=/path/x.di opens that model instead (and never resets it).
OPEN=${MODEL:-"$HERE/testmodel/MudHut Opener Am..di"}
# Fresh test model each run (RESET=0 to keep edits): original file names so notation/aird refs resolve.
if [ -z "${MODEL:-}" ] && [ "${RESET:-1}" = 1 ]; then
  rm -f "$HERE"/testmodel/MudHut* "$HERE"/testmodel/*.mcp-changelog.json
  for e in di uml notation aird; do cp "/workspace/mudhut/papyrus-out/model.$e" "$HERE/testmodel/MudHut Opener Am..$e"; done
fi
rm -rf /tmp/pmcfg /tmp/pmws && cp -r "$PAP/configuration" /tmp/pmcfg
rm -rf /tmp/pmcfg/org.eclipse.osgi /tmp/pmcfg/org.eclipse.core.runtime
# p2 rewrites bundles.info at startup, so install our bundle via osgi.bundles instead and tell
# simpleconfigurator not to uninstall bundles it doesn't know about.
sed -i "s|^osgi.bundles=.*|&,reference\\\\:file\\\\:$JAR@4|" /tmp/pmcfg/config.ini
echo "org.eclipse.equinox.simpleconfigurator.exclusiveInstallation=false" >> /tmp/pmcfg/config.ini
# Label clipping fix (FONTFIX=0 to skip): the box's default "sans" is a variable font whose
# measured width is a bit narrower than what gets drawn, so the last letter of labels was cut.
# Point the usual UI font names at the static DejaVu Sans instead.
if [ "${FONTFIX:-1}" = 1 ]; then
  cat > /tmp/pmfonts.conf <<'XML'
<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><include ignore_missing="yes">/etc/fonts/fonts.conf</include>
<match target="pattern"><test name="family"><string>Segoe UI</string></test><edit name="family" mode="assign" binding="strong"><string>DejaVu Sans</string></edit></match>
<match target="pattern"><test name="family"><string>sans-serif</string></test><edit name="family" mode="prepend" binding="strong"><string>DejaVu Sans</string></edit></match>
<match target="pattern"><test name="family"><string>Sans</string></test><edit name="family" mode="prepend" binding="strong"><string>DejaVu Sans</string></edit></match>
</fontconfig>
XML
  export FONTCONFIG_FILE=/tmp/pmfonts.conf
fi
cd "$PAP"
nohup xvfb-run -a -s "-screen 0 1600x1000x24" ./papyrus-desktop -configuration /tmp/pmcfg -data /tmp/pmws -nosplash \
  -vmargs -Dpapyrus.mcp.port=7342 -Dpapyrus.mcp.testOpen="$OPEN" \
  -Dpapyrus.mcp.changelogSidecar=true -Dpapyrus.mcp.debugTools=true -Dpapyrus.mcp.uiTimeoutMs=${UITIMEOUT:-3000} > /tmp/pm.log 2>&1 &
echo "launched; MCP at http://127.0.0.1:7342/mcp (log /tmp/pm.log, /tmp/pmws/.metadata/.log)"
