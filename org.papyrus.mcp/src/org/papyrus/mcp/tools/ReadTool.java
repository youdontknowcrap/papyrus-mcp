package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.emf.transaction.RunnableWithResult;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ModelLocator;
import org.papyrus.mcp.util.UiSync;

/**
 * Base for read-only model tools: resolves the target model (optional "model" arg) on the UI
 * thread and runs read() inside runExclusive. Adds "model" and "modelVersion" to every result so
 * a bot can pass expected_version to a later write.
 */
public abstract class ReadTool implements Tool {
    protected abstract JsonObject read(ModelLocator.Active m, JsonObject args, Session s) throws Exception;

    /** Subclasses add their own properties to this schema. */
    protected JsonObject baseSchema() {
        JsonObject s = ToolRegistry.schema();
        prop(s, "model", "string", "Optional: substring of the model URI or editor title (see list_open_models). Default: active editor.");
        return s;
    }

    protected static void prop(JsonObject schema, String name, String type, String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", desc);
        schema.getAsJsonObject("properties").add(name, p);
    }

    protected static void require(JsonObject schema, String... names) {
        JsonArray r = new JsonArray();
        for (String n : names) r.add(n);
        schema.add("required", r);
    }

    protected static String str(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : null;
    }

    protected static int integer(JsonObject a, String k, int def) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : def;
    }

    @Override
    public final JsonObject execute(JsonObject args) throws Exception {
        Session s = Session.current(); // capture on the HTTP thread (ThreadLocal)
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(str(args, "model"));
            // Capture exceptions ourselves: don't rely on how runExclusive propagates them.
            Exception[] err = new Exception[1];
            RunnableWithResult.Impl<JsonObject> r = new RunnableWithResult.Impl<>() {
                @Override public void run() {
                    try { setResult(read(m, args, s)); } catch (Exception e) { err[0] = e; }
                }
            };
            m.domain().runExclusive(r);
            if (err[0] != null) throw err[0];
            JsonObject out = r.getResult();
            out.addProperty("model", m.uri());
            out.addProperty("modelVersion", m.version());
            return out;
        });
    }
}
