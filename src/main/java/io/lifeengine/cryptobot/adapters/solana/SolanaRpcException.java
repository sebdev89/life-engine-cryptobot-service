package io.lifeengine.cryptobot.adapters.solana;

/** A JSON-RPC level error (or transport failure) talking to a Solana cluster. */
public class SolanaRpcException extends RuntimeException {

    private final String method;
    private final int code;
    private final String data;

    public SolanaRpcException(String method, int code, String message, String data) {
        this(method, code, message, data, null);
    }

    public SolanaRpcException(String method, int code, String message, String data, Throwable cause) {
        super(method + ": " + message, cause);
        this.method = method;
        this.code = code;
        this.data = data;
    }

    public String method() {
        return method;
    }

    public int code() {
        return code;
    }

    public String data() {
        return data;
    }
}
