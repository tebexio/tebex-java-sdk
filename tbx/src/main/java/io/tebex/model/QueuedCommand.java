package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * A single command Tebex wants the game server to execute in order to deliver a
 * purchase.
 *
 * <p>Returned by both {@code /queue/offline-commands} and
 * {@code /queue/online-commands/{id}}. The two endpoints differ in where the
 * owning player comes from: the offline payload carries a {@code player} object
 * per command, while the online payload omits it because the caller already
 * knows which player it asked about. {@link #getPlayer()} is therefore populated
 * from JSON for offline commands and injected by {@code PluginApi} for online
 * ones.
 *
 * <p>The command string is returned <em>verbatim</em>, tags and all: this is the
 * model of what the API sent, not of what will be run. The player tags are
 * resolved once, on the dispatch path in {@code Plugin}, immediately before the
 * command reaches the host's command hook (TBX_063).
 */
public final class QueuedCommand {

    @SerializedName("id")
    private int id;

    @SerializedName("command")
    private String command;

    // Boxed because the API sends JSON null for commands not tied to a payment
    // or package. The accessors collapse null to 0, preserving the behaviour the
    // platform modules were written against.
    @SerializedName("payment")
    private Integer payment;

    @SerializedName("package")
    private Integer packageId;

    @SerializedName("conditions")
    private Conditions conditions;

    @SerializedName("player")
    private QueuedPlayer player;

    // Not part of the API payload: which endpoint this command came from.
    private transient boolean online;

    /**
     * Returns the Tebex command id, used to acknowledge the command via
     * {@code DELETE /queue}.
     *
     * @return the command id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the raw command line to execute, without a leading slash and with
     * its Tebex tags left intact.
     *
     * @return the command string
     */
    public String getCommand() {
        return command;
    }

    /**
     * Returns the payment this command was created by.
     *
     * @return the payment id, or {@code 0} if the command is not tied to one
     */
    public int getPaymentId() {
        return payment == null ? 0 : payment;
    }

    /**
     * Returns the package this command belongs to.
     *
     * @return the package id, or {@code 0} if the command is not tied to one
     */
    public int getPackageId() {
        return packageId == null ? 0 : packageId;
    }

    /**
     * Returns how long to wait before executing this command.
     *
     * @return the delay in seconds, or {@code 0} if unconditional
     */
    public int getDelay() {
        return conditions == null ? 0 : conditions.getDelay();
    }

    /**
     * Returns how many free inventory slots the player must have before this
     * command may execute.
     *
     * @return the required free slots, or {@code 0} if unconditional
     */
    public int getRequiredSlots() {
        return conditions == null ? 0 : conditions.getSlots();
    }

    /**
     * Returns the player this command delivers to.
     *
     * @return the owning player
     */
    public QueuedPlayer getPlayer() {
        return player;
    }

    /**
     * Returns whether this command came from the online queue (and so requires
     * the player to be connected) rather than the offline queue.
     *
     * @return {@code true} if this is an online command
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * Attaches the owning player and marks this command as an online command.
     *
     * <p>Called by the API client after parsing an online-queue response, whose
     * payload carries neither the player nor the flag. Public only because the
     * client lives in a different package; application code has no reason to
     * call it.
     *
     * @param queuedPlayer the player the command was fetched for
     */
    public void bindOnlinePlayer(QueuedPlayer queuedPlayer) {
        this.player = queuedPlayer;
        this.online = true;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "QueuedCommand{id=" + id + ", command='" + command + "', online=" + online + '}';
    }

    /**
     * The optional preconditions attached to a queued command.
     */
    public static final class Conditions {

        @SerializedName("delay")
        private int delay;

        @SerializedName("slots")
        private int slots;

        /**
         * Returns the delay in seconds before the command may run.
         *
         * @return the delay in seconds, {@code 0} if absent
         */
        public int getDelay() {
            return delay;
        }

        /**
         * Returns the number of free inventory slots required.
         *
         * @return the required slots, {@code 0} if absent
         */
        public int getSlots() {
            return slots;
        }
    }
}
