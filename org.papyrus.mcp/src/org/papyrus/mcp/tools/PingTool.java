package org.papyrus.mcp.tools;

import com.google.gson.JsonObject;
import org.papyrus.mcp.util.ModelLocator;
import org.papyrus.mcp.util.UiSync;

/** Health check: server alive + whether a Papyrus model is open. */
public class PingTool implements Tool {
    public String name() { return "ping"; }
    public String description() { return "Check the Papyrus MCP server is alive and report the active model, if any."; }
    public JsonObject inputSchema() { return ToolRegistry.schema(); }
    public JsonObject execute(JsonObject args) throws Exception {
        return UiSync.call(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("pong", true);
            ModelLocator.Active m = ModelLocator.findActive();
            o.addProperty("modelOpen", m != null);
            if (m != null) {
                o.addProperty("model", m.root().getName());
                o.addProperty("resource", m.root().eResource().getURI().toString());
                o.addProperty("dirty", m.editor().isDirty());
            }
            return o;
        });
    }
}
