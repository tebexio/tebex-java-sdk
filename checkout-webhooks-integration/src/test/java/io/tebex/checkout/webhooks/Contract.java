package io.tebex.checkout.webhooks;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks parsed webhooks against apis/checkout-api.yaml. */
final class Contract {

    private static final String MODEL_PACKAGE = "io.tebex.checkout.model";

    private Contract() {
    }

    /**
     * Fails if any parsed model kept a property the contract does not define.
     * The generated models tolerate such properties (in
     * {@code additionalProperties}); this is where they are caught, so the
     * contract can be updated.
     */
    static void assertMatchesContract(Object parsed) {
        List<String> extras = new ArrayList<String>();
        collectUndeclared(parsed, "webhook", extras,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
        if (extras.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("The webhook has properties not defined in apis/checkout-api.yaml:");
        for (String extra : extras) {
            message.append("\n  - ").append(extra);
        }
        fail(message.toString());
    }

    private static void collectUndeclared(Object value, String path, List<String> out, Set<Object> seen) {
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
                    for (Object property : ((Map<?, ?>) child).keySet()) {
                        out.add("`" + property + "` on " + value.getClass().getSimpleName() + " at " + path);
                    }
                }
            } else {
                collectUndeclared(child, path + "." + getter.getName().substring(3), out, seen);
            }
        }
    }
}
