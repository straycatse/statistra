package com.straycat.statistra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings bound from the {@code statistra.*} property namespace.
 */
@ConfigurationProperties(prefix = "statistra")
public class StatistraProperties {

    private final Kafka kafka = new Kafka();
    private final Admin admin = new Admin();
    private final Ingest ingest = new Ingest();
    private final Auth auth = new Auth();

    /** Whether to insert the fixed development organization on startup. */
    private boolean seedDevOrg = false;

    public Kafka getKafka() {
        return kafka;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public Auth getAuth() {
        return auth;
    }

    public boolean isSeedDevOrg() {
        return seedDevOrg;
    }

    public void setSeedDevOrg(boolean seedDevOrg) {
        this.seedDevOrg = seedDevOrg;
    }

    public static class Kafka {
        private String topic = "analytics-events";
        private int partitions = 3;
        private short replicationFactor = 1;

        /** Topic poison records are routed to once retries are exhausted. */
        public String getDeadLetterTopic() {
            return topic + ".DLT";
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }
    }

    public static class Admin {
        /** Bearer token guarding the admin API. Blank disables the API entirely. */
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Auth {

        /**
         * How long an API key lookup is cached. The trade is bounded staleness
         * against keeping the database off the ingest path: a rotated key stays
         * usable for up to this long on instances that did not perform the
         * rotation. Zero disables caching.
         */
        private java.time.Duration cacheTtl = java.time.Duration.ofSeconds(60);

        /** Bounded so that caching misses cannot become a memory-exhaustion vector. */
        private long cacheMaxEntries = 10_000;

        public java.time.Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(java.time.Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public long getCacheMaxEntries() {
            return cacheMaxEntries;
        }

        public void setCacheMaxEntries(long cacheMaxEntries) {
            this.cacheMaxEntries = cacheMaxEntries;
        }
    }

    public static class Ingest {
        private long maxBodyBytes = 1_048_576L;
        private int maxBatchSize = 500;
        private int rateLimitPerMinute = 6000;

        public long getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }
    }
}
