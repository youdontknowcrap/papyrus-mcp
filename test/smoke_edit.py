#!/usr/bin/env python3
"""Phase-3 edit-tool test against the running test Papyrus (port 7342)."""
import sys, json, mcpclient
from mcpclient import Client, show
if len(sys.argv) > 1: mcpclient.URL = sys.argv[1]
a, b = Client("grok-build"), Client("grok-bot")
dom = a.call("find_elements", query="/^Domain$/", metaclass="Package")["elements"][0]["id"]
scene = a.call("find_elements", query="/^Scene$/", metaclass="Class")["elements"][0]["id"]
v0 = a.call("ping")
show("start", v0)

c = a.call("create_element", container=dom, metaclass="Class", name="McpProbe", properties={"isAbstract": True})
show("create_element Class", c); probe = c["id"]
show("add_property", a.call("add_property", classifier=probe, name="count", type="PrimitiveTypes::Integer", multiplicity="0..1", visibility="private"))
show("add_operation", a.call("add_operation", classifier=probe, name="ping", params=[{"name": "msg", "type": "PrimitiveTypes::String"}], returnType="PrimitiveTypes::Boolean"))
e = a.call("create_element", container=dom, metaclass="Enumeration", name="McpColor"); show("create Enumeration", e)
show("add_enumeration_literal", a.call("add_enumeration_literal", enumeration=e["id"], name="RED"))
show("create_relationship Association", a.call("create_relationship", metaclass="Association", source=probe, target=scene,
     ends={"target": {"name": "scenes", "multiplicity": "0..*"}, "source": {"aggregation": "composite"}}))
show("create_relationship Generalization", a.call("create_relationship", metaclass="Generalization", source=scene, target=probe))
show("create_relationship Dependency", a.call("create_relationship", metaclass="Dependency", source=probe, target=e["id"], name="uses"))
show("update_element", a.call("update_element", id=probe, set={"name": "McpProbe2", "visibility": "package"}))
show("set_comment", a.call("set_comment", id=probe, body="Created by the MCP edit test."))
pk = a.call("create_element", container=dom, metaclass="Package", name="McpSub")
show("move_element", a.call("move_element", id=e["id"], newContainer=pk["id"]))
show("get_element after edits", a.call("get_element", id=probe, depth=1), 1500)

# stale + undo ownership
el = a.call("get_element", id=probe, depth=0, include=[])
show("B update with stale etag", b.call("update_element", id=probe, set={"name": "X"}, expected_etags={probe: "000000000000"}))
show("B update with stale version", b.call("update_element", id=probe, set={"name": "X"}, expected_version=el["modelVersion"] - 1))
show("B undo (top is A's edit -> refused)", b.call("undo"))
show("A undo own (undo move)", a.call("undo"))
show("A redo", a.call("redo"))

# delete dry-run + real, then undo the delete
show("delete dry_run Scene", a.call("delete_elements", ids=[scene], dry_run=True), 900)
show("delete probe", a.call("delete_elements", ids=[probe]))
show("find probe after delete", a.call("find_elements", query="McpProbe2"))
show("A undo delete", a.call("undo"))
show("find probe after undo", a.call("find_elements", query="McpProbe2"))

# batch with $refs, then one undo removes all
bt = b.call("batch", label="add Lamp model", operations=[
    {"tool": "create_element", "arguments": {"container": dom, "metaclass": "Class", "name": "Lamp"}},
    {"tool": "add_property", "arguments": {"classifier": "$1", "name": "watts", "type": "PrimitiveTypes::Integer"}},
    {"tool": "create_relationship", "arguments": {"metaclass": "Association", "source": scene, "target": "$1"}}])
show("batch (3 ops)", bt, 900)
show("batch failing op 2 (all rolled back)", b.call("batch", operations=[
    {"tool": "create_element", "arguments": {"container": dom, "metaclass": "Class", "name": "Ghost"}},
    {"tool": "add_property", "arguments": {"classifier": "nope", "name": "x"}}]))
show("Ghost exists?", a.call("find_elements", query="Ghost"))
show("B undo batch (one step)", b.call("undo"))
show("Lamp exists?", a.call("find_elements", query="/^Lamp$/"))
show("changelog (A cursor)", [ (x["seq"], x["client"].split('#')[0], x["tool"], x["summary"][:60]) for x in a.call("get_changelog")["entries"]], 3000)
show("save", a.call("save"))
show("ping after save", a.call("ping"))
