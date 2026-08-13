-- Identity on events, which is what makes per-person questions possible at all.
--
-- Until now an event belonged to an organization and nothing finer, so the
-- service could answer "how many events" but never "how many people" or "did
-- the same person do A then B". Funnels, retention and unique counts all need
-- a subject, and none of them can be backfilled: identity not captured at
-- ingest time is gone permanently.
--
-- Two columns rather than one, because "always present" and "identifies a
-- person" cannot both be true of a single field:
--
--   anonymous_id  assigned client-side on first contact. Present for browser
--                 and app traffic including logged-out visitors, null for
--                 server-side and system events.
--   user_id       the tenant's own identifier, set once someone authenticates.
--                 Null before that, and forever null for cron runs, webhooks
--                 and other events with no human behind them.
--
-- Both are nullable on purpose. A billing webhook has neither, and that is a
-- correct row rather than a gap.

ALTER TABLE analytics_events
    ADD COLUMN user_id      TEXT,
    ADD COLUMN anonymous_id TEXT;

-- TEXT, deliberately not UUID. event_id was typed UUID on the reasonable
-- assumption that identifiers are UUIDs, and the result is that a tenant whose
-- events are keyed 'order_10023' cannot use client-supplied idempotency at all.
-- Real systems identify users with bigints, ULIDs and provider subjects such as
-- 'auth0|abc123'. The type must not exclude them.

-- One column to ask "every event by this person" without first knowing whether
-- they were logged in. user_id wins when both are present, so an identified
-- actor is stable across sessions and devices.
ALTER TABLE analytics_events
    ADD COLUMN actor_id TEXT GENERATED ALWAYS AS (COALESCE(user_id, anonymous_id)) STORED;

-- The funnel workhorse: walks one actor's events in time order. Ascending
-- rather than descending because funnels read forwards, unlike the listing
-- queries. Partial, since events with no actor at all are never walked and
-- indexing their nulls would only cost write throughput.
CREATE INDEX ix_analytics_events_org_actor_time
    ON analytics_events (organization_id, actor_id, occurred_at)
    WHERE actor_id IS NOT NULL;

-- Identity resolution. When a client keeps sending anonymous_id after login,
-- the anonymous-to-user mapping is derivable from the rows themselves, with no
-- separate mapping table and no backfill. This index is what makes deriving it
-- cheap; it covers only the rows that carry both, which is the small minority
-- written around the moment of authentication.
CREATE INDEX ix_analytics_events_identity
    ON analytics_events (organization_id, anonymous_id, user_id)
    WHERE anonymous_id IS NOT NULL AND user_id IS NOT NULL;
