package org.papyrus.mcp.server;

import org.papyrus.mcp.util.Workflow;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.papyrus.mcp.tools.ToolRegistry;

/**
 * Minimal MCP "Streamable HTTP" server (spec 2025-06-18) on the JDK's built-in HttpServer.
 *
 * Why not the MCP Java SDK? It pulls Jackson, Reactor and (for HTTP) a servlet container
 * that are not all OSGi bundles in Papyrus 7.1. The protocol surface we need is small:
 * JSON-RPC 2.0 over POST, replying with application/json (SSE streaming is optional per
 * spec, so we never stream in v1). Gson is already shipped by Papyrus.
 *
 * Endpoints: POST /mcp  (JSON-RPC request or batch)  -> JSON response, or 202 for notifications
 *            GET  /mcp  -> 405 (we offer no server-initiated SSE stream; allowed by spec)
 *            DELETE /mcp -> ends session
 *
 * Security: bound to 127.0.0.1 only; Origin header checked (DNS-rebinding defence, required
 * by spec); optional Bearer token compared in constant time.
 */
public class McpHttpServer {
    public static final String PROTOCOL_VERSION = "2025-06-18";
    private final int port;
    private final String token;
    private final ToolRegistry tools;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private HttpServer http;
    /** Sessions by Mcp-Session-Id. Many clients (Grok Build, Grok Bot, ...) may be connected at once. */
    private final java.util.Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public McpHttpServer(int port, String token, ToolRegistry tools) {
        this.port = port;
        this.token = token == null ? "" : token.trim();
        this.tools = tools;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/mcp", this::handle);
        // Small pool: HTTP threads only parse/serialize; model work is marshalled to the UI thread.
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "papyrus-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() { if (http != null) http.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!originOk(ex)) { send(ex, 403, "{\"error\":\"bad origin\"}"); return; }
            if (!tokenOk(ex)) { send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex);
                case "DELETE" -> {
                    String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
                    if (sid != null) sessions.remove(sid);
                    send(ex, 200, "");
                }
                default -> send(ex, 405, "");
            }
        } catch (Exception e) {
            send(ex, 500, gson.toJson(JsonRpc.error(JsonNull.INSTANCE, -32603, e.toString())));
        } finally {
            ex.close();
        }
    }

    private void post(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement in;
        try { in = JsonParser.parseString(body); }
        catch (JsonParseException e) { send(ex, 400, gson.toJson(JsonRpc.error(JsonNull.INSTANCE, -32700, "parse error"))); return; }

        if (in.isJsonArray()) { // batch
            JsonArray out = new JsonArray();
            for (JsonElement m : in.getAsJsonArray()) {
                JsonObject r = dispatch(m.getAsJsonObject(), ex);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) send(ex, 202, ""); else sendJson(ex, out);
        } else {
            JsonObject r = dispatch(in.getAsJsonObject(), ex);
            if (r == null) send(ex, 202, ""); else sendJson(ex, r);
        }
    }

    /** Returns null for notifications (no "id"). */
    private JsonObject dispatch(JsonObject req, HttpExchange ex) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        if (id == null) return null; // notifications/initialized, notifications/cancelled, ...
        try {
            switch (method) {
                case "initialize": {
                    String sid = UUID.randomUUID().toString();
                    JsonObject ci = params.has("clientInfo") ? params.getAsJsonObject("clientInfo") : new JsonObject();
                    sessions.put(sid, new Session(sid,
                            ci.has("name") ? ci.get("name").getAsString() : null,
                            ci.has("version") ? ci.get("version").getAsString() : null));
                    ex.getResponseHeaders().add("Mcp-Session-Id", sid);
                    JsonObject res = new JsonObject();
                    res.addProperty("protocolVersion", PROTOCOL_VERSION);
                    JsonObject caps = new JsonObject();
                    JsonObject toolsCap = new JsonObject();
                    toolsCap.addProperty("listChanged", false);
                    caps.add("tools", toolsCap);
                    // Guided workflow: prompts (ready-made ICONIX/agile instructions) and
                    // resources (the process checklist and a live status report).
                    JsonObject listCap = new JsonObject();
                    listCap.addProperty("listChanged", false);
                    caps.add("prompts", listCap);
                    JsonObject resCap = new JsonObject();
                    resCap.addProperty("listChanged", false);
                    resCap.addProperty("subscribe", false);
                    caps.add("resources", resCap);
                    res.add("capabilities", caps);
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "papyrus-mcp");
                    info.addProperty("version", "0.1.0");
                    res.add("serverInfo", info);
                    res.addProperty("instructions", "Live control of the open Papyrus model. All edits are undoable (Ctrl+Z in Papyrus). Call list_packages first. "
                        + "For guided modeling read resource workflow://iconix, call workflow_status, and use the iconix_* / agile prompts.");
                    return JsonRpc.result(id, res);
                }
                case "ping": return JsonRpc.result(id, new JsonObject());
                case "tools/list": {
                    JsonObject res = new JsonObject();
                    res.add("tools", tools.describeAll());
                    return JsonRpc.result(id, res);
                }
                case "tools/call": {
                    String name = params.get("name").getAsString();
                    JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
                    // Unknown/missing session id -> anonymous identity (lenient for curl testing;
                    // strict clients always send the header after initialize).
                    String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
                    Session.setCurrent(sid == null ? null : sessions.get(sid));
                    try { return JsonRpc.result(id, tools.call(name, args)); }
                    finally { Session.setCurrent(null); }
                }
                case "prompts/list": return JsonRpc.result(id, Workflow.listPrompts());
                case "prompts/get": {
                    try {
                        return JsonRpc.result(id, Workflow.getPrompt(params.get("name").getAsString(),
                            params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject()));
                    } catch (IllegalArgumentException bad) { return JsonRpc.error(id, -32602, bad.getMessage()); }
                }
                case "resources/list": return JsonRpc.result(id, Workflow.listResources());
                case "resources/templates/list": {
                    JsonObject r = new JsonObject();
                    r.add("resourceTemplates", new JsonArray());
                    return JsonRpc.result(id, r);
                }
                case "resources/read": {
                    String uri = params.get("uri").getAsString();
                    JsonObject stat = Workflow.readStatic(uri);
                    if (stat != null) return JsonRpc.result(id, stat);
                    if (uri.equals("workflow://status")) {
                        // Same report as the workflow_status tool, for clients that prefer resources.
                        JsonObject r = tools.call("workflow_status", new JsonObject());
                        String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                        return JsonRpc.result(id, Workflow.contents(uri, "application/json", text));
                    }
                    return JsonRpc.error(id, -32002, "resource not found: " + uri);
                }
                default: return JsonRpc.error(id, -32601, "method not found: " + method);
            }
        } catch (Exception e) {
            return JsonRpc.error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    /** Browsers send Origin; local CLI/agents usually don't. Allow absent or loopback origins only. */
    private boolean originOk(HttpExchange ex) {
        String o = ex.getRequestHeaders().getFirst("Origin");
        if (o == null || o.isEmpty() || o.equals("null")) return o == null || o.isEmpty();
        return o.matches("https?://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?");
    }

    private boolean tokenOk(HttpExchange ex) {
        if (token.isEmpty()) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String given = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : "";
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private void sendJson(HttpExchange ex, JsonElement e) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, 200, gson.toJson(e));
    }

    private static void send(HttpExchange ex, int code, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
