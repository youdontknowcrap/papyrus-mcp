package org.papyrus.mcp.tools;

import com.google.gson.JsonObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.papyrus.mcp.util.UiSync;

/*
 * TEST-ONLY tools, registered only with -Dpapyrus.mcp.debugTools=true. They exist to exercise the
 * UI-thread timeout and the "dialog open" refusal in headless tests. Never enable in normal use.
 */

/** Blocks the UI thread for ms milliseconds (simulates an operation stuck in a dialog). */
class DebugBlockUiTool implements Tool {
    public String name() { return "debug_block_ui"; }
    public String description() { return "TEST ONLY: block the Papyrus UI thread for 'ms' milliseconds."; }
    public JsonObject inputSchema() { return ToolRegistry.schema(); }
    public JsonObject execute(JsonObject a) throws Exception {
        long ms = a.has("ms") ? a.get("ms").getAsLong() : 5000;
        return UiSync.call(() -> { Thread.sleep(ms); JsonObject o = new JsonObject(); o.addProperty("slept", ms); return o; });
    }
}

/** Opens (asynchronously) a modal message dialog in Papyrus; closes it after 'ms'. */
class DebugOpenDialogTool implements Tool {
    public String name() { return "debug_open_dialog"; }
    public String description() { return "TEST ONLY: open a modal dialog in Papyrus for 'ms' milliseconds."; }
    public JsonObject inputSchema() { return ToolRegistry.schema(); }
    public JsonObject execute(JsonObject a) throws Exception {
        int ms = a.has("ms") ? a.get("ms").getAsInt() : 8000;
        Display d = Display.getDefault();
        d.asyncExec(() -> {
            MessageDialog dlg = new MessageDialog(d.getActiveShell(), "MCP test", null, "Modal test dialog", MessageDialog.INFORMATION, 0, "OK");
            d.timerExec(ms, () -> dlg.close());
            dlg.open(); // blocks in a nested event loop until closed
        });
        JsonObject o = new JsonObject();
        o.addProperty("opened", true);
        return o;
    }
}
