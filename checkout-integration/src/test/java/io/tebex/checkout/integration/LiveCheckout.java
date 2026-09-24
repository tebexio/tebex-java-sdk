package io.tebex.checkout.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.AddPackageRequest;
import io.tebex.checkout.model.CreateBasketRequest;
import io.tebex.checkout.model.ModelPackage;
import io.tebex.http.CheckoutApi;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live Checkout account under test, configured from environment variables.
 *
 * <ul>
 *   <li>{@code TEBEX_IT_CHECKOUT_PUBLIC_TOKEN}, {@code TEBEX_IT_CHECKOUT_PRIVATE_KEY}
 *       — required; the HTTP Basic credentials of a store with the Checkout API
 *       enabled. Every test is skipped without them.</li>
 *   <li>{@code TEBEX_IT_CHECKOUT_TXN_ID} — optional; a payment ({@code tbx-...})
 *       to fetch. The payment read test is skipped without it.</li>
 *   <li>{@code TEBEX_IT_CHECKOUT_RECURRING_REF} — optional; a recurring payment
 *       ({@code tbx-r-...}) to fetch. The recurring read test is skipped without it.</li>
 * </ul>
 *
 * <p>These name a real payment or subscription the test will change, and each
 * test that uses one is skipped without it:
 *
 * <ul>
 *   <li>{@code TEBEX_IT_CHECKOUT_REFUND_TXN_ID} — a completed payment to refund.
 *       Irreversible.</li>
 *   <li>{@code TEBEX_IT_CHECKOUT_PAUSE_REF} — an active recurring payment to pause
 *       and then reactivate.</li>
 *   <li>{@code TEBEX_IT_CHECKOUT_UPDATE_REF} — a recurring payment whose product is
 *       replaced. May make a pro-rata charge.</li>
 *   <li>{@code TEBEX_IT_CHECKOUT_CANCEL_REF} — a recurring payment to cancel.
 *       Irreversible.</li>
 * </ul>
 *
 * <p>Every other test creates real (abandoned, never paid) baskets.
 */
final class LiveCheckout {

    static final String RETURN_URL = "https://example.com/tebex-integration-test";

    static final String CUSTOMER_EMAIL = "integration-test@example.com";

    private static final String MODEL_PACKAGE = "io.tebex.checkout.model";

    private LiveCheckout() {
    }

    /** Returns the environment variable, or {@code null} if unset or blank. */
    static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** Returns an optional variable, skipping the calling test if it is not configured. */
    static String required(String name) {
        String value = env(name);
        assumeTrue(value != null, name + " is not set; test skipped");
        return value;
    }

    /** Returns a client for the live account, exactly as a consumer builds one. */
    static CheckoutApi client() {
        String token = env("TEBEX_IT_CHECKOUT_PUBLIC_TOKEN");
        String privateKey = env("TEBEX_IT_CHECKOUT_PRIVATE_KEY");
        assumeTrue(token != null && privateKey != null,
                "TEBEX_IT_CHECKOUT_PUBLIC_TOKEN and TEBEX_IT_CHECKOUT_PRIVATE_KEY are not set; "
                        + "live Checkout API tests skipped");
        CheckoutApi checkout = new CheckoutApi();
        checkout.setCredentials(token, privateKey);
        return checkout;
    }

    /** A one-off package, defined inline as the Checkout API expects. */
    static ModelPackage oneOffPackage() {
        return new ModelPackage()
                .name("Integration Test Item")
                .price(1.27f)
                .type(ModelPackage.TypeEnum.SINGLE);
    }

    /** Creates a fresh, empty basket and returns its ident. */
    static String newBasket(CheckoutApi checkout) throws ApiException {
        String ident = checkout.Baskets.createBasket(new CreateBasketRequest()
                .returnUrl(RETURN_URL + "/return")
                .completeUrl(RETURN_URL + "/complete")
                .email(CUSTOMER_EMAIL)).getIdent();
        assertTrue(ident != null && !ident.isEmpty(), "createBasket must return a basket ident");
        return ident;
    }

    /** Creates a basket holding one {@link #oneOffPackage()} and returns its ident. */
    static String basketWithPackage(CheckoutApi checkout) throws ApiException {
        String ident = newBasket(checkout);
        checkout.Baskets.addPackage(ident, new AddPackageRequest()
                ._package(oneOffPackage())
                .qty(1)
                .type(AddPackageRequest.TypeEnum.SINGLE));
        return ident;
    }

