package com.straycat.statistra.repository;

import com.straycat.statistra.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /**
     * Authentication lookup. Backed by the UNIQUE index on api_key_hash, so this
     * is a single index probe and cannot return more than one row.
     */
    Optional<Organization> findByApiKeyHash(String apiKeyHash);

    boolean existsByName(String name);
}
