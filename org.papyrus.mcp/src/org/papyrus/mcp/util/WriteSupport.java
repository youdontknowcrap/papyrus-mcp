package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.EObject;
import org.papyrus.mcp.server.Session;

/**
 * THE write path. Every mutating tool (and batch) goes through run():
 *   1. refuse if a modal dialog is open in Papyrus;
 *   2. optimistic concurrency: "expected_version" (model-wide) / "expected_etags" {id: etag};
 *   3. execute ONE McpCommand on the editor's command stack (= one Ctrl+Z);
 *   4. append a changelog entry tagged with the calling client.
 * Must run on the UI thread. Capture the Session on the HTTP thread before hopping.
 */
public final class WriteSupport {
    private WriteSupport() {}

    public static class StaleEditException extends Exception {
        public StaleEditException(String m) { super(m); }
    }

    /** Mutable context shared by the operations of one command (one tool call or one batch). */
    public static final class Ctx {
        public final ModelLocator.Active m;
        public final Session session;
        /** Ids of created/changed/deleted elements, for the changelog. */
        public final LinkedHashSet<String> ids = new LinkedHashSet<>();
        public final List<String> summaries = new ArrayList<>();
        Ctx(ModelLocator.Active m, Session s) { this.m = m; this.session = s; }
        public void touched(EObject e) { String id = ElementJson.id(e); if (id != null) ids.add(id); }
    }

    public interface Op { JsonObject apply(Ctx ctx) throws Exception; }

    public static void checkFresh(ModelLocator.Active m, JsonObject args) throws Exception {
        if (args.has("expected_version")) {
            long exp = args.get("expected_version").getAsLong(), cur = m.version();
            if (exp != cur) throw new StaleEditException("stale edit: model version is " + cur + ", expected " + exp
                    + ". Re-read (get_changelog / get_element) and retry.");
        }
        if (args.has("expected_etags")) {
            for (var en : args.getAsJsonObject("expected_etags").entrySet()) {
                EObject e = m.umlResource().getEObject(en.getKey());
                String cur = e == null ? "deleted" : ElementJson.etag(e);
                if (!cur.equals(en.getValue().getAsString()))
                    throw new StaleEditException("stale edit: element " + en.getKey() + " changed (etag now " + cur + ")");
            }
        }
    }

    public static JsonObject run(Session s, ModelLocator.Active m, JsonObject args, String tool, String label, Op op)
            throws Exception {
        if (UiSync.modalDialogOpen())
            throw new IllegalStateException("A dialog is open in Papyrus; close it and retry (edits are refused while a dialog is open).");
        checkFresh(m, args);
        Ctx ctx = new Ctx(m, s);
        McpCommand cmd = new McpCommand(m.domain(), "MCP: " + label, s.id, () -> op.apply(ctx));
        long before = m.version();
        m.domain().getCommandStack().execute(cmd);
        if (cmd.error() != null) throw cmd.error();
        if (cmd.result() == null) throw new IllegalStateException("edit was rolled back by Papyrus (see Error Log)");
        String summary = ctx.summaries.isEmpty() ? label : String.join("; ", ctx.summaries);
        Changelog.Entry e = Changelog.get().record(s, m.uri(), tool, summary, cmd.getLabel(),
                new ArrayList<>(ctx.ids), before, m.version());
        JsonObject out = cmd.result();
        out.addProperty("seq", e.seq());
        out.addProperty("undoLabel", e.undoLabel());
        out.addProperty("model", m.uri());
        out.addProperty("modelVersion", m.version());
        return out;
    }
}
