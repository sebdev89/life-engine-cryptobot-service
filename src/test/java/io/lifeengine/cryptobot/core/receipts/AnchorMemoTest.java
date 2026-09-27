package io.lifeengine.cryptobot.core.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnchorMemoTest {

    static final String ROOT = Digests.sha256("root");

    @Test
    @DisplayName("ir/1 root=… n=… ts=… — seconds precision, round-trips, fits in one memo")
    void formatAndParse() {
        AnchorMemo memo = new AnchorMemo(ROOT, 42, Instant.parse("2026-09-18T03:04:05.678Z"));
        assertThat(memo.text()).isEqualTo("ir/1 root=" + ROOT + " n=42 ts=2026-09-18T03:04:05Z");
        assertThat(memo.text().getBytes().length).isLessThan(AnchorMemo.MAX_BYTES);
        assertThat(AnchorMemo.parse(memo.text())).contains(new AnchorMemo(ROOT, 42, Instant.parse("2026-09-18T03:04:05Z")));
        assertThat(AnchorMemo.matches(memo.text())).isTrue();
    }

    @Test
    @DisplayName("anything that is not exactly the format is not a memo: no schema, upper-case hex, n=0, extra text, milliseconds")
    void rejectsVariants() {
        assertThat(AnchorMemo.parse("root=" + ROOT + " n=1 ts=2026-09-18T03:04:05Z")).isEmpty();
        assertThat(AnchorMemo.parse("ir/1 root=" + ROOT.toUpperCase() + " n=1 ts=2026-09-18T03:04:05Z")).isEmpty();
        assertThat(AnchorMemo.parse("ir/1 root=" + ROOT + " n=0 ts=2026-09-18T03:04:05Z")).isEmpty();
        assertThat(AnchorMemo.parse("ir/1 root=" + ROOT + " n=1 ts=2026-09-18T03:04:05Z extra")).isEmpty();
        assertThat(AnchorMemo.parse("ir/1 root=" + ROOT + " n=1 ts=2026-09-18T03:04:05.123Z")).isEmpty();
        assertThat(AnchorMemo.parse("ir/2 root=" + ROOT + " n=1 ts=2026-09-18T03:04:05Z")).isEmpty();
        assertThat(AnchorMemo.parse(null)).isEmpty();
        assertThatThrownBy(() -> new AnchorMemo(ROOT, 0, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorMemo("nope", 1, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
