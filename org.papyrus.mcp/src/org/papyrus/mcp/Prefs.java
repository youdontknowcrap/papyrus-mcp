package org.papyrus.mcp;

/** Preference keys. */
public final class Prefs {
    public static final String ENABLED = "enabled";
    public static final String PORT = "port";
    /** Empty = no token required (still loopback-only). */
    public static final String TOKEN = "token";
    /** If true, mutating tools are refused (read-only bots). */
    public static final String READ_ONLY = "readOnly";
    private Prefs() {}
}
