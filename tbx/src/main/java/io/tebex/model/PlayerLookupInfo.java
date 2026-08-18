package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A store's record of a player, as returned by {@code GET /user/{username}}.
 *
 * <p>Note the inconsistent key casing in this response: {@code banCount},
 * {@code chargebackRate} and {@code purchaseTotals} are camelCase while the
 * nested player uses {@code plugin_username_id}. Both are declared verbatim.
 */
public final class PlayerLookupInfo {

    @SerializedName("player")
    private Player player;

    @SerializedName("banCount")
    private int banCount;

    @SerializedName("chargebackRate")
    private int chargebackRate;

    @SerializedName("payments")
    private List<Payment> payments;

    @SerializedName("purchaseTotals")
    private Map<String, Double> purchaseTotals;

    /**
     * Returns the player the store knows about.
     *
     * @return the player, or {@code null} if the API omitted it
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Returns how many times this player has been banned from the store.
     *
     * @return the ban count
     */
    public int getBanCount() {
        return banCount;
    }

    /**
     * Returns the player's chargeback rate as a percentage.
     *
     * @return the chargeback rate
     */
    public int getChargebackRate() {
        return chargebackRate;
    }

    /**
     * Returns the player's payment history.
     *
     * @return the payments, never {@code null}
     */
    public List<Payment> getPayments() {
        return payments == null ? Collections.<Payment>emptyList() : payments;
    }

    /**
     * Returns the player's spend totals keyed by currency.
     *
     * <p>The API sends an empty JSON array rather than an object when there are no
     * totals, which Gson cannot bind to a map; the client normalises that case
     * before parsing, so this is empty rather than absent.
     *
     * @return the purchase totals by currency, never {@code null}
     */
    public Map<String, Double> getPurchaseTotals() {
        return purchaseTotals == null
                ? Collections.<String, Double>emptyMap()
                : purchaseTotals;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "PlayerLookupInfo{player=" + player + ", bans=" + banCount
                + ", payments=" + getPayments().size() + '}';
    }

    /**
     * The identity portion of a player lookup.
     */
    public static final class Player {

        @SerializedName("id")
        private String id;

        @SerializedName("username")
        private String username;

        @SerializedName("meta")
        private String meta;

        @SerializedName("plugin_username_id")
        private int pluginUsernameId;

        /**
         * Returns the store's identifier for the player.
         *
         * @return the player id
         */
        public String getId() {
            return id;
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
         * Returns the free-form metadata the store holds against the player.
         *
         * @return the metadata string
         */
        public String getMeta() {
            return meta;
        }

        /**
         * Returns the queue-level username id, the same value used to build
         * {@code /queue/online-commands/{id}}.
         *
         * @return the plugin username id
         */
        public int getPluginUsernameId() {
            return pluginUsernameId;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Player{id='" + id + "', username='" + username + "'}";
        }
    }

    /**
     * One payment in a player's history.
     */
    public static final class Payment {

        @SerializedName("txn_id")
        private String txnId;

        @SerializedName("time")
        private long time;

        @SerializedName("price")
        private double price;

        @SerializedName("currency")
        private String currency;

        @SerializedName("status")
        private int status;

        /**
         * Returns the transaction id.
         *
         * @return the transaction id
         */
        public String getTxnId() {
            return txnId;
        }

        /**
         * Returns when the payment was made, as a Unix epoch time in seconds.
         *
         * @return the payment time
         */
        public long getTime() {
            return time;
        }

        /**
         * Returns the amount paid.
         *
         * @return the payment amount
         */
        public double getPrice() {
            return price;
        }

        /**
         * Returns the ISO 4217 currency the payment was made in.
         *
         * @return the currency code
         */
        public String getCurrency() {
            return currency;
        }

        /**
         * Returns the payment's numeric status code as sent by the API.
         *
         * @return the status code
         */
        public int getStatus() {
            return status;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Payment{txnId='" + txnId + "', price=" + price + " " + currency + '}';
        }
    }
}
