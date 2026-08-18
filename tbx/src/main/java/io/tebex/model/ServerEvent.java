package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;

/**
 * A player join or leave event, sent in batches to {@code POST /events}
 * (TBX_033, TBX_034).
 *
 * <p>The player's IP is anonymised on construction — the final octet is replaced
 * with {@code x} — so an un-anonymised address cannot be sent by forgetting to
 * call a helper.
 */
public final class ServerEvent {

    @SerializedName("username_id")
    private final String uuid;

    @SerializedName("event_type")
    private final String eventType;

    @SerializedName("event_date")
    private final String eventDate;

    @SerializedName("username")
    private final String username;

    @SerializedName("ip")
    private final String ip;

    /**
     * Creates an event stamped with the current UTC time.
     *
     * @param uuid      the player's UUID as the store identifies them
     * @param username  the player's username
     * @param ip        the player's IP address; anonymised before being stored
     * @param eventType whether the player joined or left
     */
    public ServerEvent(String uuid, String username, String ip, Type eventType) {
        this(uuid, username, ip, eventType, new Date());
    }

    /**
     * Creates an event stamped with a caller-supplied time.
     *
     * <p>The clock is a parameter so the timestamp format can be asserted in a
     * test without depending on the current time.
     *
     * @param uuid      the player's UUID as the store identifies them
     * @param username  the player's username
     * @param ip        the player's IP address; anonymised before being stored
     * @param eventType whether the player joined or left
     * @param occurredAt when the event happened
     */
    public ServerEvent(String uuid, String username, String ip, Type eventType, Date occurredAt) {
        this.uuid = uuid;
        this.username = username;
        this.ip = anonymiseIp(ip);
        this.eventType = eventType == null ? null : eventType.getWireName();
        this.eventDate = formatUtc(occurredAt);
    }

    /**
     * Returns the player UUID the event is attributed to.
     *
     * @return the player UUID
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * Returns the wire name of the event type, for example {@code "server.join"}.
     *
     * @return the event type
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns when the event occurred, formatted as UTC ISO-8601.
     *
     * @return the event timestamp
     */
    public String getEventDate() {
        return eventDate;
    }

    /**
     * Returns the player's username.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the anonymised IP address.
     *
     * @return the IP with its final octet masked
     */
    public String getIp() {
        return ip;
    }

    /**
     * Formats an instant as {@code yyyy-MM-dd'T'HH:mm:ss'Z'} in UTC.
     *
     * <p>Uses {@link SimpleDateFormat} rather than {@code java.time} only because
     * a new formatter is created per call, which keeps this thread-safe without
     * the shared-state hazard {@code SimpleDateFormat} is known for.
     *
     * @param date the instant to format
     * @return the formatted UTC timestamp
     */
    private static String formatUtc(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(new SimpleTimeZone(0, "UTC"));
        return format.format(date);
    }

    /**
     * Replaces the final dot-separated octet of an IPv4 address with {@code x}.
     *
     * <p>An address with no dot (including an IPv6 address) is returned
     * unchanged, matching the old SDK's behaviour.
     *
     * @param rawIp the address to anonymise
     * @return the anonymised address, or {@code null} if none was given
     */
    private static String anonymiseIp(String rawIp) {
        if (rawIp == null) {
            return null;
        }
        int lastOctetStart = rawIp.lastIndexOf('.');
        if (lastOctetStart == -1) {
            return rawIp;
        }
        return rawIp.substring(0, lastOctetStart) + ".x";
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "ServerEvent{" + eventType + " username='" + username + "' at " + eventDate + '}';
    }

    /**
     * The kind of server event being reported.
     */
    public enum Type {

        /** A player connected to the server. */
        JOIN("server.join"),

        /** A player disconnected from the server. */
        LEAVE("server.leave");

        private final String wireName;

        /**
         * Creates a type with the identifier the API expects.
         *
         * @param wireName the value sent as {@code event_type}
         */
        Type(String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns the identifier the API expects for this type.
         *
         * @return the wire name
         */
        public String getWireName() {
            return wireName;
        }
    }
}
