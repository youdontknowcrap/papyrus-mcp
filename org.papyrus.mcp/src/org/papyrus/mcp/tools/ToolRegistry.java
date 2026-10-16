package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.papyrus.mcp.Activator;
import org.papyrus.mcp.Prefs;

/** Holds tools, renders tools/list, and adapts results to the MCP CallToolResult shape. */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public static ToolRegistry createDefault() {
        ToolRegistry r = new ToolRegistry();
        r.add(new PingTool());
        r.add(new ListOpenModelsTool());
        r.add(new ListPackagesTool());
        r.add(new GetElementTool());
        r.add(new ListElementsTool());
        r.add(new FindElementsTool());
        r.add(new GetRelationshipsTool());
        r.add(new GetStereotypesTool());
        r.add(new ListDiagramsTool());
        r.add(new GetSelectionTool());
        r.add(new GetChangelogTool());
        if (Boolean.getBoolean("papyrus.mcp.debugTools")) r.add(new DebugRenameTool()); // test-only
        // Phase 3: write tools go through util.WriteSupport (version check + changelog). See DESIGN.md.
        return r;
    }

    public void add(Tool t) { tools.put(t.name(), t); }

    public JsonArray describeAll() {
        JsonArray a = new JsonArray();
        for (Tool t : tools.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.inputSchema());
            JsonObject ann = new JsonObject();
            ann.addProperty("readOnlyHint", !t.mutating());
            o.add("annotations", ann);
            a.add(o);
        }
        return a;
    }

    /** Tool failures are reported as isError results (MCP convention), not JSON-RPC errors. */
    public JsonObject call(String name, JsonObject args) {
        Tool t = tools.get(name);
        if (t == null) return errorResult("unknown tool: " + name);
        Activator a = Activator.get();
        if (t.mutating() && a != null && a.getPreferenceStore().getBoolean(Prefs.READ_ONLY))
            return errorResult("server is in read-only mode");
        try {
            JsonObject data = t.execute(args);
            JsonObject res = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", data.toString());
            content.add(text);
            res.add("content", content);
            res.add("structuredContent", data);
            res.addProperty("isError", false);
            return res;
        } catch (Exception e) {
            return errorResult(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonObject errorResult(String msg) {
        JsonObject res = new JsonObject(), text = new JsonObject();
        JsonArray content = new JsonArray();
        text.addProperty("type", "text");
        text.addProperty("text", msg);
        content.add(text);
        res.add("content", content);
        res.addProperty("isError", true);
        return res;
    }

    static JsonObject schema(String... requiredStringProps) {
        JsonObject s = new JsonObject(), props = new JsonObject();
        JsonArray req = new JsonArray();
        s.addProperty("type", "object");
        for (String p : requiredStringProps) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "string");
            props.add(p, t);
            req.add(p);
        }
        s.add("properties", props);
        if (!req.isEmpty()) s.add("required", req);
        return s;
    }
}
