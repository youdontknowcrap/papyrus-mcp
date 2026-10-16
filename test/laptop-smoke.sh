#!/usr/bin/env bash
# Quick check of the Papyrus MCP server on the laptop: initialize, ping, list_packages.
# Usage: bash laptop-smoke.sh            (PORT=7341 default; TOKEN=... if you set one)
# Open a model (.di) in Papyrus first, or ping will say modelOpen=false and list_packages errors.
PORT=${PORT:-7341}
URL="http://127.0.0.1:$PORT/mcp"
H=(-s -m 10 -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")
[ -n "${TOKEN:-}" ] && H+=(-H "Authorization: Bearer $TOKEN")
pretty() { if command -v python3 >/dev/null; then python3 -m json.tool; else cat; fi; }

echo "== initialize"
SID=$(curl "${H[@]}" -D - -o /tmp/mcp-init.json \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"laptop-smoke","version":"1"}}}' \
  "$URL" | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
if [ -z "$SID" ]; then echo "No answer from $URL. Is Papyrus running with the plugin? (check Error Log / port)"; exit 1; fi
pretty < /tmp/mcp-init.json; echo "session: $SID"
H+=(-H "Mcp-Session-Id: $SID")

echo; echo "== ping"
curl "${H[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping","arguments":{}}}' "$URL" | pretty

echo; echo "== list_packages"
curl "${H[@]}" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_packages","arguments":{}}}' "$URL" | pretty | head -60
