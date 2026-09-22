package io.lifeengine.cryptobot.e2e.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.cryptobot.adapters.solana.Base58;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/**
 * A transparent HTTP relay in front of a real process (the validator or the signer), so a scenario
 * can model a network fault without touching the process: {@link Mode#DOWN} closes the socket on
 * every request (unreachable), {@link Mode#TAMPER_ATTESTATION} forwards {@code /validate} and
 * replaces the Ed25519 signature of the attestation in the answer with a forged one — the
 * validator really attested, the bytes on the wire are no longer what it signed, and the signer
 * (which verifies against the pinned validator key) must refuse.
 */
final class Relay extends Dispatcher {

    enum Mode {
        NORMAL,
        DOWN,
        TAMPER_ATTESTATION
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORWARDED_HEADERS = Set.of("content-type", "accept", "x-signer-token", "x-validator-token");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    volatile Mode mode = Mode.NORMAL;
    private final String target;
    private final AtomicInteger signCalls = new AtomicInteger();
    private final AtomicInteger validateCalls = new AtomicInteger();

    Relay(String target) {
        this.target = target.replaceAll("/$", "");
    }

    int signCalls() {
        return signCalls.get();
    }

    int validateCalls() {
        return validateCalls.get();
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String path = request.getPath();
        if (mode == Mode.DOWN) {
            return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target + path)).timeout(Duration.ofSeconds(20));
            for (String name : request.getHeaders().names()) {
                if (FORWARDED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    b.header(name, request.getHeader(name));
                }
            }
            byte[] body = request.getBody().readByteArray();
            b.method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String answer = resp.body() == null ? "" : resp.body();
            if (path != null && path.endsWith("/api/signer/sign")) {
                signCalls.incrementAndGet();
            }
            if (path != null && path.endsWith("/api/validator/validate")) {
                validateCalls.incrementAndGet();
                if (mode == Mode.TAMPER_ATTESTATION && resp.statusCode() == 200) {
                    answer = forgeAttestationSignature(answer);
                }
            }
            MockResponse out = new MockResponse().setResponseCode(resp.statusCode()).setBody(answer);
            resp.headers().firstValue("Content-Type").ifPresent(ct -> out.setHeader("Content-Type", ct));
            return out;
        } catch (Exception e) {
            return new MockResponse().setResponseCode(502).setHeader("Content-Type", "application/json")
                    .setBody("{\"reason\":\"relay: " + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Same payload (hashes, cluster, message hash all intact), a signature the validator never produced. */
    private static String forgeAttestationSignature(String json) throws Exception {
        JsonNode root = JSON.readTree(json);
        if (!root.isObject() || !root.path("attestation").isObject()) {
            return json;
        }
        byte[] forged = new byte[64];
        Arrays.fill(forged, (byte) 0x01);
        ((ObjectNode) root.path("attestation")).put("signature", Base58.encode(forged));
        return JSON.writeValueAsString(root);
    }
}
