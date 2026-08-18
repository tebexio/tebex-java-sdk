package io.tebex.hooks;

public interface PlayerActions {
    boolean IsOnline(String usernameOrUuid);
    int GetNumInventorySlotsAvailable(String username);
    void SendMessage(String username, String message);
    boolean HasPermission(String username, String uuid, String permission);
}
