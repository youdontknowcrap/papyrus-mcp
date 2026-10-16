"""Tiny MCP Streamable-HTTP client for tests."""
import json, sys, urllib.request
URL = "http://127.0.0.1:7342/mcp"

class Client:
    def __init__(self, name):
        self.sid = None; self.n = 0
        r = self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": name, "version": "1.0"}})
        self.rpc("notifications/initialized", notify=True)
    def rpc(self, method, params=None, notify=False):
        self.n += 1
        body = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        if not notify: body["id"] = self.n
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.sid: h["Mcp-Session-Id"] = self.sid
        resp = urllib.request.urlopen(urllib.request.Request(sys.modules[__name__].URL, json.dumps(body).encode(), h))
        self.sid = self.sid or resp.headers.get("Mcp-Session-Id")
        data = resp.read()
        return json.loads(data) if data else None
    def call(self, tool, **args):
        r = self.rpc("tools/call", {"name": tool, "arguments": args})["result"]
        return ("ERROR: " + r["content"][0]["text"]) if r["isError"] else r["structuredContent"]

def show(title, v, n=700):
    print(f"\n### {title}\n" + json.dumps(v, ensure_ascii=False)[:n])