    /**
     * Records every property in the response that the contract does not define,
     * naming the schema each one should be added to.
     *
     * <p>This never fails the test: the client tolerates such properties
     * (keeping them in {@code additionalProperties}) so released SDKs survive
     * API additions. Each one is appended to the file named by the
     * {@code tebex.contractWarnings} system property, which the build prints as
     * warnings once the tests finish, so the contract can be updated.
     */
    static void checkContract(Object response) {
        Map<String, Extra> extras = new LinkedHashMap<String, Extra>();
        collectUndeclared(response, "response", extras,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
        if (extras.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        for (Extra extra : extras.values()) {
            System.err.println("WARNING: property `" + extra.property + "` is not defined in apis/checkout-api.yaml; add it to "
                    + extra.schema + " (" + describe(extra.example) + ") - seen at " + extra.firstPath);
            lines.add(tsv(extra.schema) + "\t" + tsv(extra.property) + "\t" + tsv(describe(extra.example))
                    + "\t" + tsv(extra.firstPath));
        }
        String warnings = System.getProperty("tebex.contractWarnings");
        if (warnings == null) {
            return;
        }
        synchronized (LOG_LOCK) {
            try {
                Files.write(Paths.get(warnings), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("WARNING: could not record contract warnings in " + warnings + ": " + e);
            }
        }
    }

    private static final Object LOG_LOCK = new Object();

    /** Keeps a value on one tab-separated line. */
    private static String tsv(String value) {
        return value.replaceAll("[\\t\\r\\n]+", " ");
    }

    /** One undeclared property, grouped across every object it appeared on. */
    private static final class Extra {
        final String schema;
        final String property;
        final Object example;
        final String firstPath;

        Extra(String schema, String property, Object example, String firstPath) {
            this.schema = schema;
            this.property = property;
            this.example = example;
            this.firstPath = firstPath;
        }
    }

    private static void collectUndeclared(Object value, String path, Map<String, Extra> out, Set<Object> seen) {
        if (value == null || !seen.add(value)) {
            return;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                collectUndeclared(list.get(i), path + "[" + i + "]", out, seen);
            }
            return;
        }
        if (!value.getClass().getName().startsWith(MODEL_PACKAGE)) {
            return;
        }
        for (Method getter : value.getClass().getMethods()) {
            if (getter.getParameterCount() != 0 || Modifier.isStatic(getter.getModifiers())
                    || getter.getDeclaringClass() == Object.class || !getter.getName().startsWith("get")) {
                continue;
            }
            Object child;
            try {
                child = getter.invoke(value);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (getter.getName().equals("getAdditionalProperties")) {
                if (child instanceof Map) {
                    String schema = schemaFor(value.getClass());
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) child).entrySet()) {
                        String property = String.valueOf(entry.getKey());
                        String key = schema + "#" + property;
                        Extra extra = out.get(key);
                        if (extra == null) {
                            extra = new Extra(schema, property, entry.getValue(), path);
                            out.put(key, extra);
                        }
                    }
                }
            } else {
                collectUndeclared(child, path + "." + getter.getName().substring(3), out, seen);
            }
        }
    }

    /**
     * Names the contract schema a generated model comes from. The generator
     * renames some schemas (e.g. {@code Package} becomes {@code ModelPackage})
     * and generates classes for inline objects that have no schema of their own.
     */
    private static String schemaFor(Class<?> model) {
        String name = model.getSimpleName();
        Set<String> schemas = contractSchemas();
        if (schemas.contains(name)) {
            return "components/schemas/" + name;
        }
        if (name.startsWith("Model") && schemas.contains(name.substring(5))) {
            return "components/schemas/" + name.substring(5);
        }
        return "the inline object generated as " + name;
    }

    private static Set<String> contractSchemas;

    /** Returns the schema names under components/schemas in the contract. */
    private static synchronized Set<String> contractSchemas() {
        if (contractSchemas == null) {
            contractSchemas = new HashSet<String>();
            String contract = System.getProperty("tebex.contract");
            if (contract != null) {
                try {
                    boolean inSchemas = false;
                    for (String line : Files.readAllLines(Paths.get(contract), StandardCharsets.UTF_8)) {
                        if (line.equals("  schemas:")) {
                            inSchemas = true;
                        } else if (inSchemas && line.matches("  \\S.*")) {
                            break;
                        } else if (inSchemas && line.matches("    [A-Za-z0-9_]+:\\s*")) {
                            contractSchemas.add(line.trim().replace(":", ""));
                        }
                    }
                } catch (IOException e) {
                    // Fall back to class names; the message is still useful without it.
                }
            }
        }
        return contractSchemas;
    }

    /** Describes an example value as its JSON type plus the value itself. */
    private static String describe(Object example) {
        if (example == null) {
            return "type unknown, value was null";
        }
        String type = example instanceof String ? "string"
                : example instanceof Number ? "number"
                : example instanceof Boolean ? "boolean"
                : example instanceof Map ? "object"
                : example instanceof List ? "array"
                : example.getClass().getSimpleName();
        String shown = example instanceof String ? "\"" + example + "\"" : String.valueOf(example);
        if (shown.length() > 60) {
            shown = shown.substring(0, 57) + "...";
        }
        return type + ", e.g. " + shown;
    }
}
