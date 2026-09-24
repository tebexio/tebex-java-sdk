package io.tebex.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.tebex.headless.invoker.JSON;
import io.tebex.headless.model.Basket;
import io.tebex.headless.model.BasketAuthResponseInner;
import io.tebex.headless.model.BasketResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;

/**
 * Corrects Headless API responses that the generated models reject even though
 * they carry valid data.
 *
 * <p>The contract types {@code Basket.links} as an object, but the API returns
 * an empty array ({@code "links": []}) for a basket that has no links yet — the
 * usual case for a basket that was just created. The generated models validate
 * the whole JSON tree before binding it and throw {@code Not a JSON Object: []},
 * so every {@code createBasket} call failed to deserialize despite succeeding
 * on the server. This rewrites an empty {@code links} array to an empty object
 * before the generated validation sees it.
 *
 * <p>The same quirk affects {@code getBasketAuthUrl}: on a store with no login
 * provider the API returns {@code [[]]} — a list holding an empty array where
 * the contract expects login-link objects. Those empty entries are dropped, so
 * the caller gets an empty list, meaning there is nothing to log in with.
 *
 * <p>The generated client keeps a single, static {@link Gson} in {@link JSON};
 * {@link #install()} replaces it with a copy that has this correction
 * registered ahead of the generated adapters.
 */
final class HeadlessJson {

    /** The Basket property the API returns as an array when empty. */
    private static final String LINKS = "links";

    /** The BasketResponse property that holds the basket. */
    private static final String DATA = "data";

    /** Whether {@link #install()} has already run. */
    private static boolean installed;

    /** Not instantiable. */
    private HeadlessJson() {
    }

    /**
     * Registers the correction on the generated client's shared {@link Gson}.
     * Safe to call more than once; only the first call has an effect.
     */
    static synchronized void install() {
        if (installed) {
            return;
        }
        JSON.setGson(JSON.getGson().newBuilder()
                .registerTypeAdapterFactory(new EmptyBasketLinksFactory())
                .registerTypeAdapterFactory(new EmptyAuthLinksFactory())
                .create());
        installed = true;
    }

    /**
     * Rewrites {@code "links": []} to {@code "links": {}} on a basket object.
     *
     * @param basket the basket's JSON, possibly {@code null} or not an object
     */
    static void normalizeBasket(JsonElement basket) {
        if (basket == null || !basket.isJsonObject()) {
            return;
        }
        JsonObject object = basket.getAsJsonObject();
        JsonElement links = object.get(LINKS);
        if (links != null && links.isJsonArray() && links.getAsJsonArray().isEmpty()) {
            object.add(LINKS, new JsonObject());
        }
    }

    /**
     * Applies {@link #normalizeBasket(JsonElement)} to every type the API
     * returns a basket in, before handing the tree to the generated adapter.
     */
    private static final class EmptyBasketLinksFactory implements TypeAdapterFactory {

        /**
         * Returns a normalizing adapter for {@link Basket} and
         * {@link BasketResponse}, or {@code null} for any other type.
         *
         * @param gson the Gson instance requesting the adapter
         * @param type the type being adapted
         * @param <T>  the adapted type
         * @return the adapter, or {@code null} if this factory does not apply
         */
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<? super T> raw = type.getRawType();
            final boolean isResponse = raw == BasketResponse.class;
            if (!isResponse && raw != Basket.class) {
                return null;
            }
            final TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
            final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

            return new TypeAdapter<T>() {
                /**
                 * Writes the value unchanged via the generated adapter.
                 *
                 * @param out   the writer
                 * @param value the value to write
                 * @throws IOException if writing fails
                 */
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    delegate.write(out, value);
                }

                /**
                 * Reads the JSON tree, normalizes the basket within it, then
                 * binds it via the generated adapter.
                 *
                 * @param in the reader
                 * @return the bound value
                 * @throws IOException if reading fails
                 */
                @Override
                public T read(JsonReader in) throws IOException {
                    JsonElement tree = elementAdapter.read(in);
                    if (isResponse) {
                        if (tree != null && tree.isJsonObject()) {
                            normalizeBasket(tree.getAsJsonObject().get(DATA));
                        }
                    } else {
                        normalizeBasket(tree);
                    }
                    return delegate.fromJsonTree(tree);
                }
            };
        }
    }

    /**
     * Drops the empty-array entries the API returns in place of login links
     * ({@code [[]]}) before the generated adapter reads the list.
     */
    private static final class EmptyAuthLinksFactory implements TypeAdapterFactory {

        /** The {@code getBasketAuthUrl} return type this factory applies to. */
        private static final Type AUTH_LINKS = new TypeToken<List<BasketAuthResponseInner>>() { }.getType();

        /**
         * Returns a normalizing adapter for {@code List<BasketAuthResponseInner>},
         * or {@code null} for any other type.
         *
         * @param gson the Gson instance requesting the adapter
         * @param type the type being adapted
         * @param <T>  the adapted type
         * @return the adapter, or {@code null} if this factory does not apply
         */
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!AUTH_LINKS.equals(type.getType())) {
                return null;
            }
            final TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
            final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

            return new TypeAdapter<T>() {
                /**
                 * Writes the value unchanged via the generated adapter.
                 *
                 * @param out   the writer
                 * @param value the value to write
                 * @throws IOException if writing fails
                 */
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    delegate.write(out, value);
                }

                /**
                 * Reads the list, removes empty-array entries, then binds it
                 * via the generated adapter.
                 *
                 * @param in the reader
                 * @return the bound list
                 * @throws IOException if reading fails
                 */
                @Override
                public T read(JsonReader in) throws IOException {
                    JsonElement tree = elementAdapter.read(in);
                    if (tree != null && tree.isJsonArray()) {
                        Iterator<JsonElement> entries = tree.getAsJsonArray().iterator();
                        while (entries.hasNext()) {
                            JsonElement entry = entries.next();
                            if (entry.isJsonArray() && entry.getAsJsonArray().isEmpty()) {
                                entries.remove();
                            }
                        }
                    }
                    return delegate.fromJsonTree(tree);
                }
            };
        }
    }
}
