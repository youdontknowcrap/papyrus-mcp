package org.papyrus.mcp.tools;

import com.google.gson.JsonObject;

/** One MCP tool. execute() runs on the HTTP thread; implementations hop to the UI thread via UiSync. */
public interface Tool {
    String name();
    String description();
    /** JSON Schema for "arguments". */
    JsonObject inputSchema();
    /** true if the tool changes the model (refused in read-only mode, wrapped in a command). */
    default boolean mutating() { return false; }
    /** Returns structured JSON; ToolRegistry wraps it into MCP content. */
    JsonObject execute(JsonObject args) throws Exception;
}
