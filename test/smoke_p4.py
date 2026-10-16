#!/usr/bin/env python3
"""Phase 4: profiles, stereotypes, validation, and undo labels with element names."""
import os, mcpclient
from mcpclient import Client, show
PROFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "McpTest.profile.uml")
a = Client("grok-build")
scene = a.call("find_elements", query="/^Scene$/", metaclass="Class")["elements"][0]["id"]
show("apply_profile Standard (alias)", a.call("apply_profile", profile="Standard"))
show("apply_profile McpTest (file path)", a.call("apply_profile", profile=PROFILE))
show("get_stereotypes (model)", [ (p["name"], len(p["stereotypes"])) for p in a.call("get_stereotypes")["appliedProfiles"]])
show("applicable on Scene", a.call("get_stereotypes", id=scene)["applicable"])
show("apply «Entity» with tags", a.call("apply_stereotype", id=scene, stereotype="Entity",
     values={"table": "scenes", "version": 3, "persistence": "Transient"}), 1200)
show("apply «Auxiliary» (Standard)", a.call("apply_stereotype", id=scene, stereotype="StandardProfile::Auxiliary"))
show("update tags only", a.call("apply_stereotype", id=scene, stereotype="Entity", values={"version": 4}))
show("bad tag", a.call("apply_stereotype", id=scene, stereotype="Entity", values={"nope": 1}))
show("bad enum literal", a.call("apply_stereotype", id=scene, stereotype="Entity", values={"persistence": "Frozen"}))
show("get_element stereotypes", a.call("get_element", id=scene, depth=0, include=["stereotypes"])["element"]["stereotypes"])
show("unapply «Auxiliary»", a.call("unapply_stereotype", id=scene, stereotype="Auxiliary")["stereotypes"])
show("undo -> Auxiliary back", a.call("undo"))
show("find_elements stereotype=Entity", a.call("find_elements", stereotype="Entity"))
show("validate (warnings+)", a.call("validate", limit=5), 1500)
# Make an error: property with lower > upper
p = a.call("add_property", classifier=scene, name="broken", multiplicity="1")
a.call("update_element", id=p["id"], set={"lower": 5, "upper": "2"})
v = a.call("validate", id=scene, severity="warning")
show("validate Scene after lower>upper", v, 1200)
show("undo label uses names", [x.get("undoLabel") for x in a.call("get_changelog", since_seq=0)["entries"]][-8:], 1500)
