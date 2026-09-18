package io.lifeengine.cryptobot.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The independent validator (paper §20, level 5 of §28). A process that is not the agent: it holds
 * its own copy of the policy pinned by hash, re-derives every verdict with its own table, and
 * attests — with a key that moves no funds — that the bytes about to be signed were authorized
 * under that policy. No LLM, no RPC, no database, no wallet key.
 */
@SpringBootApplication
@EnableConfigurationProperties(ValidatorProperties.class)
public class ValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidatorApplication.class, args);
    }
}
