package com.straycat.statistra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A tenant. Events are always scoped to one of these, resolved from the API key
 * on the request.
 *
 * <p>There is deliberately no {@code @OneToMany} to events. The collection would
 * be unbounded, and nothing needs to walk it: event access goes through
 * {@code AnalyticsEventDao}, which queries with an explicit organization filter.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key_hash", nullable = false, unique = true)
    private String apiKeyHash;

    @Column(name = "api_key_prefix", nullable = false)
    private String apiKeyPrefix;

    /**
     * Set in Java rather than left to the column default. With
     * {@code insertable = false} the value is assigned by Postgres and the
     * in-memory entity keeps a null until it is reloaded, so the creation
     * response reported {@code "createdAt": null}. The database default remains
     * as a backstop for rows written outside the application.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Organization() {
        // Required by JPA.
    }

    public Organization(String name, String apiKeyHash, String apiKeyPrefix) {
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyPrefix = apiKeyPrefix;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
