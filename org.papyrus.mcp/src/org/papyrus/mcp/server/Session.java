package org.papyrus.mcp.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * One MCP client session, created by "initialize". Holds the client identity (clientInfo
 * name/version from initialize) used to tag changelog entries, and per-model changelog cursors.
 */
public final class Session {
    public final String id;
    public final String clientName;
    public final String clientVersion;
    public final long createdMillis = System.currentTimeMillis();
    /** model URI -> last changelog seq returned to this session by get_changelog. */
    public final Map<String, Long> changelogCursor = new ConcurrentHashMap<>();

    public Session(String id, String clientName, String clientVersion) {
        this.id = id;
        this.clientName = clientName == null ? "unknown" : clientName;
        this.clientVersion = clientVersion == null ? "" : clientVersion;
    }

    /** "name/version#shortSession", e.g. "grok-build/1.2#3f9a1c2b". */
    public String label() {
        return clientName + (clientVersion.isEmpty() ? "" : "/" + clientVersion) + "#" + id.substring(0, 8);
    }

    // ---- current-request context (set by McpHttpServer around each tools/call) ----
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final Session ANON = new Session("00000000-anon", "anonymous", "");
    public static Session current() { Session s = CURRENT.get(); return s == null ? ANON : s; }
    static void setCurrent(Session s) { if (s == null) CURRENT.remove(); else CURRENT.set(s); }
}
