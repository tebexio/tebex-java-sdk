package io.tebex.checkout.webhooks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.checkout.model.PaymentSubject;
import io.tebex.checkout.model.RecurringPaymentSubject;
import io.tebex.checkout.model.TebexWebhook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A store's webhook endpoint, handling deliveries the way a consumer would:
 * verify {@code X-Signature}, parse the {@link TebexWebhook} envelope, then
 * parse its {@code subject} by the webhook type.
 *
 * <p>The contract types {@code subject} as a plain object, so the generated
 * envelope keeps it as an untyped map; the subject is parsed from the raw JSON
 * with {@link PaymentSubject#fromJson} or {@link RecurringPaymentSubject#fromJson}.
 *
 * <p>Responds 403 to a bad signature, 500 (with the error as the body) if
 * parsing fails, and otherwise 200 with {@code {"id": ...}}, the response Tebex
 * requires to validate an endpoint.
 */
final class WebhookEndpoint implements AutoCloseable {

    /** One accepted delivery. {@code subject} is null for {@code validation.webhook}. */
    static final class Received {
        final TebexWebhook webhook;
        final Object subject;

        Received(TebexWebhook webhook, Object subject) {
            this.webhook = webhook;
            this.subject = subject;
        }
    }

    private final HttpServer server;
    private final String secret;
    private final AtomicReference<Received> last = new AtomicReference<Received>();

    WebhookEndpoint(String secret) throws IOException {
        this.secret = secret;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/webhook", this::handle);
        this.server.start();
    }

    /** The URL to deliver to. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    /** The most recent accepted delivery, or null if none was accepted. */
    Received last() {
        return last.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String payload = read(exchange.getRequestBody());
            String signature = exchange.getRequestHeaders().getFirst(SimulatedTebex.SIGNATURE_HEADER);
            if (!verify(payload, signature)) {
                respond(exchange, 403, "invalid signature");
                return;
            }
            TebexWebhook webhook = TebexWebhook.fromJson(payload);
            last.set(new Received(webhook, parseSubject(webhook.getType(), payload)));

            JsonObject response = new JsonObject();
            response.addProperty("id", webhook.getId());
            respond(exchange, 200, response.toString());
        } catch (Exception e) {
            respond(exchange, 500, e.toString());
        }
    }

    private boolean verify(String payload, String signature) {
        return signature != null && MessageDigest.isEqual(
                SimulatedTebex.sign(payload, secret).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static Object parseSubject(String type, String payload) throws IOException {
        if ("validation.webhook".equals(type)) {
            return null;
        }
        JsonElement subject = JsonParser.parseString(payload).getAsJsonObject().get("subject");
        if (type.startsWith("recurring-payment.")) {
            return RecurringPaymentSubject.fromJson(subject.toString());
        }
        if (type.startsWith("payment.")) {
            return PaymentSubject.fromJson(subject.toString());
        }
        throw new IOException("unhandled webhook type " + type);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) != -1; ) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
