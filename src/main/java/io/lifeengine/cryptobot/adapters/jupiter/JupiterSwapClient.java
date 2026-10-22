package io.lifeengine.cryptobot.adapters.jupiter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Jupiter Swap API v1, keyless ({@code GET /swap/v1/quote}, {@code POST /swap/v1/swap}): a quote for an exact-in
 * swap and the unsigned transaction that executes it for a given owner. Read-only towards us — Jupiter gets a
 * public key and nothing else, and what it builds is only checked and simulated (KAN-1249). This client has no way
 * to sign or broadcast: it does not know the signer or {@code sendTransaction}, and ModuleBoundariesTest keeps it so.
 *
 * <p>Every answer is checked against the request (mints, amount, mode, slippage) because the quote travels back
 * into the swap request verbatim; a quote for something else is refused, not corrected.
 */
public class JupiterSwapClient {

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;

    public JupiterSwapClient(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(properties.jupiterBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<SwapQuote> quote(String inputMint, String outputMint, long amount, int slippageBps) {
        if (amount <= 0 || slippageBps < 0 || slippageBps > 10_000) {
            return Mono.error(new IllegalArgumentException("amount must be > 0 and slippageBps within 0..10000"));
        }
        return webClient
                .get()
                .uri(b -> b.path("/swap/v1/quote")
                        .queryParam("inputMint", inputMint)
                        .queryParam("outputMint", outputMint)
                        .queryParam("amount", amount)
                        .queryParam("slippageBps", slippageBps)
                        .queryParam("swapMode", "ExactIn")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parseQuote(raw, inputMint, outputMint, amount, slippageBps));
    }

    public Mono<UnsignedSwap> buildSwap(SwapQuote quote, String ownerPublicKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quoteResponse", quote.raw());
        body.put("userPublicKey", ownerPublicKey);
        body.put("wrapAndUnwrapSol", true);
        body.put("dynamicComputeUnitLimit", true);
        return webClient
                .post()
                .uri("/swap/v1/swap")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(this::parseSwap);
    }

    private SwapQuote parseQuote(String raw, String inputMint, String outputMint, long amount, int slippageBps) {
        JsonNode q = read(raw);
        expect("inputMint", inputMint, q.path("inputMint").asText());
        expect("outputMint", outputMint, q.path("outputMint").asText());
        expect("inAmount", Long.toString(amount), q.path("inAmount").asText());
        expect("swapMode", "ExactIn", q.path("swapMode").asText());
        expect("slippageBps", Integer.toString(slippageBps), q.path("slippageBps").asText());
        long out = Long.parseLong(q.path("outAmount").asText("0"));
        long min = Long.parseLong(q.path("otherAmountThreshold").asText("0"));
        if (out <= 0 || min <= 0 || min > out) {
            throw new SwapRejectedException("quote_mismatch", "outAmount=" + out + " otherAmountThreshold=" + min);
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode step : q.path("routePlan")) {
            labels.add(step.path("swapInfo").path("label").asText());
        }
        BigDecimal impact = q.hasNonNull("priceImpactPct") ? new BigDecimal(q.get("priceImpactPct").asText()) : null;
        return new SwapQuote(inputMint, outputMint, amount, out, min, slippageBps, impact, labels, q);
    }

    private UnsignedSwap parseSwap(String raw) {
        JsonNode s = read(raw);
        String tx = s.path("swapTransaction").asText("");
        if (tx.isEmpty()) {
            throw new SwapRejectedException("malformed_transaction", "no swapTransaction in the response");
        }
        JsonNode simErr = s.path("simulationError");
        String jupiterError = simErr.isMissingNode() || simErr.isNull() ? null : simErr.path("error").asText(simErr.toString());
        return new UnsignedSwap(tx, s.path("lastValidBlockHeight").asLong(), s.path("prioritizationFeeLamports").asLong(),
                s.path("computeUnitLimit").asLong(), jupiterError);
    }

    private JsonNode read(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Jupiter swap response", ex);
        }
    }

    private static void expect(String field, String wanted, String got) {
        if (!wanted.equals(got)) {
            throw new SwapRejectedException("quote_mismatch", field + "=" + got + ", asked " + wanted);
        }
    }
}
