package io.lifeengine.cryptobot.adapters.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Base58Test {

    @Test
    void systemProgramIdIsThirtyTwoZeroBytes() {
        byte[] decoded = Base58.decode("11111111111111111111111111111111");
        assertThat(decoded).hasSize(32).containsOnly((byte) 0);
        assertThat(Base58.encode(decoded)).isEqualTo("11111111111111111111111111111111");
    }

    @Test
    void roundTripsArbitraryBytes() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (i * 7 + 3);
        }
        assertThat(Base58.decode(Base58.encode(bytes))).isEqualTo(bytes);
    }

    @Test
    void knownVector() {
        // "hello world" per the reference vectors used by bitcoin's base58.
        assertThat(Base58.encode("hello world".getBytes())).isEqualTo("StV1DL6CwTryKyV");
        assertThat(new String(Base58.decode("StV1DL6CwTryKyV"))).isEqualTo("hello world");
    }

    @Test
    void recognisesPublicKeysAndRejectsJunk() {
        assertThat(Base58.isPublicKey("So11111111111111111111111111111111111111112")).isTrue();
        assertThat(Base58.isPublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")).isTrue();
        assertThat(Base58.isPublicKey("not-a-key")).isFalse();
        assertThat(Base58.isPublicKey("0OIl")).isFalse();
        assertThat(Base58.isPublicKey(null)).isFalse();
        assertThatThrownBy(() -> Base58.decode("0")).isInstanceOf(IllegalArgumentException.class);
    }
}
