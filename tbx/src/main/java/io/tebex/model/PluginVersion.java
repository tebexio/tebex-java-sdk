package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/** The latest published version of a Tebex plugin for one game platform. */
public final class PluginVersion {

    @SerializedName("version")
    private String version;

    @SerializedName("released")
    private String released;

    /**
     * Returns the published plugin version.
     *
     * @return the version reported by Tebex
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the release timestamp exactly as reported by Tebex.
     *
     * @return the release timestamp
     */
    public String getReleased() {
        return released;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "PluginVersion{version='" + version + "', released='" + released + "'}";
    }
}
