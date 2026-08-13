-- Statistra initial schema.
--
-- Two design points worth stating explicitly:
--
--  * occurred_at / received_at are separate. Clients have clock skew and may
--    backfill history, so the time an event happened is not the time we saw it.
--    occurred_at drives analytics; received_at is the ingest audit trail.
--
--  * (organization_id, event_id) is UNIQUE. Kafka delivers at least once, so
--    without this constraint a redelivery silently double-counts. The consumer
--    relies on it via INSERT ... ON CONFLICT DO NOTHING.

CREATE TABLE organizations
(
    id             BIGSERIAL PRIMARY KEY,
    name           TEXT        NOT NULL,
    -- SHA-256 of the API key, never the key itself. UNIQUE both prevents
    -- collisions and makes authentication a single index lookup.
    api_key_hash   TEXT        NOT NULL UNIQUE,
    -- Leading characters of the key, kept so the UI can show which key is
    -- which without being able to reconstruct it.
    api_key_prefix TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE analytics_events
(
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL,
    organization_id BIGINT      NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_type      TEXT        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb
);

-- Idempotency. Also the lookup path for "have I already stored this event".
CREATE UNIQUE INDEX ux_analytics_events_org_event
    ON analytics_events (organization_id, event_id);

-- Time-range scans, which is every timeseries and list query.
CREATE INDEX ix_analytics_events_org_time
    ON analytics_events (organization_id, occurred_at DESC);

-- Breakdown-by-event-type queries filtered to a window.
CREATE INDEX ix_analytics_events_org_type_time
    ON analytics_events (organization_id, event_type, occurred_at DESC);

-- Containment lookups (metadata @> '{"plan":"pro"}'). jsonb_path_ops builds a
-- smaller, faster index than the default at the cost of only supporting @>,
-- which is the single operator the filter API exposes.
CREATE INDEX ix_analytics_events_metadata
    ON analytics_events USING GIN (metadata jsonb_path_ops);
