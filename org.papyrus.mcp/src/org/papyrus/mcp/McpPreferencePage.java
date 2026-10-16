package org.papyrus.mcp;

import org.eclipse.jface.preference.*;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Window > Preferences > Papyrus MCP. Changes apply after restart (v1 keeps it simple). */
public class McpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public McpPreferencePage() { super(GRID); }
    @Override public void init(IWorkbench wb) {
        setPreferenceStore(Activator.get().getPreferenceStore());
        setDescription("MCP server on 127.0.0.1 (Streamable HTTP, POST /mcp). Restart Papyrus after changes.");
    }
    @Override protected void createFieldEditors() {
        addField(new BooleanFieldEditor(Prefs.ENABLED, "Enable MCP server", getFieldEditorParent()));
        IntegerFieldEditor port = new IntegerFieldEditor(Prefs.PORT, "Port", getFieldEditorParent());
        port.setValidRange(1024, 65535);
        addField(port);
        addField(new StringFieldEditor(Prefs.TOKEN, "Bearer token (empty = none)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Prefs.READ_ONLY, "Read-only (refuse edit tools)", getFieldEditorParent()));
    }
}
