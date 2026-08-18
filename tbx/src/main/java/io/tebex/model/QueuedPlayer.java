package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * A player the Tebex plugin API has queued commands for.
 *
 * <p>Returned inside {@code /queue} (as the due-players list) and inside
 * {@code /queue/offline-commands} (as each command's owner). Deserialized from
 * JSON by Gson.
 */
public final class QueuedPlayer {

    @SerializedName("id")
    private int id;

    @SerializedName("name")
    private String name;

    @SerializedName("uuid")
    private String uuid;

    @SerializedName("xuid")
    private String xuid;

    /**
     * Returns the Tebex-internal queue id for this player, used to build the
     * {@code /queue/online-commands/{id}} path.
     *
     * @return the queued player id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the player's username.
     *
     * @return the username, or {@code null} if the API omitted it
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the player's Minecraft UUID as sent by the API.
     *
     * <p>The API sends this without dashes for Java Edition accounts; callers
     * that need a {@link java.util.UUID} must normalise it themselves, because
     * that normalisation is platform-specific (offline-mode servers derive the
     * id differently).
     *
     * @return the player UUID, or {@code null} if the API omitted it
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * Returns the player's Xbox user id, present for Bedrock/Geyser stores.
     *
     * @return the XUID, or {@code null} for a Java Edition player
     */
    public String getXuid() {
        return xuid;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "QueuedPlayer{id=" + id + ", name='" + name + "', uuid='" + uuid + "'}";
    }
}
