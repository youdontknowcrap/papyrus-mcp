package org.papyrus.mcp.util;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.papyrus.mcp.server.Session;

/**
 * Multi-client changelog of MCP mutations, kept per model (keyed by .uml URI).
 *
 * Every successful write tool appends one Entry: global monotonically increasing seq,
 * ISO timestamp, client identity (clientInfo name/version + session id from initialize),
 * tool name, human summary, the undo label shown in Papyrus' Edit menu, affected element ids,
 * and the model version before/after. Bots call get_changelog to see what OTHER clients
 * (or themselves) changed since their last look; the cursor is kept per session.
 *
 * Optional persistence (pref "changelogSidecar"): appended to "<model>.mcp-changelog.json"
 * next to the .uml file (JSON array, rewritten on each append; fine for thousands of entries).
 * Human edits are not in the changelog (only bumps of the model version reveal them).
 */
public final class Changelog {
    public record Entry(long seq, String timestamp, String model, String client, String sessionId,
                        String tool, String summary, String undoLabel, List<String> elementIds,
                        long versionBefore, long versionAfter) {}

    private static final Changelog INSTANCE = new Changelog();
    public static Changelog get() { return INSTANCE; }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, List<Entry>> byModel = new HashMap<>();
    private long nextSeq = 1;
    private boolean sidecar;

    public synchronized void setSidecar(boolean on) { sidecar = on; }

    public synchronized Entry record(Session s, String model, String tool, String summary, String undoLabel,
                                     List<String> ids, long vBefore, long vAfter) {
        List<Entry> list = load(model);
        Entry e = new Entry(nextSeq++, Instant.now().toString(), model, s.label(), s.id, tool, summary,
                undoLabel, ids == null ? List.of() : List.copyOf(ids), vBefore, vAfter);
        list.add(e);
        if (sidecar) persist(model, list);
        return e;
    }

    /** Entries with seq > since, optionally filtered by client (substring of client label). */
    public synchronized List<Entry> since(String model, long since, String client) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load(model))
            if (e.seq() > since && (client == null || e.client().contains(client))) out.add(e);
        return out;
    }

    public synchronized long lastSeq() { return nextSeq - 1; }

    private List<Entry> load(String model) {
        return byModel.computeIfAbsent(model, m -> {
            List<Entry> l = new ArrayList<>();
            Path p = sidecarPath(m);
            if (sidecar && p != null && Files.exists(p)) {
                try {
                    Entry[] arr = gson.fromJson(Files.readString(p), Entry[].class);
                    if (arr != null) for (Entry e : arr) { l.add(e); nextSeq = Math.max(nextSeq, e.seq() + 1); }
                } catch (Exception ex) { /* corrupt sidecar: start fresh, keep the file untouched until next append */ }
            }
            return l;
        });
    }

    private void persist(String model, List<Entry> list) {
        Path p = sidecarPath(model);
        if (p == null) return;
        try {
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(list), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Platform.getLog(Changelog.class).warn("changelog sidecar write failed: " + ex);
        }
    }

    /** platform:/resource/... or file:/... -> local path of "<name>.mcp-changelog.json". */
    static Path sidecarPath(String model) {
        URI u = URI.createURI(model);
        String file = null;
        if (u.isFile()) file = u.toFileString();
        else if (u.isPlatformResource()) {
            org.eclipse.core.resources.IFile f = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                    .getFile(new org.eclipse.core.runtime.Path(u.toPlatformString(true)));
            if (f.getLocation() != null) file = f.getLocation().toOSString();
        }
        if (file == null) return null;
        return Paths.get(file.replaceFirst("\\.uml$", "") + ".mcp-changelog.json");
    }
}
