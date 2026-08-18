package io.tebex.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * A page of results plus the cursor describing where it sits in the whole set.
 *
 * @param <T> the element type of the page
 */
public final class PaginatedResponse<T> {

    @SerializedName("pagination")
    private Pagination pagination;

    @SerializedName("data")
    private List<T> data;

    /**
     * Returns the pagination cursor for this page.
     *
     * @return the pagination details, or {@code null} if the API omitted them
     */
    public Pagination getPagination() {
        return pagination;
    }

    /**
     * Returns the items on this page.
     *
     * @return the page contents, never {@code null}
     */
    public List<T> getData() {
        return data == null ? Collections.<T>emptyList() : data;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "PaginatedResponse{items=" + getData().size() + ", " + pagination + '}';
    }

    /**
     * Where a page sits within the full result set.
     *
     * <p>These keys are camelCase on the wire, unlike the snake_case used
     * elsewhere in the plugin API.
     */
    public static final class Pagination {

        @SerializedName("totalResults")
        private int totalResults;

        @SerializedName("currentPage")
        private int currentPage;

        @SerializedName("lastPage")
        private int lastPage;

        @SerializedName("previous")
        private String previous;

        @SerializedName("next")
        private String next;

        /**
         * Returns how many results exist across all pages.
         *
         * @return the total result count
         */
        public int getTotalResults() {
            return totalResults;
        }

        /**
         * Returns the 1-based index of this page.
         *
         * @return the current page number
         */
        public int getCurrentPage() {
            return currentPage;
        }

        /**
         * Returns the number of the final page.
         *
         * @return the last page number
         */
        public int getLastPage() {
            return lastPage;
        }

        /**
         * Returns the URL of the previous page.
         *
         * @return the previous page URL, or {@code null} on the first page
         */
        public String getPrevious() {
            return previous;
        }

        /**
         * Returns the URL of the next page.
         *
         * @return the next page URL, or {@code null} on the last page
         */
        public String getNext() {
            return next;
        }

        /**
         * Returns whether a further page follows this one.
         *
         * @return {@code true} if this is not the last page
         */
        public boolean hasNext() {
            return currentPage < lastPage;
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return "Pagination{page=" + currentPage + "/" + lastPage + ", total=" + totalResults + '}';
        }
    }
}
