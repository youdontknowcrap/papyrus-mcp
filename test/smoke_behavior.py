"""Smoke test: sequence (build + fine-grained), activity and state machine tools, validation, PNG export."""
import base64, json, os, sys, mcpclient as m
c = m.Client("smoke-behavior")
OUT = os.path.join(os.path.dirname(__file__), "behavior-images"); os.makedirs(OUT, exist_ok=True)
def png(diagram, name):
    r = c.rpc("tools/call", {"name": "export_diagram_image", "arguments": {"diagram": diagram}})["result"]
    img = [x for x in r["content"] if x["type"] == "image"]
    if not img: print("EXPORT FAILED", r["content"][0]["text"][:300]); return
    open(f"{OUT}/{name}.png", "wb").write(base64.b64decode(img[0]["data"])); print("png", name)
root = c.call("list_open_models")["models"][0]["rootName"]
P = root + "::BehaviorTest"
c.call("create_element", metaclass="Package", name="BehaviorTest")
c.call("create_element", metaclass="Actor", name="Shopper", container=P)
for n in ["Cart Page", "Checkout", "Order", "Payment Gateway", "Receipt"]: c.call("create_element", metaclass="Class", name=n, container=P)
c.call("create_element", metaclass="UseCase", name="Buy Things", container=P)
c.call("set_comment", id=P + "::Buy Things", body="Basic course:\n1. The Shopper presses Buy.\n2. The system charges the card.\nAlternate courses:\n2a. Card declined: show error.")
r = c.call("build_sequence", name="Buy Things - sequence", owner=P + "::Buy Things", narrative=True,
  lifelines=["Shopper", "Cart Page", "Checkout", "Order", "Payment Gateway"],
  steps=[
    {"from": "Shopper", "to": "Cart Page", "msg": "pressBuy()", "steps": [
        {"from": "Cart Page", "to": "Checkout", "msg": "start(cart)", "returns": "order", "steps": [
            {"from": "Checkout", "to": "Order", "msg": "create", "sort": "create"},
            {"fragment": "loop", "guard": "each item", "steps": [{"from": "Checkout", "to": "Order", "msg": "addLine(item)"}]},
            {"from": "Checkout", "to": "Payment Gateway", "msg": "charge(amount)", "returns": "status"},
            {"fragment": "alt", "operands": [
                {"guard": "status = ok", "steps": [{"from": "Checkout", "to": "Order", "msg": "markPaid()"}]},
                {"guard": "else", "steps": [{"from": "Checkout", "to": "Cart Page", "msg": "showError(reason)", "sort": "async"},
                                            {"from": "Checkout", "to": "Order", "msg": "cancel", "sort": "delete"}]}]},
            {"invariant": "order.paid", "on": "Order"}]}]},
    {"from": "gate:notify", "to": "Shopper", "msg": "emailReceipt", "sort": "signal"},
    {"note": "Receipt is sent by e-mail", "on": "Shopper"}])
