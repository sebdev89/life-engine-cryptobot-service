package io.lifeengine.cryptobot.application.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.ExecutionProperties;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * the demo's faulty RPC. Same client, same node, same bytes; the three calls the
 * execution path makes <em>after signing</em> can be made to fail on demand (see
 * {@link BroadcastChaos}). The failure is always a transport-shaped {@link SolanaRpcException}
 * (a cause is set), which is exactly what the production code treats as <em>uncertain</em> — never
 * a node-side rejection, which it would rightly treat as FAILED. Exists only when
 * {@code cryptobot.chaos.enabled=true} ({@link ChaosConfiguration}).
 */
public class ChaosSolanaRpcClient extends SolanaRpcClient {

    private static final Logger log = LoggerFactory.getLogger(ChaosSolanaRpcClient.class);

    private final BroadcastChaos chaos;

    public ChaosSolanaRpcClient(WebClient.Builder builder, SolanaRpcProperties properties, ObjectMapper objectMapper, CryptobotMetrics metrics,
            ExecutionProperties execution, BroadcastChaos chaos) {
        super(builder, properties, objectMapper, metrics, execution);
        this.chaos = chaos;
    }

    @Override
    public Mono<String> sendTransaction(SolanaCluster cluster, String signedTransactionBase64) {
        return Mono.defer(() -> {
            BroadcastChaos.Mode m = chaos.consume("sendTransaction");
            if (m == BroadcastChaos.Mode.UNCERTAIN) {
                // The bytes DO reach the node; the answer never reaches us.
                return super.sendTransaction(cluster, signedTransactionBase64)
                        .flatMap(sig -> {
                            chaos.record(m, "sendTransaction", "broadcast for real (signature " + sig + "), response dropped");
                            log.warn("chaos_injected mode={} method=sendTransaction signature={} — response dropped", m.id(), sig);
                            return Mono.<String>error(fault("sendTransaction", m, new TimeoutException("chaos: response to sendTransaction lost")));
                        });
            }
            if (m == BroadcastChaos.Mode.RPC_DOWN) {
                chaos.record(m, "sendTransaction", "not sent: connection refused");
                log.warn("chaos_injected mode={} method=sendTransaction — nothing sent", m.id());
                return Mono.error(fault("sendTransaction", m, new ConnectException("chaos: rpc down")));
            }
            return super.sendTransaction(cluster, signedTransactionBase64);
        });
    }

    @Override
    public Mono<SignatureStatus> getSignatureStatus(SolanaCluster cluster, String signature) {
        return Mono.defer(() -> {
            BroadcastChaos.Mode m = chaos.consume("getSignatureStatuses");
            if (m != null) {
                chaos.record(m, "getSignatureStatuses", "no answer for " + signature);
                log.warn("chaos_injected mode={} method=getSignatureStatuses signature={}", m.id(), signature);
                return Mono.error(fault("getSignatureStatuses", m, m == BroadcastChaos.Mode.RPC_DOWN
                        ? new ConnectException("chaos: rpc down") : new TimeoutException("chaos: confirmation never arrived")));
            }
            return super.getSignatureStatus(cluster, signature);
        });
    }

    @Override
    public Mono<Long> getBlockHeight(SolanaCluster cluster) {
        return Mono.defer(() -> {
            BroadcastChaos.Mode m = chaos.consume("getBlockHeight");
            if (m != null) {
                chaos.record(m, "getBlockHeight", "no answer");
                log.warn("chaos_injected mode={} method=getBlockHeight", m.id());
                return Mono.error(fault("getBlockHeight", m, new ConnectException("chaos: rpc down")));
            }
            return super.getBlockHeight(cluster);
        });
    }

    private static SolanaRpcException fault(String method, BroadcastChaos.Mode mode, Exception cause) {
        return new SolanaRpcException(method, -1, "Solana RPC call failed (chaos " + mode.id() + "): " + cause.getMessage(), null, cause);
    }
}
