package org.papyrus.mcp.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Runs work on the SWT UI thread and returns its result to the HTTP thread, WITH A TIMEOUT.
 *
 * We post with asyncExec and wait on a future for at most papyrus.mcp.uiTimeoutMs (default 30 s).
 * - Not started when the timeout hits -> the runnable is cancelled (it will never run) and we report
 *   that Papyrus' UI thread is busy.
 * - Started but not finished -> most likely the operation itself opened a modal dialog. We can't
 *   abort it; we report that it may still complete (check get_changelog / modelVersion).
 * Note: asyncExec runnables also run inside a modal dialog's nested event loop, so a dialog the
 * USER has open does not block reads; write tools refuse instead (see modalDialogOpen()).
 */
public final class UiSync {
    private UiSync() {}

    public static long timeoutMs() { return Long.getLong("papyrus.mcp.uiTimeoutMs", 30_000L); }

    public static <T> T call(Callable<T> c) throws Exception {
        Display d = Display.getDefault();
        if (Display.getCurrent() == d) return c.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean(), started = new AtomicBoolean();
        d.asyncExec(() -> {
            if (cancelled.get()) return;
            started.set(true);
            try { f.complete(c.call()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        try {
            return f.get(timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            cancelled.set(true);
            throw new TimeoutException(started.get()
                    ? "Operation started in Papyrus but did not finish within " + timeoutMs() + " ms (a dialog may be waiting "
                      + "for the user). It may still complete: check get_changelog/modelVersion before retrying."
                    : "Papyrus UI thread is busy; the request was cancelled without running. Try again.");
        } catch (ExecutionException ee) {
            Throwable t = ee.getCause();
            if (t instanceof Exception e) throw e;
            throw new RuntimeException(t);
        }
    }

    /** UI thread only: true if a modal dialog (other than the workbench window) is open. */
    public static boolean modalDialogOpen() {
        for (Shell s : Display.getCurrent().getShells())
            if (s.isVisible() && s.getParent() != null
                    && (s.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0) return true;
        return false;
    }
}
