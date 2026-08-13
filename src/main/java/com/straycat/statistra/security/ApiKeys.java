package com.straycat.statistra.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of organization API keys.
 *
 * <p>Keys are hashed with SHA-256 rather than BCrypt. That is deliberate and is
 * the opposite of the correct choice for passwords. A password is low-entropy
 * and human-chosen, so it needs a deliberately slow hash to make brute force
 * expensive. An API key here is 256 bits from {@link SecureRandom} and is not
 * guessable at any cost, so slow hashing buys nothing.
 *
 * <p>It does cost something, though: BCrypt salts every hash, so the stored
 * value cannot be looked up. Authenticating would mean loading every
 * organization and comparing one by one. A deterministic hash keeps
 * authentication a single indexed lookup.
 */
public final class ApiKeys {

    private static final String PREFIX = "st_";
    private static final int KEY_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = 11;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ApiKeys() {
    }

    /**
     * Generates a new plaintext API key. This is the only point at which the
     * plaintext exists; only its hash is persisted.
     */
    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }

    /** Returns the lowercase hex SHA-256 of {@code apiKey}. */
    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the leading characters of a key, stored so the API can show which
     * key an organization holds without being able to reconstruct it.
     */
    public static String displayPrefix(String apiKey) {
        return apiKey.length() <= DISPLAY_PREFIX_LENGTH
                ? apiKey
                : apiKey.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /**
     * Constant-time comparison, for the admin token where the expected value is
     * a fixed secret and a timing side channel is meaningful.
     */
    public static boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
