package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * A store coupon, as returned by the {@code /coupons} endpoints.
 *
 * <p>Two wire quirks are preserved from the API rather than corrected: the
 * expiry block is keyed {@code expire} (not {@code expiry}), and its two flags
 * arrive as the JSON <em>strings</em> {@code "true"}/{@code "false"} rather than
 * JSON booleans. Gson's boolean adapter coerces a quoted boolean, so the fields
 * are still declared {@code boolean}.
 */
public final class Coupon {

    @SerializedName("id")
    private int id;

    @SerializedName("code")
    private String code;

    @SerializedName("effective")
    private Effective effective;

    @SerializedName("discount")
    private Discount discount;

    @SerializedName("expire")
    private Expiry expire;

    @SerializedName("basket_type")
    private BasketType basketType;

    @SerializedName("start_date")
    private String startDate;

    @SerializedName("user_limit")
    private int userLimit;

    @SerializedName("minimum")
    private int minimum;

    @SerializedName("username")
    private String username;

    @SerializedName("note")
    private String note;

    /**
     * Returns the coupon id.
     *
     * @return the coupon id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the code a customer types at checkout.
     *
     * @return the coupon code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns what the coupon applies to.
     *
     * @return the effective scope, or {@code null} if the API omitted it
     */
    public Effective getEffective() {
        return effective;
    }

    /**
     * Returns the discount the coupon grants.
     *
     * @return the discount, or {@code null} if the API omitted it
     */
    public Discount getDiscount() {
        return discount;
    }

    /**
     * Returns the coupon's expiry rules.
     *
     * @return the expiry, or {@code null} if the API omitted it
     */
    public Expiry getExpire() {
        return expire;
    }

    /**
     * Returns which basket types the coupon may be used with.
     *
     * @return the basket type, or {@code null} if unrecognised
     */
    public BasketType getBasketType() {
        return basketType;
    }

    /**
     * Returns when the coupon becomes valid, as the raw API string.
     *
     * @return the start date
     */
    public String getStartDate() {
        return startDate;
    }

    /**
     * Returns how many times a single user may redeem the coupon.
     *
     * @return the per-user limit, {@code 0} for unlimited
     */
    public int getUserLimit() {
        return userLimit;
    }

    /**
     * Returns the minimum basket value the coupon requires.
     *
     * @return the minimum spend
     */
    public int getMinimum() {
        return minimum;
    }

    /**
     * Returns the username the coupon is restricted to.
     *
     * @return the username, or {@code null}/empty if unrestricted
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the store owner's internal note about the coupon.
     *
     * @return the note
     */
    public String getNote() {
        return note;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "Coupon{id=" + id + ", code='" + code + "'}";
    }

    /**
     * What a coupon applies to: specific packages, specific categories, or the
     * whole cart.
     */
    public static final class Effective {

        @SerializedName("type")
        private Type type;

        @SerializedName("packages")
        private List<Integer> packages;

        @SerializedName("categories")
        private List<Integer> categories;

        /**
         * Returns the scope type.
         *
         * @return the effective type, or {@code null} if unrecognised
         */
        public Type getType() {
            return type;
        }

        /**
         * Returns the package ids the coupon applies to.
         *
         * @return the package ids, never {@code null}
         */
        public List<Integer> getPackages() {
            return packages == null ? Collections.<Integer>emptyList() : packages;
        }

        /**
         * Returns the category ids the coupon applies to.
         *
         * @return the category ids, never {@code null}
         */
        public List<Integer> getCategories() {
            return categories == null ? Collections.<Integer>emptyList() : categories;
        }

        /**
         * The kinds of scope a coupon can have. Declared with both casings for the
         * reason given on {@link CommunityGoal.Status}.
         */
        public enum Type {

            /** Applies to every item in the cart. */
            @SerializedName(value = "cart", alternate = {"CART", "Cart"})
            CART,

            /** Applies only to the listed packages. */
            @SerializedName(value = "package", alternate = {"PACKAGE", "Package"})
            PACKAGE,

            /** Applies only to the listed categories. */
            @SerializedName(value = "category", alternate = {"CATEGORY", "Category"})
            CATEGORY
        }
    }

    /**
     * The value a coupon takes off the basket.
     */
    public static final class Discount {

        @SerializedName("type")
        private DiscountType type;

        @SerializedName("percentage")
        private double percentage;

        @SerializedName("value")
        private int value;

        /**
         * Returns whether the discount is a percentage or a flat value.
         *
         * @return the discount type, or {@code null} if unrecognised
         */
        public DiscountType getType() {
            return type;
        }

        /**
         * Returns the percentage taken off, when the type is a percentage.
         *
         * @return the discount percentage
         */
        public double getPercentage() {
            return percentage;
        }

        /**
         * Returns the flat amount taken off, when the type is a value.
         *
         * @return the discount value
         */
        public int getValue() {
            return value;
        }
    }

    /**
     * When and how often a coupon may still be redeemed.
     */
    public static final class Expiry {

        @SerializedName("redeem_unlimited")
        private boolean redeemUnlimited;

        @SerializedName("expire_never")
        private boolean expireNever;

        @SerializedName("limit")
        private int limit;

        @SerializedName("date")
        private String date;

        /**
         * Returns whether the coupon may be redeemed an unlimited number of times.
         *
         * @return {@code true} if redemptions are unlimited
         */
        public boolean isRedeemUnlimited() {
            return redeemUnlimited;
        }

        /**
         * Returns whether the coupon never expires.
         *
         * @return {@code true} if the coupon has no expiry date
         */
        public boolean isExpireNever() {
            return expireNever;
        }

        /**
         * Returns the total number of redemptions allowed.
         *
         * @return the redemption limit, {@code 0} when unlimited
         */
        public int getLimit() {
            return limit;
        }

        /**
         * Returns the expiry date as the raw API string.
         *
         * @return the expiry date
         */
        public String getDate() {
            return date;
        }
    }

    /**
     * Whether a discount applies as a percentage or an absolute amount.
     */
    public enum DiscountType {

        /** A percentage off the basket. */
        @SerializedName(value = "percentage", alternate = {"PERCENTAGE", "Percentage"})
        PERCENTAGE,

        /** A fixed amount off the basket. */
        @SerializedName(value = "value", alternate = {"VALUE", "Value"})
        VALUE
    }

    /**
     * Which kinds of basket a coupon may be applied to.
     */
    public enum BasketType {

        /** One-off purchases only. */
        @SerializedName(value = "single", alternate = {"SINGLE", "Single"})
        SINGLE,

        /** Subscriptions only. */
        @SerializedName(value = "subscription", alternate = {"SUBSCRIPTION", "Subscription"})
        SUBSCRIPTION,

        /** Both one-off purchases and subscriptions. */
        @SerializedName(value = "both", alternate = {"BOTH", "Both"})
        BOTH
    }
}