m.show("build_sequence", r, 1500)
gi = c.call("get_interaction", interaction=r["interaction"]["id"])
print("\n".join(s["text"] for s in gi["sequence"] if s["kind"] == "message"))
print([ (s["kind"], s.get("operator")) for s in gi["sequence"] if s["kind"] != "message"])
png("Buy Things - sequence", "seq-build")
# fine-grained edits then redraw
iid = r["interaction"]["id"]
mm = [x["id"] for x in gi["messages"]]
c2 = c.call("create_interaction", name="Refund", owner=P)
c.call("add_lifeline", interaction=c2["id"], name="Shopper", type=P + "::Shopper")
c.call("add_lifeline", interaction=c2["id"], name="Checkout", type=P + "::Checkout")
a1 = c.call("add_message", interaction=c2["id"], **{"from": "Shopper"}, to="Checkout", msg="requestRefund(order)", execution=True)
a2 = c.call("add_message", interaction=c2["id"], **{"from": "Checkout"}, to="Shopper", msg="refunded", sort="reply")
c.call("add_execution", interaction=c2["id"], lifeline="Checkout", start_message=a1["id"], finish_message=a2["id"])
cf = c.call("add_combined_fragment", interaction=c2["id"], operator="opt", operands=[{"guard": "within 30 days"}], messages=[a1["id"], a2["id"]])
c.call("add_interaction_use", interaction=iid, refers_to=c2["id"], covers=["Shopper", "Checkout"])
c.call("add_state_invariant", interaction=c2["id"], lifeline="Checkout", text="refund.logged")
m.show("create_sequence_diagram Refund", c.call("create_sequence_diagram", interaction=c2["id"]), 600)
m.show("redraw Buy Things", c.call("create_sequence_diagram", interaction=iid, name="Buy Things - sequence"), 600)
png("Refund", "seq-fine"); png("Buy Things - sequence", "seq-build-redrawn")
# activity
ra = c.call("build_activity", name="Checkout Flow", owner=P, partitions=["Shopper", "Shop"],
  nodes=[{"id": "s", "kind": "initial", "partition": "Shopper"}, {"id": "a", "name": "Fill cart", "partition": "Shopper"},
         {"id": "b", "name": "Pay", "partition": "Shopper"}, {"id": "d", "kind": "decision", "partition": "Shop"},
         {"id": "f", "kind": "fork", "partition": "Shop"}, {"id": "e", "name": "Ship", "partition": "Shop"}, {"id": "g", "name": "Send receipt", "partition": "Shop"},
         {"id": "j", "kind": "join", "partition": "Shop"}, {"id": "x", "kind": "final", "partition": "Shop"}, {"id": "w", "kind": "accept", "name": "Card declined", "partition": "Shopper"}],
  flows=[{"from": "s", "to": "a"}, {"from": "a", "to": "b"}, {"from": "b", "to": "d"}, {"from": "d", "to": "f", "guard": "paid"}, {"from": "d", "to": "w", "guard": "declined"},
         {"from": "w", "to": "b"}, {"from": "f", "to": "e"}, {"from": "f", "to": "g"}, {"from": "e", "to": "j"}, {"from": "g", "to": "j"}, {"from": "j", "to": "x"}])
m.show("build_activity", ra, 600); png("Checkout Flow", "activity")
rs = c.call("build_state_machine", name="Order Life", owner=P + "::Order",
  states=[{"name": "start", "kind": "initial"}, {"name": "New", "entry": "reserve stock"},
          {"name": "Paying", "do": "wait for gateway", "states": [{"name": "i", "kind": "initial"}, {"name": "Authorizing"}, {"name": "Capturing"}],
           "transitions": [{"from": "i", "to": "Authorizing"}, {"from": "Authorizing", "to": "Capturing", "trigger": "authorized"}]},
          {"name": "check", "kind": "choice"}, {"name": "Paid", "exit": "release hold"}, {"name": "Cancelled"}, {"name": "end", "kind": "final"}],
  transitions=[{"from": "start", "to": "New"}, {"from": "New", "to": "Paying", "trigger": "checkout"}, {"from": "Paying", "to": "check", "trigger": "done"},
               {"from": "check", "to": "Paid", "guard": "captured"}, {"from": "check", "to": "Cancelled", "guard": "else", "effect": "notify shopper"},
               {"from": "Paid", "to": "end"}, {"from": "Cancelled", "to": "end"}])
m.show("build_state_machine", rs, 600); png("Order Life", "statemachine")
v = c.call("validate", id=P, severity="warning")
m.show("validate BehaviorTest", {"errors": v["errors"], "warnings": v["warnings"]})
for p in v["problems"][:25]: print("  ", p["severity"], p["message"][:220])
m.show("undo build_state_machine (one step)", c.call("undo"), 300)
m.show("redo", c.call("redo"), 300)
m.show("changelog tail", [e["summary"] for e in c.call("get_changelog", limit=50)["entries"][-6:]], 1500)
m.show("save", c.call("save"))
