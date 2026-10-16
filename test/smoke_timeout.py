#!/usr/bin/env python3
"""UI timeout + dialog refusal test. Needs -Dpapyrus.mcp.debugTools=true -Dpapyrus.mcp.uiTimeoutMs=3000."""
import time, threading, mcpclient
from mcpclient import Client, show
a = Client("timeout-test")
show("open modal dialog for 6 s", a.call("debug_open_dialog", ms=6000)); time.sleep(1)
show("write while modal open -> refused", a.call("create_element", metaclass="Class", name="DuringDialog"))
show("read while modal open -> works", a.call("find_elements", query="/^Domain$/"))
time.sleep(6)
show("write after dialog closed -> works", a.call("create_element", metaclass="Class", name="AfterDialog"))
show("undo it", a.call("undo"))
t0 = time.time(); show("block UI 6 s with timeout 3 s -> started-but-unfinished", a.call("debug_block_ui", ms=6000)); print("took %.1fs" % (time.time() - t0))
time.sleep(4)
b = Client("timeout-test-2")
th = threading.Thread(target=lambda: a.call("debug_block_ui", ms=7000)); th.start(); time.sleep(0.5)
t0 = time.time(); show("call while UI blocked -> cancelled, never runs", b.call("create_element", metaclass="Class", name="NeverRuns")); print("took %.1fs" % (time.time() - t0))
th.join(); time.sleep(1)
show("NeverRuns exists?", b.call("find_elements", query="NeverRuns"))
