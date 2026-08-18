package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * The request body for {@code POST /coupons}.
 *
 * <p>Built with chained setters and serialized directly by Gson, rather than the
 * old SDK's approach of assembling a {@code JsonObject} field by field inside the
 * client. Two behaviours from that assembly code are preserved deliberately:
 *
 * <ul>
 *   <li>{@code expire_never} is the <em>inverse</em> of {@link #canExpire(boolean)}.</li>
 *   <li>{@code discount_percentage} and {@code discount_amount} are both set from
 *       the single discount value; the API reads whichever matches
 *       {@code discount_type}.</li>
 * </ul>
 *
 * <p>Validation happens in {@link #validate()} rather than at send time, so a
 * caller finds out about a contradictory request before it reaches the network.
 */
public final class CreateCouponRequest {

    @SerializedName("code")
    private String code;

    @SerializedName("effective_on")
    private String effectiveOn;

    @SerializedName("packages")
    private List<Integer> packages;

    @SerializedName("categories")
    private List<Integer> categories;

    @SerializedName("discount_type")
    private String discountType;

    @SerializedName("discount_percentage")
    private int discountPercentage;

    @SerializedName("discount_amount")
    private int discountAmount;

    @SerializedName("redeem_unlimited")
    private boolean redeemUnlimited;

    @SerializedName("expire_never")
    private boolean expireNever = true;

    @SerializedName("expire_limit")
    private Integer expireLimit;

    @SerializedName("expire_date")
    private String expireDate;

    @SerializedName("start_date")
    private String startDate;

    @SerializedName("basket_type")
    private String basketType;

    @SerializedName("minimum")
    private int minimum;

    @SerializedName("discount_application_method")
    private int discountApplicationMethod = DiscountMethod.EACH_PACKAGE.getValue();

    @SerializedName("username")
    private String username;

    @SerializedName("note")
    private String note;

    // Retained so validate() can check the expiry combination without inferring
    // intent back out of the inverted expireNever field.
    private transient boolean canExpire;

    /**
     * Sets the code customers will type at checkout.
     *
     * @param couponCode the coupon code
     * @return this request, for chaining
     */
    public CreateCouponRequest code(String couponCode) {
        this.code = couponCode;
        return this;
    }

    /**
     * Restricts the coupon to the whole cart.
     *
     * @return this request, for chaining
     */
    public CreateCouponRequest effectiveOnCart() {
        this.effectiveOn = "cart";
        this.packages = null;
        this.categories = null;
        return this;
    }

    /**
     * Restricts the coupon to specific packages.
     *
     * @param packageIds the package ids the coupon applies to
     * @return this request, for chaining
     */
    public CreateCouponRequest effectiveOnPackages(List<Integer> packageIds) {
        this.effectiveOn = "package";
        this.packages = packageIds == null ? null : new ArrayList<Integer>(packageIds);
        this.categories = null;
        return this;
    }

    /**
     * Restricts the coupon to specific categories.
     *
     * @param categoryIds the category ids the coupon applies to
     * @return this request, for chaining
     */
    public CreateCouponRequest effectiveOnCategories(List<Integer> categoryIds) {
        this.effectiveOn = "category";
        this.categories = categoryIds == null ? null : new ArrayList<Integer>(categoryIds);
        this.packages = null;
        return this;
    }

    /**
     * Sets the discount as a percentage off.
     *
     * @param percentage the percentage to discount
     * @return this request, for chaining
     */
    public CreateCouponRequest percentageDiscount(int percentage) {
        this.discountType = "percentage";
        this.discountPercentage = percentage;
        this.discountAmount = percentage;
        return this;
    }

    /**
     * Sets the discount as a flat amount off.
     *
     * @param amount the amount to discount
     * @return this request, for chaining
     */
    public CreateCouponRequest valueDiscount(int amount) {
        this.discountType = "value";
        this.discountPercentage = amount;
        this.discountAmount = amount;
        return this;
    }

    /**
     * Sets how the discount spreads across a multi-item basket.
     *
     * @param method the application method
     * @return this request, for chaining
     */
    public CreateCouponRequest discountMethod(DiscountMethod method) {
        this.discountApplicationMethod = method.getValue();
        return this;
    }

    /**
     * Sets whether the coupon can be redeemed without limit.
     *
     * @param unlimited {@code true} for unlimited redemptions
     * @return this request, for chaining
     */
    public CreateCouponRequest redeemUnlimited(boolean unlimited) {
        this.redeemUnlimited = unlimited;
        return this;
    }

    /**
     * Sets whether the coupon expires at all.
     *
     * @param expires {@code true} if the coupon should expire
     * @return this request, for chaining
     */
    public CreateCouponRequest canExpire(boolean expires) {
        this.canExpire = expires;
        this.expireNever = !expires;
        return this;
    }

    /**
     * Sets the date the coupon expires on, in {@code yyyy-MM-dd} form.
     *
     * @param date the expiry date
     * @return this request, for chaining
     */
    public CreateCouponRequest expireDate(String date) {
        this.expireDate = date;
        return this;
    }

    /**
     * Sets how many total redemptions the coupon allows before expiring.
     *
     * @param limit the redemption limit
     * @return this request, for chaining
     */
    public CreateCouponRequest expireLimit(int limit) {
        this.expireLimit = limit;
        return this;
    }

    /**
     * Sets the date the coupon becomes usable, in {@code yyyy-MM-dd} form.
     *
     * @param date the start date
     * @return this request, for chaining
     */
    public CreateCouponRequest startDate(String date) {
        this.startDate = date;
        return this;
    }

    /**
     * Sets which basket types the coupon applies to.
     *
     * @param type the basket type
     * @return this request, for chaining
     */
    public CreateCouponRequest basketType(Coupon.BasketType type) {
        this.basketType = type == null ? null : type.name().toLowerCase();
        return this;
    }

    /**
     * Sets the minimum basket value required.
     *
     * @param minimumSpend the minimum spend
     * @return this request, for chaining
     */
    public CreateCouponRequest minimum(int minimumSpend) {
        this.minimum = minimumSpend;
        return this;
    }

    /**
     * Restricts the coupon to a single username.
     *
     * @param restrictedTo the username allowed to redeem the coupon
     * @return this request, for chaining
     */
    public CreateCouponRequest username(String restrictedTo) {
        this.username = restrictedTo;
        return this;
    }

    /**
     * Sets the store owner's internal note.
     *
     * @param internalNote the note
     * @return this request, for chaining
     */
    public CreateCouponRequest note(String internalNote) {
        this.note = internalNote;
        return this;
    }

    /**
     * Checks the request is internally consistent.
     *
     * <p>The old SDK threw a bare {@code RuntimeException} from inside the send
     * path when expiry was enabled without a date. This performs the same check
     * plus the ones that were missing, and reports them as
     * {@link IllegalStateException} before any request is built.
     *
     * @throws IllegalStateException if the request could not be fulfilled as
     *                               described
     */
    public void validate() {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalStateException("A coupon requires a code.");
        }
        if (effectiveOn == null) {
            throw new IllegalStateException(
                    "A coupon requires a scope: call effectiveOnCart, effectiveOnPackages or effectiveOnCategories.");
        }
        if (discountType == null) {
            throw new IllegalStateException(
                    "A coupon requires a discount: call percentageDiscount or valueDiscount.");
        }
        if ("package".equals(effectiveOn) && (packages == null || packages.isEmpty())) {
            throw new IllegalStateException("A package-scoped coupon requires at least one package id.");
        }
        if ("category".equals(effectiveOn) && (categories == null || categories.isEmpty())) {
            throw new IllegalStateException("A category-scoped coupon requires at least one category id.");
        }
        if (canExpire && (expireDate == null || expireDate.trim().isEmpty())) {
            throw new IllegalStateException("Coupon has expiry set to true, but no expiry date exists.");
        }
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "CreateCouponRequest{code='" + code + "', effectiveOn=" + effectiveOn + '}';
    }

    /**
     * How a discount is applied across the packages in a basket.
     */
    public enum DiscountMethod {

        /** Apply the discount to each package individually. */
        EACH_PACKAGE(0),

        /** Apply the discount once to the basket total. */
        BASKET_TOTAL(1);

        private final int value;

        /**
         * Creates a method with the numeric value the API expects.
         *
         * @param value the wire value
         */
        DiscountMethod(int value) {
            this.value = value;
        }

        /**
         * Returns the numeric value the API expects.
         *
         * @return the wire value
         */
        public int getValue() {
            return value;
        }
    }
}
