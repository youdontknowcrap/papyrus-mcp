#!/usr/bin/env python3
"""MCP smoke test against a running Papyrus (default port 7342). Two clients, read tools,
changelog, stale-edit detection. Uses debug_rename (needs -Dpapyrus.mcp.debugTools=true)."""
import json, sys, urllib.request
URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:7342/mcp"

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
        resp = urllib.request.urlopen(urllib.request.Request(URL, json.dumps(body).encode(), h))
        self.sid = self.sid or resp.headers.get("Mcp-Session-Id")
        data = resp.read()
        return json.loads(data) if data else None
    def call(self, tool, **args):
        r = self.rpc("tools/call", {"name": tool, "arguments": args})["result"]
        return ("ERROR: " + r["content"][0]["text"]) if r["isError"] else r["structuredContent"]

def show(title, v, n=700):
    print(f"\n### {title}\n" + json.dumps(v, ensure_ascii=False)[:n])

a, b = Client("grok-build"), Client("grok-bot")
show("tools/list", [t["name"] for t in a.rpc("tools/list")["result"]["tools"]])
show("list_open_models", a.call("list_open_models"))
show("list_packages", a.call("list_packages"), 500)
cls = a.call("find_elements", query="Scene", metaclass="Class")
show("find_elements Scene/Class", cls)
sid = cls["elements"][0]["id"]
show("get_element", a.call("get_element", id=sid, depth=1), 1200)
show("get_relationships", a.call("get_relationships", id=sid), 800)
show("list_elements recursive Association", {k: v for k, v in a.call("list_elements", metaclass="Association", recursive=True, limit=3).items()})
show("get_stereotypes (model)", a.call("get_stereotypes"))
show("get_stereotypes (element)", a.call("get_stereotypes", id=sid))
dg = a.call("list_diagrams")
show("list_diagrams", {"count": len(dg["diagrams"]), "first": dg["diagrams"][:2], "frameworks": sorted({d["framework"] for d in dg["diagrams"]})}, 900)
show("get_selection", a.call("get_selection"))
show("get_element by qualifiedName", a.call("get_element", id=cls["elements"][0]["qualifiedName"], depth=0, include=[]))

# --- changelog + stale edits
el = a.call("get_element", id=sid, depth=0, include=[])
v0, etag0 = el["modelVersion"], el["element"]["etag"]
show("A rename (expected_version ok)", a.call("debug_rename", id=sid, name="SceneX", expected_version=v0))
show("B rename with stale expected_version", b.call("debug_rename", id=sid, name="SceneY", expected_version=v0))
show("B rename with stale etag", b.call("debug_rename", id=sid, name="SceneY", expected_etags={sid: etag0}))
show("B rename back (no check)", b.call("debug_rename", id=sid, name="Scene"))
show("A get_changelog (cursor)", a.call("get_changelog"), 1500)
show("A get_changelog again (cursor -> empty)", a.call("get_changelog"))
show("B get_changelog client=grok-build since 0", b.call("get_changelog", since_seq=0, client="grok-build"), 600)
