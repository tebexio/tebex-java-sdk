package io.tebex.hooks;

/**
 * How the SDK inspects and talks to players on the host.
 *
 * <p>Used to decide whether a purchase can be delivered right now — the player
 * has to be connected, and to have room for what they bought — and to check
 * command permissions. The hook is optional: with none installed those checks
 * cannot be made, so the SDK proceeds rather than refusing everything (TBX_060).
 */
public interface PlayerActions {

    /**
     * Returns whether a player is currently connected.
     *
     * @param usernameOrUuid the player's name or uuid
     * @return {@code true} if the player is online
     */
    boolean IsOnline(String usernameOrUuid);

    /**
     * Returns how many free inventory slots a player has, so a command that
     * requires room can be held back until they do.
     *
     * @param username the player's name
     * @return the number of free slots
     */
    int GetNumInventorySlotsAvailable(String username);

    /**
     * Sends a message to a player.
     *
     * @param username the player's name
     * @param message  the message to send
     */
    void SendMessage(String username, String message);

    /**
     * Returns whether a player holds a permission node.
     *
     * @param username   the player's name
     * @param uuid       the player's uuid, may be empty
     * @param permission the permission node, for example {@code "tebex.secret"}
     * @return {@code true} if the player may proceed
     */
    boolean HasPermission(String username, String uuid, String permission);
}
