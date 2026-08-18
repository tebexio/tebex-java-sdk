package io.tebex.http;

import io.tebex.headless.api.BasketsApi;
import io.tebex.headless.invoker.ApiClient;
import java.util.Collections;

/**
 * Instantiable access point to the generated Tebex Headless API clients
 * ({@code https://headless.tebex.io/api/accounts/{token}}).
 *
 * <p>The Headless API is scoped to a store's public token, which the contract
 * models as a server variable rather than a per-call argument. An instance is
 * therefore created for a specific token, and the generated operations take no
 * token parameter:
 *
 * <pre>{@code
 * HeadlessApi headless = new HeadlessApi(publicToken);
 * WebstoreResponse store = headless.Headless.getWebstore();
 * CategoryResponse categories = headless.Headless.getCategoriesIncludePackages();
 * BasketResponse basket = headless.Headless.createBasket(request);
 * headless.Baskets.addBasketPackage(basketIdent, addRequest);
 * }</pre>
 *
 * <p>Rather than hand-writing shim methods, callers use the generated
 * {@code io.tebex.headless.*} clients directly through the fields held here,
 * named after their OpenAPI tags ({@code Headless}, {@code Baskets}). Both share
 * this instance's {@link #client}. The generated methods throw
 * {@link io.tebex.headless.invoker.ApiException} directly; callers handle it.
 *
 * <p>The {@code Headless} field is typed by its fully-qualified name because the
 * generated {@code io.tebex.headless.api.HeadlessApi} shares this facade's
 * simple name and so cannot be imported here.
 */
public final class HeadlessApi {

    /** The public-token server variable name in the OpenAPI contract. */
    private static final String TOKEN_VARIABLE = "token";

    /** The generated client backing every endpoint group on this instance. */
    public final ApiClient client;

    /** The Headless endpoints (webstore, categories, packages, baskets, …). */
    public final io.tebex.headless.api.HeadlessApi Headless;

    /** The Baskets endpoints (basket package add/remove/quantity). */
    public final BasketsApi Baskets;

    /**
     * Creates a client with no public token bound yet. Bind one with
     * {@link #setToken(String)} before calling token-scoped operations (the
     * token typically arrives later, e.g. from the plugin API).
     */
    public HeadlessApi() {
        this(new ApiClient());
    }

    /**
     * Creates a client scoped to a store's public token.
     *
     * @param publicToken the store's public token
     */
    public HeadlessApi(String publicToken) {
        this(clientForToken(publicToken));
    }

    /**
     * Creates a client backed by an explicit generated {@link ApiClient} (used by
     * tests, e.g. with {@code new ApiClient().setBasePath(localUrl)}).
     *
     * @param client the configured generated client
     */
    public HeadlessApi(ApiClient client) {
        this.client = client;
        this.Headless = new io.tebex.headless.api.HeadlessApi(client);
        this.Baskets = new BasketsApi(client);
    }

    /**
     * Binds this client to a store's public token by setting the contract's
     * {@code token} server variable. Applies to every endpoint group on this
     * instance.
     *
     * @param publicToken the store's public token
     */
    public void setToken(String publicToken) {
        client.setServerVariables(Collections.singletonMap(TOKEN_VARIABLE, publicToken == null ? "" : publicToken));
    }

    /**
     * Builds a generated {@link ApiClient} scoped to the given public token by
     * binding the contract's {@code token} server variable.
     *
     * @param publicToken the store's public token
     * @return the configured client
     */
    private static ApiClient clientForToken(String publicToken) {
        ApiClient client = new ApiClient();
        client.setServerVariables(Collections.singletonMap(TOKEN_VARIABLE, publicToken == null ? "" : publicToken));
        return client;
    }
}
