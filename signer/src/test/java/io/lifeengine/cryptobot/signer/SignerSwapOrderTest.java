package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.signer.observability.SignerMetrics;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The swap path wired by hand, with a key store that counts signatures: a refusal at any step after the policy —
 * the daily cap included — never reaches the key, and a signer that does not require attestations never signs a swap.
 */
class SignerSwapOrderTest {

    /** Counts every signature the key produces. */
    static final class CountingKeyStore extends SignerKeyStore {
        final AtomicInteger signatures = new AtomicInteger();

        CountingKeyStore(SignerProperties props) {
            super(props);
        }

        @Override
        byte[] sign(byte[] message) {
            signatures.incrementAndGet();
            return super.sign(message);
        }
    }

    static SignerProperties props(boolean requireAttestation, long maxLamportsPerDay) {
        return new SignerProperties("", SigningPolicyTest.keyJson(SignerSwapEndpointTest.KEY), "t", "devnet", 2_000_000_000L, List.of(), true,
                SignerSwapEndpointTest.VALIDATOR.publicKeyBase58(), requireAttestation, false, maxLamportsPerDay, "");
    }

    static final SwapProperties SWAPS = new SwapProperties(true, null, null, null, null, 50_000_000L, 4_700_000L, null);

    static SignerController controller(SignerProperties props, CountingKeyStore keys) {
        return new SignerController(props, keys, new SigningPolicy(props, keys), new AttestationVerifier(props), SignerMetrics.noop(),
                new DailySpendLedger(props, Clock.systemUTC()), SWAPS, new UsdcSpendLedger(props, SWAPS, Clock.systemUTC()));
    }

    static ResponseEntity<?> sign(SignerProperties props, CountingKeyStore keys) {
        byte[] wire = SignerSwapEndpointTest.ours();
        AttestationVerifier.Attestation att = SignerSwapEndpointTest.attest("o-1", SignerSwapEndpointTest.message(wire), SignerSwapEndpointTest.BOTH);
        return controller(props, keys)
                .signSwap("t", new SignerController.SwapSignRequest("o-1", Base64.getEncoder().encodeToString(wire), "devnet", 1_000_000L, att)).block();
    }

    static Object reason(ResponseEntity<?> r) {
        return ((Map<?, ?>) r.getBody()).get("reason");
    }

    @Test
    void aDailyCapRefusalNeverReachesTheKey() {
        SignerProperties tight = props(true, 1_000L); // far below one swap's ~10 M lamports
        CountingKeyStore keys = new CountingKeyStore(tight);

        ResponseEntity<?> r = sign(tight, keys);

        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(((Map<?, ?>) r.getBody()).get("reason")).isEqualTo("daily_cap");
        assertThat(keys.signatures.get()).isZero();
    }

    @Test
    void theSameSwapWithRoomInTheCapIsSignedOnce() {
        SignerProperties roomy = props(true, 50_000_000L);
        CountingKeyStore keys = new CountingKeyStore(roomy);
        assertThat(sign(roomy, keys).getStatusCode().value()).isEqualTo(200);
        assertThat(keys.signatures.get()).isEqualTo(1);
    }

    @Test
    void aSignerThatDoesNotRequireAttestationsNeverSignsASwap() {
        SignerProperties lax = props(false, 50_000_000L);
        CountingKeyStore keys = new CountingKeyStore(lax);

        ResponseEntity<?> r = sign(lax, keys);

        assertThat(((Map<?, ?>) r.getBody()).get("reason")).isEqualTo("attestation_not_required");
        assertThat(keys.signatures.get()).isZero();
    }

    @Test
    void aMissingOrUnreadableTransactionIsRefusedBeforeAnythingElse() {
        SignerProperties p = props(true, 50_000_000L);
        CountingKeyStore keys = new CountingKeyStore(p);
        SignerController c = controller(p, keys);
        ResponseEntity<?> missing = c.signSwap("t", new SignerController.SwapSignRequest("o-2", " ", "devnet", 1L, null)).block();
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(reason(missing)).isEqualTo("missing_transaction");
        assertThat(reason(c.signSwap("t", null).block())).isEqualTo("missing_transaction");
        assertThat(reason(c.signSwap("t", new SignerController.SwapSignRequest("o-3", "%%%not base64", "devnet", 1L, null)).block()))
                .isEqualTo("undecodable_transaction");
        assertThat(c.signSwap("wrong-token", new SignerController.SwapSignRequest("o-4", "AA==", "devnet", 1L, null)).block().getStatusCode().value())
                .isEqualTo(401);
        assertThat(keys.signatures.get()).isZero();
    }

    @Test
    void aLedgerThatCannotPersistRefusesAndTheKeyIsNeverUsed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // the ledger file lives in a directory that disappears after start: persisting the reservation fails
        java.nio.file.Path gone = dir.resolve("gone");
        java.nio.file.Files.createDirectory(gone);
        SignerProperties p = new SignerProperties("", SigningPolicyTest.keyJson(SignerSwapEndpointTest.KEY), "t", "devnet", 2_000_000_000L, List.of(), true,
                SignerSwapEndpointTest.VALIDATOR.publicKeyBase58(), true, false, 50_000_000L, gone.resolve("spend.ledger").toString());
        CountingKeyStore keys = new CountingKeyStore(p);
        SignerController c = controller(p, keys);
        java.nio.file.Files.delete(gone);

        byte[] wire = SignerSwapEndpointTest.ours();
        AttestationVerifier.Attestation att = SignerSwapEndpointTest.attest("o-5", SignerSwapEndpointTest.message(wire), SignerSwapEndpointTest.BOTH);
        ResponseEntity<?> r = c.signSwap("t", new SignerController.SwapSignRequest("o-5", Base64.getEncoder().encodeToString(wire), "devnet", 1_000_000L,
                att)).block();

        assertThat(reason(r)).isEqualTo("spend_ledger_unavailable");
        assertThat(keys.signatures.get()).isZero();
    }

    @Test
    void theUsdcLedgerIsItsOwnFileNextToTheLamportsOne(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String path = dir.resolve("spend.ledger").toString();
        SignerProperties p = new SignerProperties("", "", "t", "devnet", 1L, List.of(), true, "", true, false, 50_000_000L, path);
        UsdcSpendLedger usdc = new UsdcSpendLedger(p, SWAPS, Clock.systemUTC());
        assertThat(usdc.ledger().maxPerDay()).isEqualTo(50_000_000L);
        assertThat(usdc.ledger().tryReserve(1_000_000L)).isTrue();
        assertThat(java.nio.file.Files.readString(dir.resolve("spend.ledger.usdc"))).contains(" 1000000");
        assertThat(dir.resolve("spend.ledger")).doesNotExist();
        // without a path it stays in memory
        SignerProperties memory = new SignerProperties("", "", "t", "devnet", 1L, List.of(), true, "", true, false, 50_000_000L, "");
        assertThat(new UsdcSpendLedger(memory, SWAPS, Clock.systemUTC()).ledger().tryReserve(1L)).isTrue();
        assertThat(java.nio.file.Path.of(".usdc")).as("no ledger file without a ledger path").doesNotExist();
    }

    @Test
    void swapsOnDevnetDoNotNeedTheMainnetGate() {
        new SwapGuard(props(true, 1L), SWAPS);
    }
}
