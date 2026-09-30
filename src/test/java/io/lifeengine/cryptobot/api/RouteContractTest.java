package io.lifeengine.cryptobot.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

/**
 * KAN-595 (TAE phase 1, audit §21/§22 item 4): "/api/cryptobot/** inalteradas" — the package move
 * touched imports, never a controller's {@code @RequestMapping}. This is the mechanical proof: the
 * exact set of {@code METHOD path} pairs registered under {@code /api/cryptobot} today, so a future
 * PR that accidentally renames or drops one of them (the audit's 6 literal Grafana {@code uri}
 * values, the UI's {@code control-plane-api.ts}/{@code receipts-api.ts}/{@code lineage-api.ts}, the
 * demo scripts, {@code smoke-cryptobot-devnet.sh}) fails here instead of in a dashboard "No data" or
 * a 404 nobody notices. Phase 2 (KAN-457/KAN-597) is explicitly additive — this list only grows.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// cryptobot.chaos.enabled=true: the demo-only ChaosController/PriceChaosController routes are also
// part of the contract (the demo script and the chaos runbook depend on them existing).
@TestPropertySource(properties = {"cryptobot.chaos.enabled=true"})
class RouteContractTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private static final Set<String> EXPECTED = Set.of(
            "GET /api/cryptobot/zones",
            "POST /api/cryptobot/zones",
            "POST /api/cryptobot/market-review",
            "GET /api/cryptobot/dead-letters",
            "GET /api/cryptobot/dead-letters/{id}",
            "POST /api/cryptobot/dead-letters/{id}/resolve",
            "POST /api/cryptobot/dead-letters/{id}/requeue",
            "GET /api/cryptobot/snapshots/{symbol}",
            "GET /api/cryptobot/market-reviews/latest",
            "GET /api/cryptobot/market-reviews",
            "POST /api/cryptobot/glossary/events",
            "GET /api/cryptobot/indicators",
            "POST /api/cryptobot/wallets",
            "GET /api/cryptobot/wallets",
            "GET /api/cryptobot/wallets/{walletId}/portfolio",
            "POST /api/cryptobot/wallets/{walletId}/refresh",
            "GET /api/cryptobot/wallets/{walletId}/activity",
            "POST /api/cryptobot/wallets/{walletId}/ask",
            "GET /api/cryptobot/wallets/{walletId}/messages",
            "POST /api/cryptobot/wallets/{walletId}/proposals",
            "GET /api/cryptobot/wallets/{walletId}/proposals",
            "GET /api/cryptobot/demo/chaos",
            "PUT /api/cryptobot/demo/chaos",
            "DELETE /api/cryptobot/demo/chaos",
            "GET /api/cryptobot/journal",
            "POST /api/cryptobot/journal",
            "GET /api/cryptobot/proposals",
            "GET /api/cryptobot/proposals/{proposalId}",
            "GET /api/cryptobot/proposals/{proposalId}/audit",
            "GET /api/cryptobot/proposals/{proposalId}/events",
            "POST /api/cryptobot/proposals/{proposalId}/approve",
            "POST /api/cryptobot/proposals/{proposalId}/reject",
            "POST /api/cryptobot/proposals/{proposalId}/cancel",
            "POST /api/cryptobot/proposals/{proposalId}/execute",
            "GET /api/cryptobot/quotes/{asset}",
            "GET /api/cryptobot/receipts/{receiptHash}/lineage",
            "GET /api/cryptobot/receipts/{receiptHash}/ancestors",
            "GET /api/cryptobot/receipts/{receiptHash}/descendants",
            "GET /api/cryptobot/receipts/{receiptHash}/parents",
            "GET /api/cryptobot/receipts/{receiptHash}/children",
            "GET /api/cryptobot/receipts/{receiptHash}/reused-by",
            "GET /api/cryptobot/proposals/{proposalId}/lineage",
            "POST /api/cryptobot/monitoring/run-once",
            "GET /api/cryptobot/observations",
            "POST /api/cryptobot/observations",
            "GET /api/cryptobot/watchlist",
            "POST /api/cryptobot/watchlist",
            "POST /api/cryptobot/anchors",
            "GET /api/cryptobot/anchors",
            "GET /api/cryptobot/anchors/{root}",
            "POST /api/cryptobot/anchors/{root}/verify",
            "GET /api/cryptobot/demo/price",
            "PUT /api/cryptobot/demo/price",
            "DELETE /api/cryptobot/demo/price",
            "GET /api/cryptobot/receipts/signing-key",
            "GET /api/cryptobot/receipts/{receiptHash}",
            "POST /api/cryptobot/receipts/{receiptHash}/verify",
            "GET /api/cryptobot/proposals/{proposalId}/receipts",
            "GET /api/cryptobot/wallets/{walletId}/receipts",
            // KAN-818: Proof of Value V1 — additive, new resource.
            "POST /api/cryptobot/identities",
            "GET /api/cryptobot/identities",
            "GET /api/cryptobot/identities/{id}",
            "POST /api/cryptobot/value-events",
            "GET /api/cryptobot/value-events",
            "GET /api/cryptobot/value-events/{id}",
            "GET /api/cryptobot/value-events/{id}/proof",
            "GET /api/cryptobot/health");

    @Test
    @DisplayName("todas las rutas /api/cryptobot/** siguen siendo exactamente las mismas (método + patrón)")
    void cryptobotRoutesAreUnchanged() {
        Set<String> actual = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod method = entry.getValue();
            if (!method.getBeanType().getPackageName().startsWith("io.lifeengine.cryptobot")) {
                continue; // actuator, error handling, etc. are not this contract
            }
            Set<String> patterns = info.getPatternsCondition().getPatterns().stream()
                    .map(org.springframework.web.util.pattern.PathPattern::getPatternString)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of("GET") // no explicit method = matches any; every controller here declares one
                    : info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/cryptobot")) {
                    continue;
                }
                for (String httpMethod : methods) {
                    actual.add(httpMethod + " " + pattern);
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }
}
