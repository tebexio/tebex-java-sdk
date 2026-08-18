package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * The startup analytics payload sent once per server start.
 *
 * <p>Every value is supplied by the caller. The old SDK read these straight off
 * {@code PluginPlatform} ({@code getType()}, {@code getTelemetry()},
 * {@code isOnlineMode()}, {@code getPluginVersion()}), but that platform surface
 * does not exist in this SDK yet — specifying it is a separate, pending piece of
 * work. Until then the integration that <em>does</em> know these values passes
 * them in, which also makes the payload testable without a running server.
 */
public final class StartupTelemetry {

    @SerializedName("server")
    private final Server server;

    @SerializedName("plugin")
    private final PluginInfo plugin;

    /**
     * Creates a telemetry payload.
     *
     * @param platform        the platform name, for example {@code "BUKKIT"}
     * @param platformVersion the server software version
     * @param onlineMode      whether the server authenticates players with Mojang
     * @param pluginVersion   the Tebex plugin version
     */
    public StartupTelemetry(String platform, String platformVersion, boolean onlineMode, String pluginVersion) {
        this.server = new Server(platform, platformVersion, onlineMode);
        this.plugin = new PluginInfo(pluginVersion);
    }

    /**
     * Returns the server portion of the payload.
     *
     * @return the server details
     */
    public Server getServer() {
        return server;
    }

    /**
     * Returns the plugin portion of the payload.
     *
     * @return the plugin details
     */
    public PluginInfo getPlugin() {
        return plugin;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "StartupTelemetry{" + server + ", " + plugin + '}';
    }

    /**
     * The host server's identifying details.
     */
    public static final class Server {

        @SerializedName("platform")
        private final String platform;

        @SerializedName("platform_version")
        private final String platformVersion;

        @SerializedName("online_mode")
        private final boolean onlineMode;

        /**
         * Creates the server block.
         *
         * @param platform        the platform name
         * @param platformVersion the server software version
         * @param onlineMode      whether the server is in online mode
         */
        Server(String platform, String platformVersion, boolean onlineMode) {
            this.platform = platform;
            this.platformVersion = platformVersion;
            this.onlineMode = onlineMode;
        }

        /**
         * Returns the platform name.
         *
         * @return the platform name
         */
        public String getPlatform() {
            return platform;
        }

        /**
         * Returns the server software version.
         *
         * @return the platform version
         */
        public String getPlatformVersion() {
            return platformVersion;
        }

        /**
         * Returns whether the server is in online mode.
         *
         * @return {@code true} if in online mode
         */
        public boolean isOnlineMode() {
            return onlineMode;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "server=" + platform + "/" + platformVersion;
        }
    }

    /**
     * The Tebex plugin's identifying details.
     */
    public static final class PluginInfo {

        @SerializedName("version")
        private final String version;

        /**
         * Creates the plugin block.
         *
         * @param version the plugin version
         */
        PluginInfo(String version) {
            this.version = version;
        }

        /**
         * Returns the plugin version.
         *
         * @return the plugin version
         */
        public String getVersion() {
            return version;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "plugin=" + version;
        }
    }
}
