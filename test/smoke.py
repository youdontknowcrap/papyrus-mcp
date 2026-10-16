#!/usr/bin/env python3
"""Phase-2 read-tool smoke test (port 7342)."""
import sys, mcpclient
from mcpclient import Client, show
if len(sys.argv) > 1: mcpclient.URL = sys.argv[1]
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

