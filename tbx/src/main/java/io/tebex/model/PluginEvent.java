package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A runtime warning or error reported to the Tebex plugin-logs service
 * (TBX_011, TBX_012, TBX_013, TBX_037).
 *
 * <p>Unlike the old SDK's equivalent, this carries no reference to a platform and
 * reads nothing from one. The old constructor took a {@code PluginPlatform} and
 * populated the framework/version/store fields from it; that surface does not
 * exist in this SDK yet, so the fields are set through the {@code on*} and
 * {@code with*} methods by whoever does have that information. Every field is
 * optional: a log with only a level and a message is valid.
 */
public final class PluginEvent {

    @SerializedName("game_id")
    private String gameId = "Minecraft";

    @SerializedName("framework_id")
    private String frameworkId;

    @SerializedName("runtime_version")
    private String runtimeVersion;

    @SerializedName("framework_version")
    private String frameworkVersion;

    @SerializedName("plugin_version")
    private String pluginVersion;

    @SerializedName("store_id")
    private String storeId;

    @SerializedName("store_name")
    private String storeName;

    @SerializedName("server_id")
    private String serverId;

    @SerializedName("event_message")
    private String eventMessage;

    @SerializedName("event_level")
    private Level eventLevel;

    @SerializedName("metadata")
    private Map<String, String> metadata;

    @SerializedName("trace")
    private String trace = "";

    /**
     * Creates an event at the given level.
     *
     * @param level   the severity of the event
     * @param message the human-readable message
     */
    public PluginEvent(Level level, String message) {
        this.eventLevel = level;
        this.eventMessage = message;
    }

    /**
     * Records which store the event happened against.
     *
     * @param account the store account
     * @return this event, for chaining
     */
    public PluginEvent onStore(ServerInformation.Account account) {
        if (account != null) {
            this.storeId = String.valueOf(account.getId());
            this.storeName = account.getName();
        }
        return this;
    }

    /**
     * Records which server the event happened on.
     *
     * @param server the store server
     * @return this event, for chaining
     */
    public PluginEvent onServer(ServerInformation.Server server) {
        if (server != null) {
            this.serverId = String.valueOf(server.getId());
        }
        return this;
    }

    /**
     * Records the host platform and runtime the event came from.
     *
     * @param frameworkId      the server software name, for example {@code "Spigot"}
     * @param frameworkVersion the server software version
     * @param runtimeVersion   the Java runtime version
     * @param pluginVersion    the Tebex plugin version
     * @return this event, for chaining
     */
    public PluginEvent onPlatform(String frameworkId, String frameworkVersion,
                                  String runtimeVersion, String pluginVersion) {
        this.frameworkId = frameworkId;
        this.frameworkVersion = frameworkVersion;
        this.runtimeVersion = runtimeVersion;
        this.pluginVersion = pluginVersion;
        return this;
    }

    /**
     * Attaches an explicit stack trace string.
     *
     * @param stackTrace the trace to report
     * @return this event, for chaining
     */
    public PluginEvent withTrace(String stackTrace) {
        this.trace = stackTrace == null ? "" : stackTrace;
        return this;
    }

    /**
     * Attaches the stack trace of a throwable.
     *
     * <p>Unlike the old SDK's version this does <em>not</em> also print the trace
     * to the console: a model object writing to standard error is a side effect
     * the caller cannot suppress, and the engine already logs what it catches.
     *
     * @param error the throwable to record
     * @return this event, for chaining
     */
    public PluginEvent withTrace(Throwable error) {
        if (error == null) {
            return this;
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        this.trace = writer.toString();
        return this;
    }

    /**
     * Adds a metadata key/value pair to the event.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return this event, for chaining
     */
    public PluginEvent withMetadata(String key, String value) {
        if (metadata == null) {
            metadata = new LinkedHashMap<String, String>();
        }
        metadata.put(key, value);
        return this;
    }

    /**
     * Returns the severity of this event.
     *
     * @return the event level
     */
    public Level getEventLevel() {
        return eventLevel;
    }

    /**
     * Returns the event message.
     *
     * @return the message
     */
    public String getEventMessage() {
        return eventMessage;
    }

    /**
     * Returns the recorded stack trace.
     *
     * @return the trace, empty if none was attached
     */
    public String getTrace() {
        return trace;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "PluginEvent{" + eventLevel + ": " + eventMessage + '}';
    }

    /**
     * The severity of a reported plugin event.
     */
    public enum Level {

        /** Informational, no action needed. */
        INFO,

        /** Something recoverable went wrong (TBX_011, TBX_013). */
        WARNING,

        /** Something failed (TBX_012). */
        ERROR
    }
}
