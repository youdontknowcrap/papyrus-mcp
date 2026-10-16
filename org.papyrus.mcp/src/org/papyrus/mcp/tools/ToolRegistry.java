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
        // Phase 3: edit tools. All go through util.WriteSupport (dialog check, staleness check,
        // one undo step, changelog entry).
        r.add(new CreateElementTool());
        r.add(new CreateRelationshipTool());
        r.add(new AddPropertyTool());
        r.add(new AddOperationTool());
        r.add(new AddEnumerationLiteralTool());
        r.add(new UpdateElementTool());
        r.add(new SetCommentTool());
        r.add(new MoveElementTool());
        r.add(new DeleteElementsTool());
        r.add(new BatchTool(r));
        r.add(new UndoRedoTool(true));
        r.add(new UndoRedoTool(false));
        r.add(new SaveTool());
        // Phase 4: profiles, stereotypes, validation
        r.add(new ApplyProfileTool());
        r.add(new ApplyStereotypeTool());
        r.add(new UnapplyStereotypeTool());
        r.add(new ValidateTool());
        // Phase 5: GMF diagrams
        r.add(new CreateDiagramTool());
        r.add(new AddToDiagramTool());
        r.add(new RemoveFromDiagramTool());
        r.add(new LayoutDiagramTool());
        r.add(new ExportDiagramImageTool());
        if (Boolean.getBoolean("papyrus.mcp.debugTools")) { // test-only, see DebugTools.java
            r.add(new DebugBlockUiTool());
            r.add(new DebugOpenDialogTool());
        }
        return r;
    }

    public void add(Tool t) { tools.put(t.name(), t); }

    public Tool get(String name) { return tools.get(name); }

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
            // "_image" {data, mimeType} becomes an MCP image content item (kept out of structuredContent).
            JsonElement img = data.remove("_image");
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", data.toString());
            content.add(text);
            if (img != null && img.isJsonObject()) {
                JsonObject ic = img.getAsJsonObject().deepCopy();
                ic.addProperty("type", "image");
                content.add(ic);
            }
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
