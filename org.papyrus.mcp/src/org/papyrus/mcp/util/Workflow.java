package org.papyrus.mcp.util;

import com.google.gson.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * The guided workflow (ICONIX + agile) shipped inside the plugin jar under workflow/.
 * Serves MCP "prompts" (prompts/list, prompts/get) and "resources" (resources/list, resources/read).
 *
 * Plain-word summary: prompts are ready-made instructions a bot (or its user) can pick from a
 * menu, e.g. "do robustness analysis for use case X". Resources are documents the bot can read,
 * e.g. the process checklist. Both are just text files, so they can be edited without Java.
 */
public final class Workflow {
    private Workflow() {}

    /** Static documents: uri -> {file, name, description}. A dynamic one (status) is added by the server. */
    public static final String[][] RESOURCES = {
        {"workflow://iconix", "iconix.md", "ICONIX + agile process checklist",
            "The stages (domain model, use cases, narratives, robustness, sequence), the three reviews, and the agile mapping."},
        {"workflow://iconix/conventions", "conventions.md", "How the workflow is stored in the model",
            "Where narratives, robustness packages and stereotypes live, so workflow_status can find them."},
        {"workflow://agile", "agile.md", "From use cases to an agile backlog",
            "Epics, stories, acceptance criteria, sprint planning and Definition of Done."},
    };

    /** Read a text file from the plugin's workflow/ folder (works from the jar or a dev checkout). */
    public static String text(String rel) throws IOException {
        URL u = null;
        Bundle b = Platform.getBundle("org.papyrus.mcp");
        if (b != null) u = b.getEntry("workflow/" + rel);
        if (u == null) u = Workflow.class.getClassLoader().getResource("workflow/" + rel);
        if (u == null) throw new FileNotFoundException("workflow/" + rel + " not found in plugin");
        try (InputStream in = u.openStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public static JsonArray promptDefs() throws IOException {
        return JsonParser.parseString(text("prompts.json")).getAsJsonArray();
    }

    /** prompts/list result: name, description, arguments (the "file" key is internal and dropped). */
    public static JsonObject listPrompts() throws IOException {
        JsonArray out = new JsonArray();
        for (JsonElement e : promptDefs()) {
            JsonObject p = e.getAsJsonObject().deepCopy();
            p.remove("file");
            out.add(p);
        }
        JsonObject r = new JsonObject();
        r.add("prompts", out);
        return r;
    }

    /**
     * prompts/get: fills {{arg}} and {{arg|default}} placeholders and returns one user message.
     * Missing required arguments are an error (MCP says -32602 "invalid params").
     */
    public static JsonObject getPrompt(String name, JsonObject args) throws IOException {
        for (JsonElement e : promptDefs()) {
            JsonObject p = e.getAsJsonObject();
            if (!p.get("name").getAsString().equals(name)) continue;
            for (JsonElement ae : p.getAsJsonArray("arguments")) {
                JsonObject a = ae.getAsJsonObject();
                if (a.get("required").getAsBoolean() && (args == null || !args.has(a.get("name").getAsString())))
                    throw new IllegalArgumentException("missing required argument: " + a.get("name").getAsString());
            }
            String body = fill(text("prompts/" + p.get("file").getAsString()), args);
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", body);
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "user");
            msg.add("content", content);
            JsonArray msgs = new JsonArray();
            msgs.add(msg);
            JsonObject r = new JsonObject();
            r.addProperty("description", p.get("description").getAsString());
            r.add("messages", msgs);
            return r;
        }
        throw new IllegalArgumentException("unknown prompt: " + name);
    }

    private static final Pattern PH = Pattern.compile("\\{\\{(\\w+)(?:\\|([^}]*))?\\}\\}");

    /** Replace {{name}} / {{name|default}} with the argument value (or the default, or ""). */
    static String fill(String t, JsonObject args) {
        Matcher mt = PH.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String v = args != null && args.has(mt.group(1)) && !args.get(mt.group(1)).isJsonNull()
                ? args.get(mt.group(1)).getAsString() : (mt.group(2) == null ? "" : mt.group(2));
            mt.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /** resources/list for the static documents plus the live status resource. */
    public static JsonObject listResources() {
        JsonArray out = new JsonArray();
        for (String[] r : RESOURCES) out.add(res(r[0], r[2], r[3], "text/markdown"));
        out.add(res("workflow://status", "Live workflow status of the active model",
            "Same report as the workflow_status tool, as JSON.", "application/json"));
        JsonObject o = new JsonObject();
        o.add("resources", out);
        return o;
    }

    private static JsonObject res(String uri, String name, String desc, String mime) {
        JsonObject o = new JsonObject();
        o.addProperty("uri", uri);
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.addProperty("mimeType", mime);
        return o;
    }

    /** resources/read for a static document, or null if the uri is not one of them. */
    public static JsonObject readStatic(String uri) throws IOException {
        for (String[] r : RESOURCES) if (r[0].equals(uri)) return contents(uri, "text/markdown", text(r[1]));
        return null;
    }

    public static JsonObject contents(String uri, String mime, String text) {
        JsonObject c = new JsonObject();
        c.addProperty("uri", uri);
        c.addProperty("mimeType", mime);
        c.addProperty("text", text);
        JsonArray a = new JsonArray();
        a.add(c);
        JsonObject o = new JsonObject();
        o.add("contents", a);
        return o;
    }
}
