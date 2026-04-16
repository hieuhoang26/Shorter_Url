package com.hhh.url.shorter_url.util;

/**
 * Thread-safe Base62 encoder/decoder backed by a configurable charset.
 *
 * <p>The charset length determines the encoding base (e.g., 62 for standard Base62,
 * 58 for Base58). Encoding produces a big-endian string (most significant digit first).
 * All instance state is immutable after construction, making this class safe for
 * concurrent use without synchronisation.
 */
public class Base62Encoder {

    private final String charset;
    private final int base;

    /**
     * Creates a new encoder using the given charset.
     *
     * @param charset the characters to use for encoding; length defines the base.
     * @throws IllegalArgumentException if charset is null or empty.
     */
    public Base62Encoder(String charset) {
        if (charset == null || charset.isEmpty()) {
            throw new IllegalArgumentException("Charset must not be null or empty");
        }
        this.charset = charset;
        this.base = charset.length();
    }

    /**
     * Encodes a non-negative long value into a base-N string.
     *
     * @param value the non-negative number to encode.
     * @return the encoded string; returns the first charset character for {@code value == 0}.
     * @throws IllegalArgumentException if value is negative.
     */
    public String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be non-negative, got: " + value);
        }
        if (value == 0) {
            return String.valueOf(charset.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            sb.append(charset.charAt((int) (remaining % base)));
            remaining /= base;
        }
        return sb.reverse().toString();
    }

    /**
     * Decodes a base-N string back to its original long value.
     *
     * <p>Leading characters equal to {@code charset.charAt(0)} are treated as zeros
     * and contribute nothing to the value, so padding is decoded correctly without
     * explicit stripping.
     *
     * @param code the encoded string to decode.
     * @return the decoded long value.
     * @throws IllegalArgumentException if code is null, empty, or contains characters
     *                                  not present in the charset.
     */
    public long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("Code must not be null or empty");
        }
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            int index = charset.indexOf(code.charAt(i));
            if (index == -1) {
                throw new IllegalArgumentException("Invalid character in code: '" + code.charAt(i) + "'");
            }
            result = result * base + index;
        }
        return result;
    }
}
