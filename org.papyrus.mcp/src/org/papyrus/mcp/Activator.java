package org.papyrus.mcp;

import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.papyrus.mcp.server.McpHttpServer;
import org.papyrus.mcp.tools.ToolRegistry;

/**
 * Bundle lifecycle. Starts the MCP HTTP server on start(), stops it on stop().
 * Port/token/enabled come from the preference store (see McpPreferenceInitializer).
 * System properties -Dpapyrus.mcp.port / -Dpapyrus.mcp.token (set in papyrus-desktop.ini
 * after -vmargs) override preferences, handy for headless testing.
 */
public class Activator extends AbstractUIPlugin {
    public static final String ID = "org.papyrus.mcp";
    private static Activator instance;
    private McpHttpServer server;

    public static Activator get() { return instance; }

    @Override
    public void start(BundleContext ctx) throws Exception {
        super.start(ctx);
        instance = this;
        if (!getPreferenceStore().getBoolean(Prefs.ENABLED)) return;
        int port = Integer.getInteger("papyrus.mcp.port", getPreferenceStore().getInt(Prefs.PORT));
        String token = System.getProperty("papyrus.mcp.token", getPreferenceStore().getString(Prefs.TOKEN));
        org.papyrus.mcp.util.Changelog.get().setSidecar(Boolean.parseBoolean(System.getProperty(
                "papyrus.mcp.changelogSidecar", String.valueOf(getPreferenceStore().getBoolean(Prefs.CHANGELOG_SIDECAR)))));
        server = new McpHttpServer(port, token, ToolRegistry.createDefault());
        try {
            server.start();
            log("Papyrus MCP listening on http://127.0.0.1:" + port + "/mcp");
        } catch (Exception e) {
            // Port in use etc. must never break Papyrus startup: log and carry on.
            Platform.getLog(getBundle()).error("Papyrus MCP failed to start", e);
            server = null;
        }
    }

    @Override
    public void stop(BundleContext ctx) throws Exception {
        if (server != null) server.stop();
        instance = null;
        super.stop(ctx);
    }

    public static void log(String msg) {
        if (instance != null) Platform.getLog(instance.getBundle()).info(msg);
    }
}
