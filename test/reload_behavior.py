"""Second half of the save + reopen check: run after smoke_behavior.py saved the model and
Papyrus was relaunched with RESET=0. Reads the interaction back, validates and re-exports."""
import base64, os, mcpclient as m
c = m.Client("reload-behavior")
root = c.call("list_open_models")["models"][0]["rootName"]
P = root + "::BehaviorTest"
i = c.call("get_interaction", interaction=P + "::Buy Things::Buy Things - sequence")
m.show("get_interaction after reopen", {"lifelines": len(i.get("lifelines", [])), "messages": len(i.get("messages", []))})
v = c.call("validate", id=P, severity="warning")
m.show("validate after reopen", {"errors": v["errors"], "warnings": v["warnings"]})
OUT = os.path.join(os.path.dirname(__file__), "behavior-images")
for d, n in [("Buy Things - sequence", "seq-reopened"), ("Checkout Flow", "activity-reopened"), ("Order Life", "statemachine-reopened")]:
    r = c.rpc("tools/call", {"name": "export_diagram_image", "arguments": {"diagram": d}})["result"]
    img = [x for x in r["content"] if x["type"] == "image"]
    print("png", n, "OK" if img else r["content"][0]["text"][:200])
    if img: open(f"{OUT}/{n}.png", "wb").write(base64.b64decode(img[0]["data"]))
