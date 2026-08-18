package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * A storefront category as returned by {@code GET /listing}.
 *
 * <p>Categories nest one level: a category may contain packages, subcategories,
 * or (when {@link #isOnlySubcategories()} is set) only subcategories. Unlike the
 * old SDK's model, a subcategory does not hold a back-reference to its parent —
 * that made the object graph cyclic and undeserializable by plain Gson, and no
 * caller needed it.
 */
public final class Category {

    @SerializedName("id")
    private int id;

    @SerializedName("order")
    private int order;

    @SerializedName("name")
    private String name;

    @SerializedName("gui_item")
    private String guiItem;

    @SerializedName("only_subcategories")
    private boolean onlySubcategories;

    @SerializedName("packages")
    private List<CategoryPackage> packages;

    @SerializedName("subcategories")
    private List<Category> subcategories;

    /**
     * Returns the category id.
     *
     * @return the category id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the display order of this category on the webstore.
     *
     * @return the sort order
     */
    public int getOrder() {
        return order;
    }

    /**
     * Returns the category name.
     *
     * @return the category name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the item the store owner configured to represent this category in
     * an in-game GUI.
     *
     * @return the GUI item identifier, or {@code null} if unset
     */
    public String getGuiItem() {
        return guiItem;
    }

    /**
     * Returns whether this category displays only its subcategories, hiding any
     * packages directly attached to it.
     *
     * @return {@code true} if only subcategories should be shown
     */
    public boolean isOnlySubcategories() {
        return onlySubcategories;
    }

    /**
     * Returns the packages directly in this category.
     *
     * @return the packages, never {@code null}
     */
    public List<CategoryPackage> getPackages() {
        return packages == null ? Collections.<CategoryPackage>emptyList() : packages;
    }

    /**
     * Returns the subcategories of this category.
     *
     * @return the subcategories, never {@code null}
     */
    public List<Category> getSubcategories() {
        return subcategories == null ? Collections.<Category>emptyList() : subcategories;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "Category{id=" + id + ", name='" + name + "', packages=" + getPackages().size() + '}';
    }
}
