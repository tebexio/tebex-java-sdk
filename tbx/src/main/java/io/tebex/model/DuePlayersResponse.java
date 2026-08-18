package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The response from {@code GET /queue}: which players have commands waiting, and
 * when the plugin should next check.
 *
 * <p>{@link #getNextCheck()} is the API's own backoff instruction. The engine
 * must honour it rather than polling on a fixed interval, and a forced check must
 * reset the local timer to the value returned here (TBX_009, TBX_010).
 */
public final class DuePlayersResponse {

    @SerializedName("meta")
    private Meta meta;

    @SerializedName("players")
    private List<QueuedPlayer> players;

    /**
     * Returns whether the store wants offline commands executed on this cycle.
     *
     * @return {@code true} if the offline queue should also be drained
     */
    public boolean isExecuteOffline() {
        return meta != null && meta.executeOffline;
    }

    /**
     * Returns how many seconds to wait before the next queue check.
     *
     * @return the API-instructed delay in seconds, {@code 0} if absent
     */
    public int getNextCheck() {
        return meta == null ? 0 : meta.nextCheck;
    }

    /**
     * Returns whether more due players exist beyond this page.
     *
     * @return {@code true} if the queue was truncated
     */
    public boolean hasMore() {
        return meta != null && meta.more;
    }

    /**
     * Returns the players with commands waiting.
     *
     * @return the due players, never {@code null}
     */
    public List<QueuedPlayer> getPlayers() {
        return players == null ? Collections.<QueuedPlayer>emptyList() : players;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "DuePlayersResponse{players=" + getPlayers().size()
                + ", nextCheck=" + getNextCheck()
                + ", executeOffline=" + isExecuteOffline() + '}';
    }

    /**
     * The {@code meta} block of a due-players response.
     */
    private static final class Meta {

        @SerializedName("execute_offline")
        private boolean executeOffline;

        @SerializedName("next_check")
        private int nextCheck;

        @SerializedName("more")
        private boolean more;
    }
}
