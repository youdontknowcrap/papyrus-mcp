#!/usr/bin/env bash
# Headless test launch of the installed Papyrus under Xvfb with the freshly built plugin.
# Uses a throwaway configuration (/tmp/pmcfg) whose bundles.info gets our jar appended, so the
# Papyrus install itself is never modified. Opens testmodel/model.di via the plugin's test hook.
set -euo pipefail
PAP=${PAPYRUS:-/workspace/papyrus-app/Papyrus}
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAR=$(ls -t "$HERE"/org.papyrus.mcp/dist/*.jar | head -1)
VER=$(basename "$JAR" .jar); VER=${VER#org.papyrus.mcp_}
pkill -f "configuration /tmp/pmcfg" || true; sleep 1
rm -rf /tmp/pmcfg /tmp/pmws && cp -r "$PAP/configuration" /tmp/pmcfg
rm -rf /tmp/pmcfg/org.eclipse.osgi /tmp/pmcfg/org.eclipse.core.runtime
# p2 rewrites bundles.info at startup, so install our bundle via osgi.bundles instead and tell
# simpleconfigurator not to uninstall bundles it doesn't know about.
sed -i "s|^osgi.bundles=.*|&,reference\\\\:file\\\\:$JAR@4|" /tmp/pmcfg/config.ini
echo "org.eclipse.equinox.simpleconfigurator.exclusiveInstallation=false" >> /tmp/pmcfg/config.ini
cd "$PAP"
nohup xvfb-run -a -s "-screen 0 1600x1000x24" ./papyrus-desktop -configuration /tmp/pmcfg -data /tmp/pmws -nosplash \
  -vmargs -Dpapyrus.mcp.port=7342 -Dpapyrus.mcp.testOpen="$HERE/testmodel/model.di" \
  -Dpapyrus.mcp.debugTools=true -Dpapyrus.mcp.changelogSidecar=true > /tmp/pm.log 2>&1 &
echo "launched; MCP at http://127.0.0.1:7342/mcp (log /tmp/pm.log, /tmp/pmws/.metadata/.log)"
