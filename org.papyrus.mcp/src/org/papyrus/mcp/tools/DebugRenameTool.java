package org.papyrus.mcp.tools;

import com.google.gson.JsonObject;
import java.util.List;
import org.eclipse.emf.edit.command.SetCommand;
import org.eclipse.uml2.uml.NamedElement;
import org.eclipse.uml2.uml.UMLPackage;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;

/**
 * TEST-ONLY (enabled by -Dpapyrus.mcp.debugTools=true): renames an element through
 * WriteSupport to exercise the command stack, undo, stale-edit checks and the changelog
 * before the real phase-3 tools (which will use the Papyrus edit service) exist.
 */
public class DebugRenameTool implements Tool {
    public String name() { return "debug_rename"; }
    public String description() { return "TEST ONLY: rename an element (undoable, changelogged). Args: id, name, expected_version?, expected_etags?"; }
    public boolean mutating() { return true; }
    public JsonObject inputSchema() { return ToolRegistry.schema("id", "name"); }
    public JsonObject execute(JsonObject a) throws Exception {
        Session s = Session.current();
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(a.has("model") ? a.get("model").getAsString() : null);
            NamedElement e = (NamedElement) ElementJson.resolve(m, a.get("id").getAsString());
            String old = e.getName(), nu = a.get("name").getAsString();
            var cmd = SetCommand.create(m.domain(), e, UMLPackage.Literals.NAMED_ELEMENT__NAME, nu);
            if (cmd instanceof org.eclipse.emf.common.command.AbstractCommand ac) ac.setLabel("MCP: rename " + old + " -> " + nu);
            Changelog.Entry en = WriteSupport.execute(s, m, a, name(), cmd, "rename '" + old + "' to '" + nu + "'",
                    List.of(ElementJson.id(e)));
            JsonObject o = new JsonObject();
            o.addProperty("seq", en.seq());
            o.addProperty("undoLabel", en.undoLabel());
            o.addProperty("modelVersion", m.version());
            o.addProperty("etag", ElementJson.etag(e));
            return o;
        });
    }
}
