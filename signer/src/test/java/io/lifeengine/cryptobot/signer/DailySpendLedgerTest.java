package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KAN-602: the signer's rolling 24 h velocity cap — independent, persisted, fail-closed. */
class DailySpendLedgerTest {

    private static final long CAP = 3_000_000L;

    @TempDir Path dir;

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-22T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static SignerProperties props(String ledgerPath, boolean allowMainnet) {
        return new SignerProperties("", "", "t", "devnet", 1_000_000_000L, List.of(), true, "", false, allowMainnet, CAP, ledgerPath);
    }

    @Test
    void reservesUpToTheDailyCapAndRefusesBeyondIt() {
        DailySpendLedger ledger = new DailySpendLedger(props("", false), new MovingClock());

        assertThat(ledger.tryReserve(1_000_000L)).isTrue();
        assertThat(ledger.tryReserve(2_000_000L)).isTrue();
        assertThat(ledger.spentInWindow()).isEqualTo(3_000_000L);
        // one lamport over the cap is refused, and a refusal records nothing
        assertThat(ledger.tryReserve(1L)).isFalse();
        assertThat(ledger.spentInWindow()).isEqualTo(3_000_000L);
    }

    @Test
    void theWindowRollsAfter24Hours() {
        MovingClock clock = new MovingClock();
        DailySpendLedger ledger = new DailySpendLedger(props("", false), clock);
        assertThat(ledger.tryReserve(3_000_000L)).isTrue();

        clock.now = clock.now.plus(Duration.ofHours(23)).plusSeconds(3599);
        assertThat(ledger.tryReserve(1L)).isFalse();

        clock.now = clock.now.plusSeconds(1);
        assertThat(ledger.tryReserve(3_000_000L)).isTrue();
    }

    @Test
    void aRestartDoesNotResetAPersistedWindow() throws Exception {
        Path file = dir.resolve("spend.ledger");
        MovingClock clock = new MovingClock();
        assertThat(new DailySpendLedger(props(file.toString(), false), clock).tryReserve(2_500_000L)).isTrue();

        DailySpendLedger afterRestart = new DailySpendLedger(props(file.toString(), false), clock);

        assertThat(afterRestart.spentInWindow()).isEqualTo(2_500_000L);
        assertThat(afterRestart.tryReserve(1_000_000L)).isFalse();
        assertThat(Files.readString(file)).contains(" 2500000");
    }

    @Test
    void mainnetWithoutAPersistedLedgerDoesNotStart() {
        assertThatThrownBy(() -> new DailySpendLedger(props("", true), new MovingClock()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spend-ledger-path");
    }

    @Test
    void aCorruptLedgerDoesNotStartRatherThanForgetWhatWasSigned() throws Exception {
        Path file = dir.resolve("spend.ledger");
        Files.writeString(file, "1792512000000 not-a-number\n");

        assertThatThrownBy(() -> new DailySpendLedger(props(file.toString(), false), new MovingClock()))
                .isInstanceOf(IllegalStateException.class);
    }
}
