package io.lifeengine.cryptobot.solana.rpc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Public JSON-RPC endpoints per cluster. No API keys: CryptoBot only ever uses the public read
 * surface plus {@code sendTransaction} on devnet.
 *
 * <p>KAN-602: the cluster label is declarative; the genesis hash is not. Before the first {@code sendTransaction}
 * to a cluster the client asks the configured RPC for {@code getGenesisHash} and refuses to send if it is not the
 * expected one, so bytes for one network are never broadcast to another (a "devnet" URL that is really mainnet).
 * {@code expectedGenesisDevnet} may be set empty ({@code CRYPTOBOT_SOLANA_EXPECTED_GENESIS_DEVNET=}) for a local
 * solana-test-validator, whose genesis changes on every reset; the mainnet one can never be turned off.
 *
 * <p>KAN-1304 (Alpenglow): the commitment the reads, the simulation and the send preflight ask for, per cluster —
 * {@code auto} (default), {@code confirmed} or {@code finalized}. Under Alpenglow confirmed and finalized converge and
 * {@code confirmed} is deprecated, to be removed in a later release; before Alpenglow, {@code finalized} costs
 * ~12.8 s. {@code auto} asks the cluster's own RPC ({@code getAgGenesisCert}): {@code finalized} once it has a
 * certificate, {@code confirmed} while it does not. See {@link SolanaRpcClient#commitmentFor}.
 */
@ConfigurationProperties(prefix = "cryptobot.solana.rpc")
public record SolanaRpcProperties(String devnetUrl, String mainnetUrl, Duration timeout, String expectedGenesisDevnet, String expectedGenesisMainnet,
        String commitmentDevnet, String commitmentMainnet) {

    public static final String AUTO = "auto";
    public static final String CONFIRMED = "confirmed";
    public static final String FINALIZED = "finalized";

    public static final String DEVNET_GENESIS = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG";
    public static final String MAINNET_GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";

    @ConstructorBinding
    public SolanaRpcProperties {
        devnetUrl = blankTo(devnetUrl, "https://api.devnet.solana.com");
        mainnetUrl = blankTo(mainnetUrl, "https://api.mainnet-beta.solana.com");
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
        // null = not configured -> the real devnet genesis; "" = deliberately off (local validator only)
        expectedGenesisDevnet = expectedGenesisDevnet == null ? DEVNET_GENESIS : expectedGenesisDevnet.trim();
        // mainnet: never off
        expectedGenesisMainnet = blankTo(expectedGenesisMainnet, MAINNET_GENESIS);
        commitmentDevnet = commitment(commitmentDevnet, "devnet");
        commitmentMainnet = commitment(commitmentMainnet, "mainnet");
    }

    /** The shape before KAN-1304: the commitment fixed at {@code confirmed}, as it was (tests that stub the RPC). */
    public SolanaRpcProperties(String devnetUrl, String mainnetUrl, Duration timeout, String expectedGenesisDevnet, String expectedGenesisMainnet) {
        this(devnetUrl, mainnetUrl, timeout, expectedGenesisDevnet, expectedGenesisMainnet, CONFIRMED, CONFIRMED);
    }

    /** The shape before KAN-602 (tests that stub the RPC without getGenesisHash): devnet check off, mainnet on. */
    public SolanaRpcProperties(String devnetUrl, String mainnetUrl, Duration timeout) {
        this(devnetUrl, mainnetUrl, timeout, "", null);
    }

    /** {@code auto}, {@code confirmed} or {@code finalized} for {@code cluster}. */
    public String commitmentSettingFor(SolanaCluster cluster) {
        return cluster == SolanaCluster.MAINNET_BETA ? commitmentMainnet : commitmentDevnet;
    }

    private static String commitment(String value, String cluster) {
        String v = value == null || value.isBlank() ? AUTO : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!v.equals(AUTO) && !v.equals(CONFIRMED) && !v.equals(FINALIZED)) {
            throw new IllegalArgumentException("cryptobot.solana.rpc.commitment-" + cluster + " must be auto, confirmed or finalized, was " + value);
        }
        return v;
    }

    public String urlFor(SolanaCluster cluster) {
        return cluster == SolanaCluster.MAINNET_BETA ? mainnetUrl : devnetUrl;
    }

    /** The genesis hash the RPC of {@code cluster} must answer, or empty when the check is off (devnet only). */
    public String expectedGenesisFor(SolanaCluster cluster) {
        return cluster == SolanaCluster.MAINNET_BETA ? expectedGenesisMainnet : expectedGenesisDevnet;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
