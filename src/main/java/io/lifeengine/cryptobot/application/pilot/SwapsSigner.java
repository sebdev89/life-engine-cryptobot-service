package io.lifeengine.cryptobot.application.pilot;

import io.lifeengine.cryptobot.integration.signer.SignerClient;

/**
 * The signer client swap orders use: a wrapper so the pilot's dedicated client is never a second {@link SignerClient}
 * bean (every other injection of SignerClient keeps getting the shared one).
 */
public record SwapsSigner(SignerClient client, boolean dedicated) {}
