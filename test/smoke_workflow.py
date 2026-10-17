"""Smoke test for the guided-workflow features: prompts, resources, ICONIX profile, workflow_status."""
import json, mcpclient as m
c = m.Client("smoke-workflow")
init = c.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "x"}})["result"]
m.show("capabilities", init["capabilities"])
pl = c.rpc("prompts/list")["result"]["prompts"]
m.show("prompts/list names", [p["name"] for p in pl])
g = c.rpc("prompts/get", {"name": "iconix_robustness", "arguments": {"use_case": "Publish a Show"}})["result"]
m.show("prompts/get iconix_robustness", g["messages"][0]["content"]["text"], 300)
m.show("prompts/get missing arg", c.rpc("prompts/get", {"name": "iconix_review"}).get("error"))
m.show("resources/list", [r["uri"] for r in c.rpc("resources/list")["result"]["resources"]])
m.show("resources/read workflow://iconix", c.rpc("resources/read", {"uri": "workflow://iconix"})["result"]["contents"][0]["text"], 200)
m.show("workflow_status (empty model)", c.call("workflow_status"), 1500)
m.show("resources/read workflow://status", c.rpc("resources/read", {"uri": "workflow://status"})["result"]["contents"][0]["text"], 200)

# ---- on a real model: ICONIX profile alias, a robustness package, and the rule checker
uc = c.call("find_elements", metaclass="UseCase", limit=1)
m.show("find a use case", uc, 300)
ucid = (uc.get("elements") or uc.get("results"))[0]["id"]
m.show("apply_profile ICONIX", c.call("apply_profile", profile="ICONIX"), 300)
pk = c.call("create_element", metaclass="Package", name="Robustness - smoke")
pkid = pk["element"]["id"] if "element" in pk else pk["id"]
c.call("create_relationship", metaclass="Dependency", source=pkid, target=ucid)
ids = {}
for n, st in [("Smoke Page", "Boundary"), ("Smoke Logic", "Control"), ("Smoke Thing", None)]:
    r = c.call("create_element", metaclass="Class", name=n, container=pkid if st else None) if st else c.call("create_element", metaclass="Class", name=n)
    ids[n] = r["element"]["id"] if "element" in r else r["id"]
    if st: c.call("apply_stereotype", id=ids[n], stereotype=st)
c.call("apply_stereotype", id=ids["Smoke Thing"], stereotype="Entity")
c.call("create_relationship", metaclass="Dependency", source=ids["Smoke Page"], target=ids["Smoke Logic"])
c.call("create_relationship", metaclass="Dependency", source=ids["Smoke Page"], target=ids["Smoke Thing"])   # violation
s = c.call("workflow_status")
m.show("workflow_status on MudHut test model (expect 1 violation)", {"stages": s["stages"], "violations": s["findings"]["robustnessRuleViolations"],
       "noNarrative": len(s["findings"]["useCasesWithoutNarrative"]), "counts": s["counts"]}, 1800)
m.show("missing required arg", c.call("update_element", id=ids["Smoke Thing"]), 300)
