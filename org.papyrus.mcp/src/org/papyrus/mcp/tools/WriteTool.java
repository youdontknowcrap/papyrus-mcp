package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;

/**
 * Base for model-editing tools. Subclasses implement apply() (runs INSIDE the write transaction)
 * and label(). execute() wraps it in WriteSupport.run (dialog check, staleness check, one undo
 * step, changelog). batch calls apply() of several tools inside one shared command.
 */
public abstract class WriteTool implements Tool {
    /** Runs inside the McpCommand transaction. Must only touch the model; no dialogs. */
    public abstract JsonObject apply(WriteSupport.Ctx ctx, JsonObject args) throws Exception;
    /**
     * Short human label: becomes "MCP: <label>" in Papyrus' Edit > Undo menu. Computed on the UI
     * thread BEFORE executing (Papyrus' operation history captures the label at execute time), so
     * use n(m, args, key) to show element names instead of ids.
     */
    public abstract String label(ModelLocator.Active m, JsonObject args);

    /** Name (or qualified-name tail) of the element referenced by args[key]; falls back to the raw value. */
    protected static String n(ModelLocator.Active m, JsonObject a, String key) {
        String v = str(a, key);
        if (v == null) return "?";
        try {
            var e = ElementJson.resolve(m, v);
            if (e instanceof org.eclipse.uml2.uml.NamedElement ne && ne.getName() != null) return ne.getName();
            return e.eClass().getName();
        } catch (Exception ex) { return v; }
    }
    /** Tool-specific schema; common args (model, expected_version, expected_etags) are added for you. */
    protected abstract JsonObject schema();

    @Override public boolean mutating() { return true; }

    @Override public final JsonObject inputSchema() {
        JsonObject s = schema();
        ReadTool.prop(s, "model", "string", "Optional model selector (see list_open_models).");
        ReadTool.prop(s, "expected_version", "integer", "Optional: fail if modelVersion changed since you read it.");
        ReadTool.prop(s, "expected_etags", "object", "Optional {elementId: etag}: fail if any of these elements changed.");
        return s;
    }

    @Override public JsonObject execute(JsonObject args) throws Exception {
        Session s = Session.current();
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(ReadTool.str(args, "model"));
            return WriteSupport.run(s, m, args, name(), label(m, args), ctx -> apply(ctx, args));
        });
    }

    // ---- small helpers for subclasses ----
    protected static JsonObject schema(String[][] props, String... required) {
        JsonObject s = ToolRegistry.schema();
        for (String[] p : props) ReadTool.prop(s, p[0], p[1], p[2]);
        if (required.length > 0) ReadTool.require(s, required);
        return s;
    }
    protected static String str(JsonObject a, String k) { return ReadTool.str(a, k); }
    protected static String need(JsonObject a, String k) {
        String v = str(a, k);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing argument: " + k);
        return v;
    }
}
