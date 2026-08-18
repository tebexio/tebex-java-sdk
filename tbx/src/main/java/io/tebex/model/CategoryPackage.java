package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * The summary view of a package as it appears inside a {@link Category} in the
 * {@code /listing} response.
 *
 * <p>This is deliberately not {@link StorePackage}: the listing endpoint returns
 * only the fields needed to render a storefront entry, while
 * {@code /package/{id}} returns the full definition.
 */
public final class CategoryPackage {

    @SerializedName("id")
    private int id;

    @SerializedName("order")
    private int order;

    @SerializedName("name")
    private String name;

    @SerializedName("price")
    private double price;

    @SerializedName("image")
    private String image;

    @SerializedName("gui_item")
    private String guiItem;

    @SerializedName("sale")
    private Sale sale;

    /**
     * Returns the package id.
     *
     * @return the package id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the display order within the category.
     *
     * @return the sort order
     */
    public int getOrder() {
        return order;
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
     * Returns the package price in the store's currency, before any sale.
     *
     * @return the list price
     */
    public double getPrice() {
        return price;
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
     * Returns the item the store owner configured to represent this package in an
     * in-game GUI.
     *
     * @return the GUI item identifier, or {@code null} if unset
     */
    public String getGuiItem() {
        return guiItem;
    }

    /**
     * Returns the sale currently applied to this package.
     *
     * @return the sale, or {@code null} if the API sent none
     */
    public Sale getSale() {
        return sale;
    }

    /**
     * Returns the effective price after any active sale discount, floored at
     * zero so a discount larger than the price cannot produce a negative price.
     *
     * @return the price a customer would pay
     */
    public double getEffectivePrice() {
        if (sale == null || !sale.isActive()) {
            return price;
        }
        return Math.max(0.0d, price - sale.getDiscount());
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "CategoryPackage{id=" + id + ", name='" + name + "', price=" + price + '}';
    }

    /**
     * A sale discount applied to a listed package.
     */
    public static final class Sale {

        @SerializedName("active")
        private boolean active;

        @SerializedName("discount")
        private double discount;

        /**
         * Returns whether the sale is currently active.
         *
         * @return {@code true} if the discount applies
         */
        public boolean isActive() {
            return active;
        }

        /**
         * Returns the absolute amount discounted from the list price.
         *
         * @return the discount amount
         */
        public double getDiscount() {
            return discount;
        }
    }
}
