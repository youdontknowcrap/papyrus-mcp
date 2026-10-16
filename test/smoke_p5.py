#!/usr/bin/env python3
"""Phase 5: GMF diagrams. Creates diagrams, adds elements + relationships, lays out, exports PNGs."""
import base64, json, os, urllib.request, mcpclient
from mcpclient import Client, show
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "p5-images"); os.makedirs(OUT, exist_ok=True)
a = Client("grok-build")
def f(q, mc): return a.call("find_elements", query=q, metaclass=mc)["elements"][0]["id"]
dom = f("/^Domain$/", "Package")
names = ["Production", "Scene", "Beat", "Shot", "Timeline", "Render"]
ids = [f("/^%s$/" % n, "Class") for n in names]

def export(diagram, fname):
    r = a.rpc("tools/call", {"name": "export_diagram_image", "arguments": {"diagram": diagram}})["result"]
    if r["isError"]: print("export error:", r["content"][0]["text"]); return
    img = [c for c in r["content"] if c["type"] == "image"][0]
    open(os.path.join(OUT, fname), "wb").write(base64.b64decode(img["data"]))
    print(f"exported {fname}: {r['structuredContent']['bytes']} bytes, mimeType {img['mimeType']}")

d = a.call("create_diagram", kind="class", owner=dom, name="MCP - Core domain")
show("create_diagram class", d); did = d["id"]
r = a.call("add_to_diagram", diagram=did, elements=ids)
show("add_to_diagram 6 classes + relationships", {"nodes": [(n["name"], n["viewType"]) for n in r["nodes"]], "edges": [(e.get("name"), e["metaclass"], e["viewType"]) for e in r["edges"]], "skipped": r["skipped"]}, 1500)
show("add again (already shown)", a.call("add_to_diagram", diagram=did, elements=ids[:1])["skipped"])
export(did, "1-before-layout.png")
show("layout graphviz LR", a.call("layout_diagram", diagram=did, rankdir="LR"))
export(did, "2-after-layout.png")
show("remove_from_diagram Render", a.call("remove_from_diagram", diagram=did, ids=[ids[-1]]))
show("undo remove", a.call("undo"))
show("list_diagrams (new one)", [x for x in a.call("list_diagrams")["diagrams"] if x["name"].startswith("MCP")])

# Use case diagram: actors + use cases with associations
acts = a.call("find_elements", metaclass="Actor", limit=3)["elements"]
ucs = a.call("find_elements", metaclass="UseCase", limit=6)["elements"]
u = a.call("create_diagram", kind="usecase", name="MCP - Use cases", open=False); show("create_diagram usecase", u)
r = a.call("add_to_diagram", diagram=u["id"], elements=[x["id"] for x in acts + ucs])
show("usecase add", {"nodes": len(r["nodes"]), "edges": len(r["edges"]), "skipped": r["skipped"]})
show("layout usecase grid", a.call("layout_diagram", diagram=u["id"], algorithm="grid"))
export(u["id"], "3-usecase-grid.png")
show("actor on class diagram (should be skipped or shown)", a.call("add_to_diagram", diagram=did, elements=[acts[0]["id"]], withRelationships=False)["skipped"])
a.call("undo")

# Batch: build a mini model and its diagram in ONE undo step
b = a.call("batch", label="Lamp model + diagram", operations=[
    {"tool": "create_element", "arguments": {"container": dom, "metaclass": "Class", "name": "Lamp"}},
    {"tool": "create_element", "arguments": {"container": dom, "metaclass": "Class", "name": "Bulb"}},
    {"tool": "create_relationship", "arguments": {"metaclass": "Association", "source": "$1", "target": "$2", "ends": {"target": {"name": "bulbs", "multiplicity": "1..*", "aggregation": "composite"}}}},
    {"tool": "create_diagram", "arguments": {"kind": "class", "owner": dom, "name": "MCP - Lamp"}},
    {"tool": "add_to_diagram", "arguments": {"diagram": "$4", "elements": ["$1", "$2"]}},
    {"tool": "layout_diagram", "arguments": {"diagram": "$4"}}])
show("batch model+diagram", {"seq": b["seq"], "undoLabel": b["undoLabel"], "edges": b["results"][4]["edges"]}, 800)
export(b["results"][3]["id"], "4-batch-lamp.png")
show("undo batch", a.call("undo"))
show("Lamp diagram gone?", [x["name"] for x in a.call("list_diagrams")["diagrams"] if "Lamp" in x["name"]])
show("save", a.call("save"))
