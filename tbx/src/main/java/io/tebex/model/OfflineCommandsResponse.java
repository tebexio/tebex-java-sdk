package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The response from {@code GET /queue/offline-commands}: commands to run for
 * players who need not be connected.
 */
public final class OfflineCommandsResponse {

    @SerializedName("meta")
    private Meta meta;

    @SerializedName("commands")
    private List<QueuedCommand> commands;

    /**
     * Returns whether the store rate-limited this response, meaning more offline
     * commands remain and another check should follow.
     *
     * @return {@code true} if the response was limited
     */
    public boolean isLimited() {
        return meta != null && meta.limited;
    }

    /**
     * Returns the offline commands to execute.
     *
     * @return the commands, never {@code null}
     */
    public List<QueuedCommand> getCommands() {
        return commands == null ? Collections.<QueuedCommand>emptyList() : commands;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "OfflineCommandsResponse{commands=" + getCommands().size()
                + ", limited=" + isLimited() + '}';
    }

    /**
     * The {@code meta} block of an offline-commands response.
     */
    private static final class Meta {

        @SerializedName("limited")
        private boolean limited;
    }
}
