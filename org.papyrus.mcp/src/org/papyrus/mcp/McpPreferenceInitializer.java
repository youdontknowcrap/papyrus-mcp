package org.papyrus.mcp;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

public class McpPreferenceInitializer extends AbstractPreferenceInitializer {
    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore s = Activator.get().getPreferenceStore();
        s.setDefault(Prefs.ENABLED, true);
        s.setDefault(Prefs.PORT, 7341);
        s.setDefault(Prefs.TOKEN, "");
        s.setDefault(Prefs.READ_ONLY, false);
    }
}
