package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.command.CommandStack;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;

/**
 * undo / redo on the editor's shared command stack (same as Ctrl+Z / Ctrl+Y in Papyrus).
 * scope "session" (default): only if the top command is an MCP edit made by THIS session;
 * scope "mcp": any MCP edit; force=true: anything, including the user's own edits.
 */
class UndoRedoTool implements Tool {
    private final boolean undo;
    UndoRedoTool(boolean undo) { this.undo = undo; }
    public String name() { return undo ? "undo" : "redo"; }
    public String description() {
        return (undo ? "Undo" : "Redo") + " the last edit on the model's command stack. By default only if it is this "
             + "session's own MCP edit; scope='mcp' allows any bot's edit; force=true allows anything (incl. the user's).";
    }
    public boolean mutating() { return true; }
    public JsonObject inputSchema() {
        JsonObject s = ToolRegistry.schema();
        ReadTool.prop(s, "scope", "string", "session (default) | mcp");
        ReadTool.prop(s, "force", "boolean", "Allow undoing/redoing non-MCP (user) edits. Default false.");
        ReadTool.prop(s, "model", "string", "Optional model selector.");
        return s;
    }
    public JsonObject execute(JsonObject a) throws Exception {
        Session s = Session.current();
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(ReadTool.str(a, "model"));
            if (UiSync.modalDialogOpen()) throw new IllegalStateException("A dialog is open in Papyrus; close it and retry.");
            CommandStack cs = m.domain().getCommandStack();
            Command top = undo ? cs.getUndoCommand() : cs.getRedoCommand();
            if (top == null || !(undo ? cs.canUndo() : cs.canRedo())) throw new IllegalStateException("nothing to " + name());
            boolean force = a.has("force") && a.get("force").getAsBoolean();
            String scope = a.has("scope") ? a.get("scope").getAsString() : "session";
            if (!force) {
                if (!(top instanceof McpCommand mc))
                    throw new IllegalStateException("top of stack is not an MCP edit ('" + top.getLabel() + "'); pass force=true to " + name() + " it");
                if (scope.equals("session") && !mc.sessionId.equals(s.id))
                    throw new IllegalStateException("top of stack '" + top.getLabel() + "' was made by another client; use scope='mcp'");
            }
            String label = top.getLabel();
            long before = m.version();
            if (undo) cs.undo(); else cs.redo();
            Changelog.Entry e = Changelog.get().record(s, m.uri(), name(), name() + " '" + label + "'", label,
                    java.util.List.of(), before, m.version());
            JsonObject o = new JsonObject();
            o.addProperty(undo ? "undone" : "redone", label);
            o.addProperty("seq", e.seq());
            o.addProperty("modelVersion", m.version());
            return o;
        });
    }
}

class SaveTool implements Tool {
    public String name() { return "save"; }
    public String description() { return "Save the open Papyrus model (same as Ctrl+S in its editor)."; }
    public boolean mutating() { return true; }
    public JsonObject inputSchema() {
        JsonObject s = ToolRegistry.schema();
        ReadTool.prop(s, "model", "string", "Optional model selector.");
        return s;
    }
    public JsonObject execute(JsonObject a) throws Exception {
        Session s = Session.current();
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(ReadTool.str(a, "model"));
            if (UiSync.modalDialogOpen()) throw new IllegalStateException("A dialog is open in Papyrus; close it and retry.");
            boolean wasDirty = m.editor().isDirty();
            m.editor().doSave(new org.eclipse.core.runtime.NullProgressMonitor());
            Changelog.get().record(s, m.uri(), name(), "saved", null, java.util.List.of(), m.version(), m.version());
            JsonObject o = new JsonObject();
            o.addProperty("saved", wasDirty);
            o.addProperty("dirty", m.editor().isDirty());
            o.addProperty("model", m.uri());
            return o;
        });
    }
}
