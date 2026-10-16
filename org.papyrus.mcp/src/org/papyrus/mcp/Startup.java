package org.papyrus.mcp;

import java.io.File;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

/**
 * Activates the bundle at boot (org.eclipse.ui.startup).
 *
 * TEST HOOK: -Dpapyrus.mcp.testOpen=/abs/path/model.di links the .di's folder into the workspace
 * as project "mcp-test" (files stay where they are) and opens the .di in the Papyrus editor.
 * Used for headless testing under Xvfb; does nothing when the property is absent.
 */
public class Startup implements IStartup {
    @Override
    public void earlyStartup() {
        Activator.get();
        String di = System.getProperty("papyrus.mcp.testOpen");
        if (di == null) return;
        Display.getDefault().asyncExec(() -> {
            try {
                File f = new File(di);
                IWorkspace ws = ResourcesPlugin.getWorkspace();
                IProject p = ws.getRoot().getProject("mcp-test");
                if (!p.exists()) {
                    IProjectDescription d = ws.newProjectDescription("mcp-test");
                    d.setLocation(new org.eclipse.core.runtime.Path(f.getParentFile().getAbsolutePath()));
                    p.create(d, new NullProgressMonitor());
                }
                p.open(new NullProgressMonitor());
                p.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                IFile file = p.getFile(f.getName());
                IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file, true);
                Activator.log("test hook opened " + file.getFullPath());
            } catch (Exception e) {
                org.eclipse.core.runtime.Platform.getLog(Startup.class).error("testOpen failed", e);
            }
        });
    }
}
