package io.lifeengine.cryptobot.integration.signer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * HTTP client for {@code cryptobot-signer}. The service sends an <b>unsigned</b> transaction and
 * gets a signed one back. It never sees the key; the signer never sees the LLM, the RPC or the DB.
 */
@Component
public class SignerClient {

    private static final Logger log = LoggerFactory.getLogger(SignerClient.class);
    public static final String TOKEN_HEADER = "X-Signer-Token";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Identity(String publicKey, String cluster, long maxLamports, List<String> allowedDestinations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignResponse(String signedTransactionBase64, String signer, String txHash) {}

    public static class SignerRefused extends RuntimeException {
        public SignerRefused(String reason) {
            super("Signer refused: " + reason);
        }
    }

    private final WebClient webClient;
    private final SignerProperties props;

    public SignerClient(WebClient.Builder builder, SignerProperties props) {
        this.webClient = builder.baseUrl(props.baseUrl()).build();
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Empty when the signer is disabled or unreachable — the policy engine treats that as "not executable". */
    public Mono<Optional<Identity>> identity() {
        if (!props.enabled()) {
            return Mono.just(Optional.empty());
        }
        return webClient
                .get()
                .uri("/api/signer/identity")
                .header(TOKEN_HEADER, props.token())
                .retrieve()
                .bodyToMono(Identity.class)
                .timeout(props.timeout())
                .map(Optional::of)
                .onErrorResume(
                        ex -> {
                            log.warn("signer_identity_unavailable baseUrl={} error={}", props.baseUrl(), ex.toString());
                            return Mono.just(Optional.empty());
                        });
    }

    public Mono<SignResponse> sign(UUID proposalId, String unsignedTransactionBase64, String expectedFeePayer) {
        if (!props.enabled()) {
            return Mono.error(new SignerRefused("signer disabled"));
        }
        return webClient
                .post()
                .uri("/api/signer/sign")
                .header(TOKEN_HEADER, props.token())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "proposalId", proposalId.toString(),
                        "unsignedTransactionBase64", unsignedTransactionBase64,
                        "expectedFeePayer", expectedFeePayer))
                .retrieve()
                .bodyToMono(SignResponse.class)
                .timeout(props.timeout())
                .onErrorMap(WebClientResponseException.class, ex -> new SignerRefused("HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString()))
                .onErrorMap(ex -> !(ex instanceof SignerRefused), ex -> new SignerRefused(ex.getMessage()));
    }
}
