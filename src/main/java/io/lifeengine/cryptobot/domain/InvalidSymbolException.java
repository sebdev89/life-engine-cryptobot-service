package io.lifeengine.cryptobot.domain;

public class InvalidSymbolException extends RuntimeException {

    public InvalidSymbolException(String message) {
        super(message);
    }
}
