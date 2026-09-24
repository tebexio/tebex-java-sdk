package io.tebex.checkout.webhooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Plays Tebex's part in a webhook delivery: signs a payload with the store's
 * webhook secret and POSTs it to an endpoint.
 *
 * <p>Tebex signs the SHA-256 hex digest of the raw body with HMAC-SHA256 keyed
 * by the webhook secret, and sends the hex result in {@code X-Signature}.
 */
final class SimulatedTebex {

    static final String SIGNATURE_HEADER = "X-Signature";

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final String secret;

    SimulatedTebex(String secret) {
        this.secret = secret;
    }

    /** The endpoint's response to one delivery. */
    static final class Delivery {
        final int status;
        final String body;

        Delivery(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** POSTs the payload signed with this instance's secret. */
    Delivery deliver(String endpoint, String payload) throws IOException {
        return deliver(endpoint, payload, sign(payload, secret));
    }

    /** POSTs the payload with an explicit signature, e.g. a forged one. */
    Delivery deliver(String endpoint, String payload, String signature) throws IOException {
        Request request = new Request.Builder()
                .url(endpoint)
                .header(SIGNATURE_HEADER, signature)
                .post(RequestBody.create(payload.getBytes(StandardCharsets.UTF_8), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return new Delivery(response.code(), response.body() == null ? "" : response.body().string());
        }
    }

    /** Returns the signature Tebex would send for the payload. */
    static String sign(String payload, String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(hex(digest).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 and HmacSHA256 are required on every JVM", e);
        }
    }

    /** Loads a payload from src/test/resources/webhooks, byte for byte. */
    static String fixture(String name) throws IOException {
        try (InputStream in = SimulatedTebex.class.getResourceAsStream("/webhooks/" + name)) {
            if (in == null) {
                throw new IOException("no webhook fixture named " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
