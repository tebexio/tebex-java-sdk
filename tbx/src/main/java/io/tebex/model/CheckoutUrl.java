package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * The result of {@code POST /checkout}: a hosted checkout link for a package
 * (TBX_017).
 */
public final class CheckoutUrl {

    @SerializedName("url")
    private String url;

    @SerializedName("expires")
    private String expires;

    /**
     * Returns the URL to send the customer to in order to complete payment.
     *
     * @return the checkout URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns when the checkout link stops being valid, as the raw string the API
     * sends.
     *
     * @return the expiry timestamp
     */
    public String getExpires() {
        return expires;
    }

    /**
     * Returns the basket identifier embedded at the end of the checkout URL.
     *
     * <p>This is the value the Headless API expects as a basket ident, which is
     * what makes a checkout created here usable with the Headless endpoints
     * (TBX_017).
     *
     * @return the basket ident, or {@code null} if the URL is absent or has no
     *         final path segment
     */
    public String getBasketIdent() {
        if (url == null) {
            return null;
        }
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == trimmed.length() - 1) {
            return null;
        }
        return trimmed.substring(lastSlash + 1);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "CheckoutUrl{url='" + url + "', expires='" + expires + "'}";
    }
}
