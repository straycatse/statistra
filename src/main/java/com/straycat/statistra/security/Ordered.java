package com.straycat.statistra.security;

/**
 * Filter ordering.
 *
 * <p>The sequence matters for correctness, not just tidiness: the body size
 * check must run before anything reads the body, and rate limiting must run
 * after authentication so the counter can be keyed by organization rather than
 * by IP.
 */
public final class Ordered {

    /** Reject oversized bodies before any work is done on them. */
    public static final int BODY_SIZE = 10;

    /** Resolve the API key to an organization. */
    public static final int API_KEY_AUTH = 20;

    /** Count against the now-known organization. */
    public static final int RATE_LIMIT = 30;

    private Ordered() {
    }
}
