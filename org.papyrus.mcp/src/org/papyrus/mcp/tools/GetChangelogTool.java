package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.Changelog;
import org.papyrus.mcp.util.ModelLocator;

/**
 * MCP mutations by all clients. Without since_seq, returns entries after this session's cursor
 * ("what changed since I last asked") and advances the cursor.
 */
public class GetChangelogTool extends ReadTool {
    public String name() { return "get_changelog"; }
    public String description() {
        return "Changes made through MCP by any client (who, when, what, undo label). Without since_seq, returns "
             + "what is new since this session last called get_changelog. modelVersion also moves on human edits/undo.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "since_seq", "integer", "Return entries with seq > since_seq (0 = all). Omit to use this session's cursor.");
        prop(s, "client", "string", "Optional filter: substring of client label (e.g. 'grok-build').");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        boolean useCursor = !a.has("since_seq");
        long since = useCursor ? s.changelogCursor.getOrDefault(m.uri(), 0L) : a.get("since_seq").getAsLong();
        var entries = Changelog.get().since(m.uri(), since, str(a, "client"));
        JsonArray arr = new JsonArray();
        long max = since;
        for (var e : entries) { arr.add(new Gson().toJsonTree(e)); max = Math.max(max, e.seq()); }
        if (useCursor) s.changelogCursor.put(m.uri(), Math.max(max, Changelog.get().lastSeq()));
        JsonObject out = new JsonObject();
        out.add("entries", arr);
        out.addProperty("cursor", max);
        out.addProperty("you", s.label());
        return out;
    }
}
