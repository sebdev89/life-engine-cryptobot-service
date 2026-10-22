package io.lifeengine.cryptobot.adapters.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * KAN-1249 / pilot: the bytes Jupiter builds are checked before they are even simulated. Fixture: a real Jupiter
 * legacy swap ({@code asLegacyTransaction=true}) built for the public Binance hot wallet, never signed.
 */
class UnsignedSwapCheckTest {

    /** Binance's public Solana hot wallet ("Binance 2" on Solscan): only its address is used. */
    static final String OWNER = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9";

    static String fixture(String name) {
        try (InputStream in = UnsignedSwapCheckTest.class.getResourceAsStream("/jupiter/" + name)) {
            return new String(in.readAllBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static byte[] tx(String name) throws Exception {
        return Base64.getDecoder().decode(new ObjectMapper().readTree(fixture(name)).path("swapTransaction").asText());
    }

    static byte[] realTx() throws Exception {
        return tx("swap-sol-usdc.json");
    }

    private static String b64(byte[] tx) {
        return Base64.getEncoder().encodeToString(tx);
    }

    private static String reason(String b64, String owner) {
        try {
            UnsignedSwapCheck.check(b64, owner);
            return null;
        } catch (SwapRejectedException ex) {
            return ex.reason();
        }
    }

    @Test
    void theRealJupiterLegacyTransactionPasses() throws Exception {
        UnsignedSwapCheck.Checked c = UnsignedSwapCheck.check(b64(realTx()), OWNER);

        assertThat(c.feePayer()).isEqualTo(OWNER);
        assertThat(c.programs()).contains(UnsignedSwapCheck.JUPITER_V6).allMatch(UnsignedSwapCheck.ALLOWED_PROGRAMS::contains);
    }

    @Test
    void aVersionedTransactionWithLookupTablesIsRefused() throws Exception {
        assertThat(reason(b64(tx("swap-sol-usdc-v0.json")), "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa")).isEqualTo("unexpected_version");
    }

    @Test
    void aTransactionForAnotherFeePayerIsRefused() throws Exception {
        assertThat(reason(b64(realTx()), "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin")).isEqualTo("fee_payer_mismatch");
    }

    @Test
    void anAlreadySignedTransactionIsRefused() throws Exception {
        byte[] tx = realTx();
        tx[1] = 7; // first byte of the signature slot
        assertThat(reason(b64(tx), OWNER)).isEqualTo("already_signed");
    }

    @Test
    void moreThanOneSignerIsRefused() throws Exception {
        byte[] tx = realTx();
        tx[0] = 2;
        assertThat(reason(b64(tx), OWNER)).isEqualTo("unexpected_signers");
    }

    @Test
    void aProgramOffTheAllowListIsRefused() throws Exception {
        byte[] tx = realTx();
        byte[] jup = Base58.decode(UnsignedSwapCheck.JUPITER_V6);
        int at = indexOf(tx, jup);
        assertThat(at).isPositive();
        System.arraycopy(Base58.decode("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"), 0, tx, at, 32);

        assertThat(reason(b64(tx), OWNER)).isEqualTo("program_not_allowed");
    }

    @Test
    void garbageOrTrailingBytesAreRefusedNotCrashed() throws Exception {
        assertThat(reason("%%%", OWNER)).isEqualTo("malformed_transaction");
        assertThat(reason(b64(new byte[] {1, 0, 0}), OWNER)).isEqualTo("malformed_transaction");
        byte[] tx = realTx();
        assertThat(reason(b64(Arrays.copyOf(tx, tx.length + 1)), OWNER)).isEqualTo("malformed_transaction");
        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(Arrays.copyOf(tx, tx.length - 5)), OWNER)).isInstanceOf(SwapRejectedException.class);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        for (int i = 0; i + needle.length <= hay.length; i++) {
            if (Arrays.equals(hay, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
