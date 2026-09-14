package io.lifeengine.cryptobot.signer.solana;

import java.util.Arrays;

/**
 * Bitcoin-alphabet Base58 as used by Solana for public keys, signatures and blockhashes. No
 * checksum variant on purpose: Solana never uses Base58Check.
 */
public final class Base58 {

    private static final char[] ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    private Base58() {}

    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        byte[] number = Arrays.copyOf(input, input.length);
        char[] encoded = new char[number.length * 2];
        int outputStart = encoded.length;
        for (int inputStart = zeros; inputStart < number.length; ) {
            encoded[--outputStart] = ALPHABET[divmod(number, inputStart, 256, 58)];
            if (number[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < encoded.length && encoded[outputStart] == ALPHABET[0]) {
            outputStart++;
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ALPHABET[0];
        }
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    public static byte[] decode(String input) {
        if (input == null || input.isEmpty()) {
            return new byte[0];
        }
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = c < 128 ? INDEXES[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character '" + c + "' at " + i);
            }
            input58[i] = (byte) digit;
        }
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) {
            zeros++;
        }
        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        for (int inputStart = zeros; inputStart < input58.length; ) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) {
            outputStart++;
        }
        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.length);
    }

    /** True iff {@code value} decodes to exactly 32 bytes — a Solana public key. */
    public static boolean isPublicKey(String value) {
        if (value == null || value.length() < 32 || value.length() > 44) {
            return false;
        }
        try {
            return decode(value).length == 32;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = (int) number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
