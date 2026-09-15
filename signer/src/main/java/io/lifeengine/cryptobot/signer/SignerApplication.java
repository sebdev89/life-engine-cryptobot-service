package io.lifeengine.cryptobot.signer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The only process that holds a private key. It knows nothing about portfolios, policies, LLMs
 * or the chain: it receives an unsigned transaction, checks it against its own hard limits, and
 * returns the same bytes signed. cryptobot-service broadcasts; this never talks to an RPC.
 */
@SpringBootApplication
@EnableConfigurationProperties(SignerProperties.class)
public class SignerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignerApplication.class, args);
    }
}
