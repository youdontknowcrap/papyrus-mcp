package org.papyrus.mcp;

import org.eclipse.ui.IStartup;

/** Exists only so org.eclipse.ui.startup activates the bundle (lazy activation) at boot. */
public class Startup implements IStartup {
    @Override public void earlyStartup() { Activator.get(); }
}
