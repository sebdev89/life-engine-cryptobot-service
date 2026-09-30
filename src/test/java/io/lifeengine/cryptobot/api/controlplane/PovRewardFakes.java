package io.lifeengine.cryptobot.api.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * KAN-822: the signer and the validator as the service sees them for a payout — both HTTP fakes, the chain is
 * {@link AnchorFlowTest.DevnetDispatcher}. The signer really signs (with {@link AnchorFlowTest#SIGNER_KEY}) and, like
 * {@code SIGNER_ALLOWED_DESTINATIONS}, refuses bytes that pay a destination it does not allow; it also refuses a request
 * whose attestation is not for these bytes. The validator answers ALLOW for the facts it is handed and records them.
 */
final class PovRewardFakes {

    static final ObjectMapper JSON = new ObjectMapper();

    private PovRewardFakes() {}

    /** Signer: payouts to any destination in {@link #BLOCKED} are refused with {@code destination_not_allowed}. */
    static final class Signer extends Dispatcher {
        static final Set<String> BLOCKED = new CopyOnWriteArraySet<>();
        static final List<String> SIGNED_FOR = new CopyOnWriteArrayList<>();
        private final AnchorFlowTest.SignerDispatcher anchors = new AnchorFlowTest.SignerDispatcher();

        static void reset() {
            BLOCKED.clear();
            SIGNED_FOR.clear();
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            if (!"POST".equals(request.getMethod()) || !"/api/signer/sign".equals(request.getPath())) {
                return anchors.dispatch(request);
            }
            try {
                JsonNode body = JSON.readTree(request.getBody().readUtf8());
                byte[] wire = Base64.getDecoder().decode(body.path("unsignedTransactionBase64").asText());
                byte[] message = Arrays.copyOfRange(wire, 65, wire.length);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message));
                if (!body.path("attestation").path("payload").asText().contains("\"message_hash\":\"" + hash + "\"")) {
                    return json(403, "{\"reason\":\"attestation_message_mismatch\"}");
                }
                for (String blocked : BLOCKED) {
                    if (indexOf(message, Base58.decode(blocked)) >= 0) {
                        return json(403, "{\"reason\":\"destination_not_allowed\"}");
                    }
                }
                byte[] sig = AnchorFlowTest.SIGNER_KEY.sign(message);
                byte[] signed = new byte[1 + 64 + message.length];
                signed[0] = 1;
                System.arraycopy(sig, 0, signed, 1, 64);
                System.arraycopy(message, 0, signed, 65, message.length);
                SIGNED_FOR.add(body.path("proposalId").asText());
                return json(200, "{\"signedTransactionBase64\":\"" + Base64.getEncoder().encodeToString(signed) + "\",\"signer\":\""
                        + AnchorFlowTest.SIGNER_KEY.publicKeyBase58() + "\"}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= haystack.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    /** Validator: ALLOW for whatever (I, S) arrives, bound to the message hash and cluster it was asked about. */
    static final class Validator extends Dispatcher {
        static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();

        static void reset() {
            REQUESTS.clear();
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if (!"validator-token".equals(request.getHeader("X-Validator-Token"))) {
                return json(401, "{\"reason\":\"bad_token\"}");
            }
            try {
                if ("GET".equals(request.getMethod())) {
                    return json(200, "{\"publicKey\":\"validator-key\",\"policyVersion\":\"cryptobot-policy-v1\",\"policyHash\":null,\"pinned\":false,"
                            + "\"enabled\":true}");
                }
                JsonNode body = JSON.readTree(request.getBody().readUtf8());
                REQUESTS.add(body);
                String payload = "{\"cluster\":\"" + body.path("cluster").asText() + "\",\"decision\":\"ALLOW\",\"message_hash\":\""
                        + body.path("messageHash").asText() + "\",\"proposal_id\":\"" + body.path("proposalId").asText() + "\"}";
                return json(200, "{\"decision\":\"ALLOW\",\"escalation\":\"NONE\",\"tier\":\"AUTONOMOUS\",\"failedPredicates\":[],\"refusals\":[],"
                        + "\"policyVersion\":\"cryptobot-policy-v1\",\"policyHash\":\"" + body.path("policyHash").asText() + "\",\"inputHash\":\"sha256:"
                        + "c".repeat(64) + "\",\"verdictHash\":\"" + body.path("expectedVerdictHash").asText() + "\",\"issuedAt\":1,\"expiresAt\":2,"
                        + "\"attestation\":{\"payload\":" + JSON.writeValueAsString(payload) + ",\"signature\":\"sig\",\"validator\":\"validator-key\"}}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }
    }

    static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
