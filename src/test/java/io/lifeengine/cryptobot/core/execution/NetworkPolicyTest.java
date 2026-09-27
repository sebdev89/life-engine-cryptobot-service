package io.lifeengine.cryptobot.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.core.Network;
import org.junit.jupiter.api.Test;

/** The core's own copy of the mainnet fail-closed rule, independent of the Solana adapter's. */
class NetworkPolicyTest {

    @Test
    void devnetIsAlwaysPermitted() {
        assertThat(NetworkPolicy.failClosed().permits(Network.DEVNET)).isTrue();
        assertThat(new NetworkPolicy(true).permits(Network.DEVNET)).isTrue();
    }

    @Test
    void mainnetIsRefusedUnlessTheFlagIsOn() {
        assertThat(NetworkPolicy.failClosed().permits(Network.MAINNET_BETA)).isFalse();
        assertThat(new NetworkPolicy(false).permits(Network.MAINNET_BETA)).isFalse();
        assertThat(new NetworkPolicy(true).permits(Network.MAINNET_BETA)).isTrue();
    }

    @Test
    void theExceptionNamesTheNetworkAndCarriesTheCode() {
        MainnetRefusedException ex = new MainnetRefusedException("submit", Network.MAINNET_BETA);
        assertThat(ex.code()).isEqualTo("MAINNET_DISABLED");
        assertThat(ex.network()).isEqualTo(Network.MAINNET_BETA);
        assertThat(ex.getMessage()).contains("mainnet-beta").contains("allow-mainnet");
    }
}
