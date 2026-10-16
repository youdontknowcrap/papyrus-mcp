package org.papyrus.mcp.util;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.widgets.Display;

/**
 * Runs work on the SWT UI thread and returns its result to the HTTP thread.
 * All editor/workbench access MUST happen here; command execution also goes here so
 * GMF edit parts refresh synchronously and the undo history stays consistent.
 * v1 has no timeout: a modal dialog in Papyrus will block the call (see DESIGN.md risks).
 */
public final class UiSync {
    private UiSync() {}
    public static <T> T call(Callable<T> c) throws Exception {
        Display d = Display.getDefault();
        if (Display.getCurrent() == d) return c.call();
        AtomicReference<T> res = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        d.syncExec(() -> {
            try { res.set(c.call()); } catch (Exception e) { err.set(e); }
        });
        if (err.get() != null) throw err.get();
        return res.get();
    }
}
