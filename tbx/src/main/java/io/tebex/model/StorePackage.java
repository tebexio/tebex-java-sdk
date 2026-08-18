package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * The full definition of a store package, as returned by {@code GET /packages}
 * and {@code GET /package/{id}}.
 *
 * <p>Named {@code StorePackage} rather than the old SDK's {@code Package}: that
 * name shadows {@link java.lang.Package} in every file that imports it, which is
 * legal but a persistent readability trap in a public SDK.
 */
public final class StorePackage {

    @SerializedName("id")
    private int id;

    @SerializedName("name")
    private String name;

    @SerializedName("image")
    private String image;

    @SerializedName("price")
    private double price;

    @SerializedName("expiry_length")
    private int expiryLength;

    @SerializedName("expiry_period")
    private String expiryPeriod;

    @SerializedName("type")
    private String type;

    @SerializedName("category")
    private Reference category;

    @SerializedName("global_limit")
    private int globalLimit;

    @SerializedName("global_limit_period")
    private String globalLimitPeriod;

    @SerializedName("user_limit")
    private int userLimit;

    @SerializedName("user_limit_period")
    private String userLimitPeriod;

    @SerializedName("servers")
    private List<Reference> servers;

    @SerializedName("required_packages")
    private List<Integer> requiredPackages;

    @SerializedName("require_any")
    private boolean requireAny;

    @SerializedName("create_giftcard")
    private boolean createGiftcard;

    // The API key is "show_until"; the old SDK's field was misspelled "showUtil".
    @SerializedName("show_until")
    private boolean showUntil;

    @SerializedName("gui_item")
    private String guiItem;

    @SerializedName("disabled")
    private boolean disabled;

    @SerializedName("disable_quantity")
    private boolean disableQuantity;

    @SerializedName("custom_price")
    private boolean customPrice;

    @SerializedName("choose_server")
    private boolean chooseServer;

    @SerializedName("limit_expires")
    private boolean limitExpires;

    @SerializedName("inherit_commands")
    private boolean inheritCommands;

    @SerializedName("variable_giftcard")
    private boolean variableGiftcard;

    /**
     * Returns the package id.
     *
     * @return the package id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the package name.
     *
     * @return the package name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the package image URL.
     *
     * @return the image URL, or {@code null} if none is set
     */
    public String getImage() {
        return image;
    }

    /**
     * Returns the package price in the store's currency.
     *
     * @return the price
     */
    public double getPrice() {
        return price;
    }

    /**
     * Returns how many {@link #getExpiryPeriod()} units the package lasts.
     *
     * @return the expiry length, {@code 0} if the package does not expire
     */
    public int getExpiryLength() {
        return expiryLength;
    }

    /**
     * Returns the unit the expiry length is measured in, for example
     * {@code "month"}.
     *
     * @return the expiry period, or {@code null} if the package does not expire
     */
    public String getExpiryPeriod() {
        return expiryPeriod;
    }

    /**
     * Returns the package type, for example {@code "single"} or
     * {@code "subscription"}.
     *
     * @return the package type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the category this package belongs to, as an id/name reference
     * rather than a full {@link Category}: the packages endpoints send only those
     * two fields.
     *
     * @return the owning category reference, or {@code null} if uncategorised
     */
    public Reference getCategory() {
        return category;
    }

    /**
     * Returns how many times this package may be purchased store-wide.
     *
     * @return the global limit, {@code 0} for unlimited
     */
    public int getGlobalLimit() {
        return globalLimit;
    }

    /**
     * Returns the period the global limit resets over.
     *
     * @return the global limit period, or {@code null} if there is no limit
     */
    public String getGlobalLimitPeriod() {
        return globalLimitPeriod;
    }

    /**
     * Returns how many times a single user may purchase this package.
     *
     * @return the per-user limit, {@code 0} for unlimited
     */
    public int getUserLimit() {
        return userLimit;
    }

    /**
     * Returns the period the per-user limit resets over.
     *
     * @return the user limit period, or {@code null} if there is no limit
     */
    public String getUserLimitPeriod() {
        return userLimitPeriod;
    }

    /**
     * Returns the servers this package may be purchased for.
     *
     * @return the servers, never {@code null}
     */
    public List<Reference> getServers() {
        return servers == null ? Collections.<Reference>emptyList() : servers;
    }

    /**
     * Returns the ids of packages a customer must already own to buy this one.
     *
     * @return the required package ids, never {@code null}
     */
    public List<Integer> getRequiredPackages() {
        return requiredPackages == null ? Collections.<Integer>emptyList() : requiredPackages;
    }

    /**
     * Returns whether owning <em>any</em> required package suffices, as opposed
     * to all of them.
     *
     * @return {@code true} if any one required package is enough
     */
    public boolean isRequireAny() {
        return requireAny;
    }

    /**
     * Returns whether purchasing this package creates a gift card.
     *
     * @return {@code true} if a gift card is created
     */
    public boolean isCreateGiftcard() {
        return createGiftcard;
    }

    /**
     * Returns whether the package remains visible after its limit is reached.
     *
     * @return {@code true} if the package is still shown
     */
    public boolean isShowUntil() {
        return showUntil;
    }

    /**
     * Returns the item the store owner configured to represent this package in an
     * in-game GUI.
     *
     * @return the GUI item identifier, or {@code null} if unset or blank
     */
    public String getGuiItem() {
        return guiItem == null || guiItem.isEmpty() ? null : guiItem;
    }

    /**
     * Returns whether the package is disabled and cannot be purchased.
     *
     * @return {@code true} if disabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Returns whether the quantity selector is hidden for this package.
     *
     * @return {@code true} if quantity selection is disabled
     */
    public boolean isDisableQuantity() {
        return disableQuantity;
    }

    /**
     * Returns whether the customer chooses the price (pay-what-you-want).
     *
     * @return {@code true} if the price is customer-set
     */
    public boolean isCustomPrice() {
        return customPrice;
    }

    /**
     * Returns whether the customer picks which server the package applies to.
     *
     * @return {@code true} if the customer chooses a server
     */
    public boolean isChooseServer() {
        return chooseServer;
    }

    /**
     * Returns whether the purchase limit expires over time.
     *
     * @return {@code true} if the limit expires
     */
    public boolean isLimitExpires() {
        return limitExpires;
    }

    /**
     * Returns whether this package inherits commands from its category.
     *
     * @return {@code true} if commands are inherited
     */
    public boolean isInheritCommands() {
        return inheritCommands;
    }

    /**
     * Returns whether the gift card this package creates has a variable value.
     *
     * @return {@code true} if the gift card value varies
     */
    public boolean isVariableGiftcard() {
        return variableGiftcard;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "StorePackage{id=" + id + ", name='" + name + "', price=" + price + '}';
    }

    /**
     * An id/name pair the packages endpoints use to point at a category or server
     * without embedding the whole object.
     */
    public static final class Reference {

        @SerializedName("id")
        private int id;

        @SerializedName("name")
        private String name;

        /**
         * Returns the referenced id.
         *
         * @return the id
         */
        public int getId() {
            return id;
        }

        /**
         * Returns the referenced name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Reference{id=" + id + ", name='" + name + "'}";
        }
    }
}
