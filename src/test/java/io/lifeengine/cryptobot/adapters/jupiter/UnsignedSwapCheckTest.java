package io.lifeengine.cryptobot.adapters.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** KAN-1249: the bytes Jupiter builds are checked before they are even simulated. Fixture: a real swap response. */
class UnsignedSwapCheckTest {

    /** The fresh, unfunded address the fixture was built for (no key exists anywhere for it). */
    static final String OWNER = "5EKgeuMhPB7BUMnpnUof4bwutSdwP3nCokfJSAKQGRLa";

    static String fixture(String name) {
        try (InputStream in = UnsignedSwapCheckTest.class.getResourceAsStream("/jupiter/" + name)) {
            return new String(in.readAllBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static byte[] realTx() throws Exception {
        String b64 = new ObjectMapper().readTree(fixture("swap-sol-usdc.json")).path("swapTransaction").asText();
        return Base64.getDecoder().decode(b64);
    }

    private static String b64(byte[] tx) {
        return Base64.getEncoder().encodeToString(tx);
    }

    @Test
    void theRealJupiterTransactionPasses() throws Exception {
        UnsignedSwapCheck.Checked c = UnsignedSwapCheck.check(b64(realTx()), OWNER);

        assertThat(c.feePayer()).isEqualTo(OWNER);
        assertThat(c.programs()).contains(UnsignedSwapCheck.JUPITER_V6).allMatch(UnsignedSwapCheck.ALLOWED_PROGRAMS::contains);
        assertThat(c.lookupTables()).isPositive();
    }

    @Test
    void aTransactionForAnotherFeePayerIsRefused() throws Exception {
        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(realTx()), "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"))
                .isInstanceOf(SwapRejectedException.class)
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("fee_payer_mismatch"));
    }

    @Test
    void anAlreadySignedTransactionIsRefused() throws Exception {
        byte[] tx = realTx();
        tx[1] = 7; // first byte of the signature slot
        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(tx), OWNER))
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("already_signed"));
    }

    @Test
    void moreThanOneSignerIsRefused() throws Exception {
        byte[] tx = realTx();
        tx[0] = 2;
        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(tx), OWNER))
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("unexpected_signers"));
    }

    @Test
    void aProgramOffTheAllowListIsRefused() throws Exception {
        byte[] tx = realTx();
        // swap the Jupiter program id for another key among the static keys: same length, different program
        byte[] jup = Base58.decode(UnsignedSwapCheck.JUPITER_V6);
        int at = indexOf(tx, jup);
        assertThat(at).isPositive();
        byte[] other = Base58.decode("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin");
        System.arraycopy(other, 0, tx, at, 32);

        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(tx), OWNER))
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("program_not_allowed"));
    }

    @Test
    void garbageIsRefusedNotCrashed() {
        assertThatThrownBy(() -> UnsignedSwapCheck.check("%%%", OWNER))
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("malformed_transaction"));
        assertThatThrownBy(() -> UnsignedSwapCheck.check(b64(new byte[] {1, 0, 0}), OWNER))
                .satisfies(ex -> assertThat(((SwapRejectedException) ex).reason()).isEqualTo("malformed_transaction"));
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
