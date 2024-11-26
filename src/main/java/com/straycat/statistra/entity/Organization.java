package com.straycat.statistra.entity;

import jakarta.persistence.*;

import java.util.Set;

@Entity
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String apiKey; // Unique identifier for API usage

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<AnalyticsEventEntity> events;

    // Getters and setters

    public Long getId() {
        return id;
    }
}