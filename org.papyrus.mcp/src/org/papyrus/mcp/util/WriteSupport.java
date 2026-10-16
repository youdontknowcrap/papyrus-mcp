package org.papyrus.mcp.util;

import com.google.gson.JsonObject;
import java.util.List;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.ecore.EObject;
import org.papyrus.mcp.server.Session;

/**
 * Shared path for EVERY mutating tool (phase 3 tools use this; nothing else may touch the stack):
 *   1. optimistic concurrency: optional args "expected_version" (model-wide) and
 *      "expected_etags" {elementId: etag} (per element) -> StaleEditException if changed;
 *   2. execute exactly one command on the editor's command stack (= one Ctrl+Z);
 *   3. append a changelog entry tagged with the calling client.
 * Must run on the UI thread (inside UiSync.call). Capture the Session BEFORE hopping threads.
 */
public final class WriteSupport {
    private WriteSupport() {}

    public static class StaleEditException extends Exception {
        public StaleEditException(String m) { super(m); }
    }

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
                    throw new StaleEditException("stale edit: element " + en.getKey() + " changed (etag " + cur + ")");
            }
        }
    }

    public static Changelog.Entry execute(Session s, ModelLocator.Active m, JsonObject args, String tool,
                                          Command cmd, String summary, List<String> ids) throws Exception {
        checkFresh(m, args);
        if (cmd == null || !cmd.canExecute()) throw new IllegalStateException("command not executable: " + summary);
        long before = m.version();
        m.domain().getCommandStack().execute(cmd);
        return Changelog.get().record(s, m.uri(), tool, summary, cmd.getLabel(), ids, before, m.version());
    }
}
