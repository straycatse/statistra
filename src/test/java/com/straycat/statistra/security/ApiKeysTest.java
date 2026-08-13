package com.straycat.statistra.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatesDistinctKeysWithTheExpectedPrefix() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = ApiKeys.generate();
            assertThat(key).startsWith("st_");
            keys.add(key);
        }
        // 256 bits of entropy, so a collision here means the generator is broken.
        assertThat(keys).hasSize(1000);
    }

    @Test
    void hashIsDeterministicSoAuthenticationCanBeAnIndexLookup() {
        String key = ApiKeys.generate();
        assertThat(ApiKeys.hash(key)).isEqualTo(ApiKeys.hash(key));
    }

    @Test
    void differentKeysHashDifferently() {
        assertThat(ApiKeys.hash(ApiKeys.generate()))
                .isNotEqualTo(ApiKeys.hash(ApiKeys.generate()));
    }

    @Test
    void hashIsHexEncodedSha256() {
        assertThat(ApiKeys.hash("anything")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void hashDoesNotContainThePlaintextKey() {
        String key = ApiKeys.generate();
        assertThat(ApiKeys.hash(key)).doesNotContain(key.substring(3));
    }

    @Test
    void displayPrefixRevealsOnlyTheLeadingCharacters() {
        String key = ApiKeys.generate();
        String prefix = ApiKeys.displayPrefix(key);

        assertThat(prefix).hasSize(11);
        assertThat(key).startsWith(prefix);
        assertThat(prefix).isNotEqualTo(key);
    }

    @Test
    void displayPrefixHandlesShortInput() {
        assertThat(ApiKeys.displayPrefix("st_ab")).isEqualTo("st_ab");
    }

    @Test
    void secureEqualsMatchesOnlyIdenticalValues() {
        assertThat(ApiKeys.secureEquals("token", "token")).isTrue();
        assertThat(ApiKeys.secureEquals("token", "token ")).isFalse();
        assertThat(ApiKeys.secureEquals("token", "Token")).isFalse();
        assertThat(ApiKeys.secureEquals(null, "token")).isFalse();
        assertThat(ApiKeys.secureEquals("token", null)).isFalse();
    }
}
