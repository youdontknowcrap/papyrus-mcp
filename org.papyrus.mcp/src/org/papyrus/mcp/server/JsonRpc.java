package org.papyrus.mcp.server;

import com.google.gson.*;

/** JSON-RPC 2.0 envelope helpers. */
public final class JsonRpc {
    private JsonRpc() {}
    public static JsonObject result(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }
    public static JsonObject error(JsonElement id, int code, String msg) {
        JsonObject o = new JsonObject(), e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", msg);
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("error", e);
        return o;
    }
}
