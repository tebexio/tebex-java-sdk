package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * The store and server details returned by the Tebex plugin API
 * {@code /information} endpoint.
 *
 * <p>Instances are deserialized from JSON by Gson; the field names are mapped to
 * the API's snake_case keys with {@link SerializedName}.
 */
public final class ServerInformation {

    @SerializedName("account")
    private Account account;

    @SerializedName("server")
    private Server server;

    @SerializedName("public_token")
    private String publicToken;

    /**
     * Returns the store account the secret key belongs to.
     *
     * @return the account details
     */
    public Account getAccount() {
        return account;
    }

    /**
     * Returns the specific server the secret key is scoped to.
     *
     * @return the server details
     */
    public Server getServer() {
        return server;
    }

    public String getPublicToken() {
        return publicToken;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "ServerInformation{account=" + account + ", server=" + server + '}';
    }

    /**
     * The store account associated with the secret key.
     */
    public static final class Account {

        @SerializedName("id")
        private int id;

        @SerializedName("domain")
        private String domain;

        @SerializedName("name")
        private String name;

        @SerializedName("currency")
        private Currency currency;

        @SerializedName("online_mode")
        private boolean onlineMode;

        @SerializedName("game_type")
        private String gameType;

        @SerializedName("log_events")
        private boolean logEvents;

        /**
         * Returns the numeric account id.
         *
         * @return the account id
         */
        public int getId() {
            return id;
        }

        /**
         * Returns the store domain (webstore URL).
         *
         * @return the store domain
         */
        public String getDomain() {
            return domain;
        }

        /**
         * Returns the store name.
         *
         * @return the store name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the store's configured currency.
         *
         * @return the store currency
         */
        public Currency getCurrency() {
            return currency;
        }

        /**
         * Returns whether the store operates in online (authenticated) mode.
         *
         * @return {@code true} if the store is in online mode
         */
        public boolean isOnlineMode() {
            return onlineMode;
        }

        /**
         * Returns the game type the store is configured for (for example
         * {@code "Minecraft (Offline/Geyser)"} or {@code "Minecraft: Java Edition"}).
         *
         * @return the store game type
         */
        public String getGameType() {
            return gameType;
        }

        /**
         * Returns whether the store has event logging enabled.
         *
         * @return {@code true} if event logging is enabled
         */
        public boolean isLogEvents() {
            return logEvents;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Account{id=" + id + ", name='" + name + "', domain='" + domain + "'}";
        }

        /**
         * A store currency, described by its ISO 4217 code and display symbol.
         */
        public static final class Currency {

            @SerializedName("iso_4217")
            private String iso4217;

            @SerializedName("symbol")
            private String symbol;

            /**
             * Returns the ISO 4217 currency code, for example {@code "USD"}.
             *
             * @return the ISO 4217 code
             */
            public String getIso4217() {
                return iso4217;
            }

            /**
             * Returns the display symbol, for example {@code "$"}.
             *
             * @return the currency symbol
             */
            public String getSymbol() {
                return symbol;
            }

            /** {@inheritDoc} */
            @Override
            public String toString() {
                return iso4217 + " (" + symbol + ")";
            }
        }
    }

    /**
     * The server the secret key is scoped to within the store.
     */
    public static final class Server {

        @SerializedName("id")
        private int id;

        @SerializedName("name")
        private String name;

        /**
         * Returns the numeric server id.
         *
         * @return the server id
         */
        public int getId() {
            return id;
        }

        /**
         * Returns the server name.
         *
         * @return the server name
         */
        public String getName() {
            return name;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Server{id=" + id + ", name='" + name + "'}";
        }
    }
}
