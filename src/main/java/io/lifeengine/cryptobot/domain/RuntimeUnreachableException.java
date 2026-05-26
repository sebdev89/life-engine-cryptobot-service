package io.lifeengine.cryptobot.domain;

public class RuntimeUnreachableException extends RuntimeException {

    public RuntimeUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
