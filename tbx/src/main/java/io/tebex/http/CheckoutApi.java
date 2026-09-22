package io.tebex.http;

import io.tebex.checkout.api.BasketsApi;
import io.tebex.checkout.api.PaymentsApi;
import io.tebex.checkout.api.RecurringPaymentsApi;
import io.tebex.checkout.invoker.ApiClient;

/**
 * Instantiable access point to the generated Tebex Checkout API clients
 * ({@code https://checkout.tebex.io/api}).
 *
 * <p>The Checkout API authenticates every request with HTTP Basic auth (the
 * contract's {@code tebex_checkout_auth_basic} security scheme) rather than a
 * path-scoped token, so an instance can be created once and bound to
 * credentials with {@link #setCredentials(String, String)} whenever they
 * become available:
 *
 * <pre>{@code
 * CheckoutApi checkout = new CheckoutApi();
 * checkout.setCredentials(secretKey, "");
 * Basket basket = checkout.Baskets.getBasketById(ident).getData();
 * checkout.Checkout.checkout(request);
 * checkout.Payments.getPaymentByTxnId(txnId, "txn_id");
 * checkout.RecurringPayments.getRecurringPaymentByReference(reference);
 * }</pre>
 *
 * <p>Rather than hand-writing shim methods, callers use the generated
 * {@code io.tebex.checkout.*} clients directly through the fields held here,
 * named after their OpenAPI tags ({@code Baskets}, {@code Checkout},
 * {@code Payments}, {@code RecurringPayments}). All four share this instance's
 * {@link #client}. The generated methods throw
 * {@link io.tebex.checkout.invoker.ApiException} directly; callers handle it.
 *
 * <p>The {@code Checkout} field is typed by its fully-qualified name because
 * the generated {@code io.tebex.checkout.api.CheckoutApi} shares this facade's
 * simple name and so cannot be imported here.
 */
public final class CheckoutApi {

    /** The generated client backing every endpoint group on this instance. */
    public final ApiClient client;

    /** The Baskets endpoints (basket create/fetch/update, packages, sales). */
    public final BasketsApi Baskets;

    /** The Checkout endpoint (starting a checkout for a basket). */
    public final io.tebex.checkout.api.CheckoutApi Checkout;

    /** The Payments endpoints (single-payment fetch and refund). */
    public final PaymentsApi Payments;

    /** The Recurring Payments endpoints (fetch, status, pause/cancel). */
    public final RecurringPaymentsApi RecurringPayments;

    /**
     * Creates a client with no credentials bound yet. Bind them with
     * {@link #setCredentials(String, String)} before calling any operation.
     */
    public CheckoutApi() {
        this(new ApiClient());
    }

    /**
     * Creates a client backed by an explicit generated {@link ApiClient} (used
     * by tests, e.g. with {@code new ApiClient().setBasePath(localUrl)}).
     *
     * @param client the configured generated client
     */
    public CheckoutApi(ApiClient client) {
        this.client = client;
        this.Baskets = new BasketsApi(client);
        this.Checkout = new io.tebex.checkout.api.CheckoutApi(client);
        this.Payments = new PaymentsApi(client);
        this.RecurringPayments = new RecurringPaymentsApi(client);
    }

    /**
     * Binds this client to HTTP Basic credentials. Applies to every endpoint
     * group on this instance.
     *
     * @param username the HTTP Basic username
     * @param password the HTTP Basic password
     */
    public void setCredentials(String username, String password) {
        client.setUsername(username);
        client.setPassword(password);
    }
}
