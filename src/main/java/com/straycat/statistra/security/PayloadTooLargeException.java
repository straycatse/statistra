package com.straycat.statistra.security;

/**
 * Raised when a request body exceeds the configured ceiling while being read.
 *
 * <p>Thrown from inside the servlet input stream, so it typically surfaces
 * wrapped in Spring's {@code HttpMessageNotReadableException}. The exception
 * handler unwraps it to distinguish "too big" from "malformed", which are
 * different problems for the client to fix.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}
