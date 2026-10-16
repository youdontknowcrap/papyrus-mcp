package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.papyrus.mcp.util.ModelLocator;
import org.papyrus.mcp.util.UiSync;

/** All Papyrus editors, including not-yet-restored tabs (which can't be queried until clicked). */
public class ListOpenModelsTool implements Tool {
    public String name() { return "list_open_models"; }
    public String description() { return "List open Papyrus models (editor tabs) with URI, version, dirty and active flags. Use 'model' in other tools to pick one."; }
    public JsonObject inputSchema() { return ToolRegistry.schema(); }
    public JsonObject execute(JsonObject args) throws Exception {
        return UiSync.call(() -> {
            JsonArray arr = new JsonArray();
            IEditorPart active = PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null ? null
                    : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
            for (IEditorReference r : ModelLocator.papyrusEditorRefs()) {
                JsonObject o = new JsonObject();
                o.addProperty("title", r.getTitle());
                IEditorPart ed = r.getEditor(false);
                o.addProperty("restored", ed != null);
                if (ed instanceof org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor) {
                    ModelLocator.Active m = ModelLocator.find(r.getTitle());
                    if (m != null) {
                        o.addProperty("model", m.uri());
                        o.addProperty("rootName", m.root().getName());
                        o.addProperty("modelVersion", m.version());
                    }
                    o.addProperty("dirty", ed.isDirty());
                    o.addProperty("active", ed == active);
                }
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.add("models", arr);
            return out;
        });
    }
}
