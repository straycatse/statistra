# Statistra

Multi-tenant analytics event ingest and query service.

Clients POST events with an API key. Events are published to Kafka, persisted to
Postgres by a batch consumer, and read back through an aggregate query API.

```
   POST /api/v1/events                      analytics-events
 ┌──────────────────────┐    ┌───────────┐    ┌─────────┐    ┌────────────────┐
 │  AnalyticsController │───▶│   Kafka   │───▶│Consumer │───▶│   PostgreSQL   │
 │  (authenticate,      │    │ keyed by  │    │ (batch, │    │ analytics_events│
 │   validate, accept)  │    │  org id   │    │  dedup) │    │    (jsonb)     │
 └──────────────────────┘    └─────┬─────┘    └─────────┘    └───────┬────────┘
            │ 202                  │ poison records                  │
            ▼                      ▼                                 ▼
        {eventIds}         analytics-events.DLT              GET /api/v1/analytics/*
```

Kafka sits between accept and store so that a slow or failing database applies
backpressure instead of rejecting client traffic, and so that events can be
replayed. Ingest survives a database outage: API keys are cached, so events keep
being accepted and buffered while Postgres is down.

Events optionally carry identity, which is what makes per-person questions
possible: unique people, and ordered conversion funnels across the sign-in
boundary. Without it the service can only count events.

## Quickstart

Requires Docker and a JDK 17+ (`.java-version` pins 17 for jenv users).

```bash
docker compose up -d                                     # Postgres + Kafka
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile seeds a development organization and prints its key. It is
`st_dev_local_key_not_for_production`, a published constant with no value as a
credential, which is why the profile must never be enabled in production.

```bash
export KEY=st_dev_local_key_not_for_production

# Send an event
curl -X POST localhost:8080/api/v1/events \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"eventType":"page_view","metadata":{"plan":"pro","country":"SE"}}'

# Read it back
curl "localhost:8080/api/v1/analytics/timeseries?interval=hour" -H "X-API-Key: $KEY"
```

`statistra.insomnia.json` in the repo root imports into Insomnia with every
endpoint, a prefilled **Local** environment and a **Railway** one, plus a folder
of negative cases for checking a fresh deployment.

## Ingest

`POST /api/v1/events` and `POST /api/v1/events/batch` (max 500 events, 1 MB body).

```json
{
  "eventId": "optional-uuid",
  "eventType": "page_view",
  "occurredAt": "2026-08-12T10:00:00Z",
  "userId": "auth0|abc123",
  "anonymousId": "anon-77",
  "metadata": { "plan": "pro" }
}
```

Only `eventType` is required. Both endpoints answer `202 Accepted`: the event has
been handed to Kafka, not yet persisted.

There is no `organizationId` field. Tenancy is derived from the API key, so a
caller has no way to write into another tenant's data.

**Retries are safe.** Supply `eventId` and resending is a no-op, enforced by a
unique constraint on `(organization_id, event_id)`. Without it, Kafka's
at-least-once delivery would inflate every metric on redelivery.

`occurredAt` defaults to now and may be backdated to load history. It is stored
separately from `received_at`, because when an event happened and when it
arrived are different facts.

## Query

All endpoints are scoped to the authenticated organization. Every one accepts
`from`, `to` (ISO-8601, defaulting to the last 30 days), `eventType`, and
repeatable `filter`.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/analytics/timeseries` | Counts per `hour`/`day`/`week`/`month` bucket |
| `GET /api/v1/analytics/breakdown` | Top groups by `eventType` or `metadata.<key>` |
| `GET /api/v1/analytics/summary` | Totals, unique people, distinct types, first and last event |
| `GET /api/v1/analytics/funnel` | Ordered conversion funnel over distinct people |
| `GET /api/v1/events` | Paginated raw events |
| `GET /api/v1/event-types` | Every event type sent, with counts |

Time series include empty buckets as zero. A plain `GROUP BY` omits periods with
no events, and a chart drawn from that closes the gaps and misstates the data.

Buckets are **always UTC**, and the range is half-open: `from` inclusive, `to`
exclusive. A `day` bucket is a UTC midnight-to-midnight day wherever the service
runs, so the same query returns the same answer from a laptop and from a
deployment. Per-tenant reporting timezones are not supported yet; a client
wanting local days should request `hour` buckets and roll them up.

### Identity

Events carry two optional identifiers, and both are your values, not ours:

- **`userId`** your own identifier for the person, set once they authenticate.
  Stored verbatim and scoped to your organization, so it can never collide with
  another tenant's. Any string: bigints, ULIDs and `auth0|abc123` all work.
- **`anonymousId`** a client-generated id that covers logged-out traffic.

Both may be absent. A billing webhook or a cron run has neither, and those
events are simply excluded from per-person questions rather than merged into a
phantom actor.

**Keep sending `anonymousId` after login.** That overlap is the only record
linking someone's anonymous history to their account, it makes the mapping
derivable from the rows themselves with no separate identify call, and it cannot
be reconstructed later if it was never written. Without it, every funnel that
crosses sign-in breaks exactly where it matters.

Sessions are deliberately not stored. A session is a gap in time, better derived
at query time than trusted from a client.

### Funnels

```bash
curl "localhost:8080/api/v1/analytics/funnel\
?step=page_view&step=signup&step=purchase&window=7d" -H "X-API-Key: $KEY"
```

Steps are matched **in order, first occurrence, per person**. A step only counts
when it happens after the previous one for that same actor, so someone who
purchased and later viewed the page is not a conversion. Repeating a step does
not let anyone count twice.

`window` bounds how long someone has to finish, measured from their **first**
step rather than the previous one, so an N-step funnel cannot quietly allow N
times the window. Defaults to `7d`; accepts `30m`, `24h`, `7d`.

```json
{
  "from": "2026-07-14T00:00:00Z",
  "to": "2026-08-13T00:00:00Z",
  "conversionWindow": "7d",
  "entered": 10,
  "completed": 2,
  "overallConversion": 0.2,
  "steps": [
    { "step": 1, "eventType": "page_view", "actors": 10,
      "conversionFromPrevious": 1.0, "conversionFromFirst": 1.0,
      "medianSecondsFromPrevious": null },
    { "step": 2, "eventType": "signup", "actors": 6,
      "conversionFromPrevious": 0.6, "conversionFromFirst": 0.6,
      "medianSecondsFromPrevious": 141.0 },
    { "step": 3, "eventType": "purchase", "actors": 2,
      "conversionFromPrevious": 0.333, "conversionFromFirst": 0.2,
      "medianSecondsFromPrevious": 88.0 }
  ]
}
```

`actors` counts distinct people, `conversionFromPrevious` is the share of the
previous step who continued, `conversionFromFirst` the share of everyone who
entered. `medianSecondsFromPrevious` is null for the first step and whenever
nobody converted. At most 8 steps.

Anonymous history is stitched to the account it became, so a funnel spanning
sign-in counts one person rather than two. The mapping is scoped to the query
window: someone who identified before it is only stitched if they also appear
inside it.

### Metadata filtering

```bash
# Events where metadata contains plan=pro AND country=SE
curl "localhost:8080/api/v1/analytics/summary?filter=plan:pro&filter=country:SE" \
  -H "X-API-Key: $KEY"

# Group by an arbitrary metadata key
curl "localhost:8080/api/v1/analytics/breakdown?groupBy=metadata.plan" \
  -H "X-API-Key: $KEY"
```

Filtering compiles to `metadata @> ?::jsonb`, bound as a single parameter and
served by a GIN index. Grouping binds the key through `metadata ->> ?`. No
caller input is ever concatenated into SQL.

### Event types are not registered

`eventType` is a free-form string; anything is accepted. That keeps clients
flexible but means `user_signup` and `user_signedup` will happily coexist as two
separate metrics. `GET /api/v1/event-types` exists to make that visible. If you
later want strictness, an allow-list per organization is the natural next step.

## Authentication

Every `/api/**` request carries `X-API-Key`. The key is hashed with SHA-256 and
resolved to an organization, which then scopes every query. No endpoint accepts
an organization id, so there is no request a caller can construct that reads
another tenant's data.

Lookups are cached for `AUTH_CACHE_TTL` (default 60s). This is not a
micro-optimisation. Authentication used to read Postgres on every request, which
put the database on the critical path of the one endpoint Kafka exists to keep
off it: with Postgres stopped, **every** ingest request failed and every event
sent during the outage was lost, while the broker sat healthy and idle. Cached,
the same test accepts and later drains all of them, and single-event ingest went
from 1,678 to 4,367 req/s locally.

The trade is bounded staleness: a rotated key stays usable until its entry
expires. Rotation evicts explicitly, so revocation is immediate on the instance
that performed it and others wait out the TTL. Set `AUTH_CACHE_TTL=0s` for
strict revocation at the cost of a database read per request.

Misses are cached too, so a flood of invalid keys cannot be used to hammer the
database, and the cache is size-bounded so caching cannot itself become a
memory-exhaustion vector.

## Admin

Guarded by `ADMIN_TOKEN`, separate from tenant keys so no client credential can
provision. **The API returns 503 when the token is unset**, rather than running
unauthenticated.

```bash
curl -X POST localhost:8080/admin/organizations \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Acme AB"}'
```

| Endpoint | Purpose |
|---|---|
| `POST /admin/organizations` | Create an organization, returns its key **once** |
| `GET /admin/organizations` | List organizations, never key material |
| `POST /admin/organizations/{id}/rotate-key` | Issue a replacement key |

Keys are stored as SHA-256, not BCrypt. They are 256 random bits rather than a
human-chosen password, so slow hashing buys nothing, while a deterministic hash
keeps authentication a single indexed lookup instead of a scan-and-compare over
every row.

## Configuration

Every value is environment-overridable and nothing sensitive is committed.

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5444/statistra` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `postgres` | Credentials |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker |
| `ADMIN_TOKEN` | *(unset)* | Admin API and non-probe Actuator token; unset disables both |
| `AUTH_CACHE_TTL` | `60s` | API key lookup cache; `0s` disables it |
| `AUTH_CACHE_MAX_ENTRIES` | `10000` | Cache bound, so caching cannot exhaust memory |
| `MAX_BODY_BYTES` | `1048576` | Ingest body ceiling |
| `MAX_BATCH_SIZE` | `500` | Events per batch request |
| `RATE_LIMIT_PER_MINUTE` | `6000` | Ingest **requests** per org per minute, not events |
| `KAFKA_TOPIC` | `analytics-events` | Topic; the DLT is this plus `.DLT` |
| `KAFKA_TOPIC_PARTITIONS` | `3` | Partitions created at startup |
| `KAFKA_TOPIC_REPLICATION_FACTOR` | `1` | Must be 1 on a single broker |
| `KAFKA_CONSUMER_GROUP` | `analytics-group` | Consumer group id |
| `LOG_LEVEL` | `INFO` | Level for `com.straycat.statistra` |
| `PORT` | `8080` | HTTP port |

`RATE_LIMIT_PER_MINUTE` counts requests, not events, so a client within the limit
can still send 6000 x `MAX_BATCH_SIZE` events a minute. It is a web-tier abuse
floor; `MAX_BATCH_SIZE` and `MAX_BODY_BYTES` are what bound volume. The bucket is
per organization, so 6000/min is 100 req/s for an entire customer, which is low
for a tenant whose clients do not batch.

## Schema

Flyway owns the schema (`src/main/resources/db/migration`), applied at startup.
Hibernate runs with `ddl-auto=validate`, so a mismatch between entities and the
migrated schema fails the boot rather than silently altering tables.

To change the schema, add a new `V<n>__description.sql`. Never edit an applied
migration: Flyway checksums them and will refuse to start.

- **V1** `organizations` and `analytics_events`. `occurred_at`/`received_at` as
  `timestamptz`, `metadata` as `jsonb` with a GIN index, and a
  `UNIQUE (organization_id, event_id)` constraint that the consumer relies on
  via `INSERT ... ON CONFLICT DO NOTHING`. That constraint is what stops Kafka's
  at-least-once delivery from inflating every count.
- **V2** identity. `user_id` and `anonymous_id`, both nullable `TEXT`, plus a
  generated `actor_id` column that coalesces them so one index serves "every
  event by this person" whether or not they were logged in. `user_id` wins when
  both are present, so an identified person is stable across devices.

`actor_id` is the index and filter column, not the counting column: it does not
stitch across sign-in, since pre-login rows carry the anonymous id and post-login
rows the user id. Queries that count people resolve identity first (see
[Identity](#identity)).

## Deploying to Railway

```bash
railway up
```

`railway.toml` builds from the `Dockerfile` and health-checks `/actuator/health`.
Provision a Postgres service and a Kafka service (Railway has no managed Kafka;
run `apache/kafka` in KRaft mode as its own service).

**Railway's `DATABASE_URL` is not a valid JDBC URL.** It is
`postgresql://user:pass@host/db`, which the JDBC driver cannot parse. Set the
datasource explicitly from Railway's referenced variables:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
```

Set `ADMIN_TOKEN` to a generated secret. Do not set `SPRING_PROFILES_ACTIVE=local`.

Keep `numReplicas = 1` until rate-limit state moves out of memory: the limiter
counts per instance, so N replicas allow N times the configured rate.

## Benchmarking

Correctness under load is asserted in `PipelineUnderLoadTest` and runs in CI on
every push. It makes no timing assertions: shared runners vary by several times
between runs, and a suite that fails for reasons unrelated to the code is a
suite people learn to ignore. It asserts invariants that hold at any speed, that
every accepted event is persisted exactly once and that the counters reconcile.

Throughput is measured separately and deliberately, never gated on:

```bash
docker compose up -d
RATE_LIMIT_PER_MINUTE=10000000 ./gradlew bootRun --args='--spring.profiles.active=local'
python3 benchmark/load_test.py
```

The rate limit override matters. The default 6000/min is 100 req/s, so without
it you are benchmarking the rate limiter.

### Against a deployment

```bash
python3 benchmark/load_test.py \
  --base-url https://your-app.up.railway.app \
  --api-key  st_...            \
  --admin-token "$ADMIN_TOKEN" \
  --phase query
```

Counts come through the query API rather than SQL, so no database access is
needed. Three caveats:

- **Use a dedicated benchmark organization.** Events land in the real table
  under whichever key you supply, and there is no delete endpoint, so cleaning
  up means going to the database directly.
- **Raise `RATE_LIMIT_PER_MINUTE` first**, or you measure the limiter. The
  script warns when it sees 429s rather than reporting the ceiling as capacity.
- **Remote latency is mostly your own network.** `--phase ping` measures the
  same path doing nothing, so subtract it. Throughput and consumer lag survive
  the trip; latency percentiles do not.

The outage phase refuses to run against a remote host, because it works by
stopping Postgres.

Every run discards 15 seconds of traffic before measuring, so the first phase
does not absorb JIT compilation and connection-pool startup. Without it a fresh
deployment reports its cold-start cost as though it were steady-state: locally
that alone is a 3x difference in p50 and 6x in p95, and it is larger on a small
container. `--warmup-seconds 0` disables it.

Measured locally on an M-series laptop against containerised Postgres and Kafka.
Ratios transfer between machines; absolute numbers do not.

| | |
|---|---|
| Batch ingest accepted | 62,600 events/s |
| Persisted end to end | 7,200 events/s |
| Peak consumer backlog absorbed | 15,200 records |
| Single-event ingest | 4,400 req/s, p50 1.3 ms, p99 8.6 ms |

The gap between accepted and persisted is what Kafka buys: a burst Postgres
could not have taken synchronously. `benchmark/load_test.py --phase outage`
stops Postgres mid-ingest and is the test that justifies the broker at all.

`kafka_consumer_fetch_manager_records_lag_max` is the number to watch in
production. Spiking and recovering is healthy; trending upward means the
consumer is losing.

## Tests

```bash
./gradlew test
```

Testcontainers starts real Postgres and Kafka, so only Docker is required. 79
tests cover the pipeline end to end, idempotency under redelivery, tenant
isolation, the error contract, UTC bucketing, API key caching and revocation,
and funnel semantics.

The funnel tests each target a specific way funnels are got wrong rather than a
happy path: steps taken out of order, repeated steps, both window semantics,
identity stitching, events with no actor, and cross-tenant leakage.

If containers fail to start with *"Could not find a valid Docker environment"*
while Docker is plainly running, that is the Docker API version. Testcontainers
falls back to API 1.32 and Docker Engine 29 rejects anything below 1.40 with a
400. `build.gradle` pins `api.version=1.44` for this reason.

## Operations

`/actuator/health` is open, because the platform healthcheck must reach it
without credentials. It reports only UP or DOWN.

`/actuator/metrics`, `/actuator/prometheus` and `/actuator/info` require the
admin token, since management shares the public port and the scrape output
includes ingest volume:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/actuator/prometheus
```

Prometheus scrapes this with `authorization: { credentials: <ADMIN_TOKEN> }` in
the job config.

Application counters: `statistra.events.published`, `.publish_failed`,
`.persisted`, `.deduplicated`, `.invalid`. A rising `deduplicated` is normal
(retries and rebalances being suppressed); a rising `publish_failed` is not.

`kafka_consumer_fetch_manager_records_lag_max` is the number to watch. Spiking
and recovering is healthy; trending upward means the consumer cannot keep up and
the backlog will grow without bound.

Records that cannot be processed after retries go to `analytics-events.DLT`
rather than being dropped, so failures stay inspectable.

A green healthcheck does **not** prove the pipeline works. Spring Boot ships no
Kafka health indicator and the admin client is non-fatal, so the app boots and
reports healthy with a broken broker while ingest accepts events that never
persist. Only an end-to-end POST followed by `/api/v1/analytics/summary` proves
it.

## Deleting data

There is no delete endpoint yet, so removal means SQL. `railway connect Postgres`
opens a prompt against a deployment.

Always look first:

```sql
SELECT organization_id, event_type, count(*)
FROM analytics_events GROUP BY 1, 2 ORDER BY 3 DESC;
```

Clearing benchmark traffic, which is the only data written with these types:

```sql
DELETE FROM analytics_events
WHERE event_type IN ('load_test', 'single', 'warmup', 'during_outage');
```

Removing a tenant entirely, which cascades to their events:

```sql
DELETE FROM organizations WHERE id = <id>;
```

Provision a dedicated organization for benchmarking and cleanup becomes that one
line, including its API key.

Erasing one person, which is the GDPR path now that `user_id` exists:

```sql
DELETE FROM analytics_events WHERE organization_id = ? AND user_id = ?;
```

Keep `user_id` pseudonymous, an opaque internal id rather than an email address,
so these rows stay one step removed from directly identifying data.

## Known limitations

- Rate limiting is in-memory and per instance, and uses a fixed window, so a
  client can burst across a boundary. It is an abuse floor, not a billing meter.
- `analytics_events` is unpartitioned with no retention policy. Partitioning by
  month is the first thing to add as the table grows.
- The body-size cap checks bytes as they are read, but a reverse-proxy limit
  should sit in front of it in production.
- No user accounts. Organizations are provisioned by an operator through the
  admin API.
- No delete endpoint. Removing a tenant, benchmark traffic or one person's events
  means hand-run SQL against the database, which is the operation you least want
  to be doing by hand. See [Deleting data](#deleting-data).
- Funnel identity stitching is scoped to the query window: someone who identified
  before it is only stitched if they also appear inside it. A persistent mapping
  table would remove that limit.
- Funnel steps are event types only. A `filter` applies to every step rather than
  per step, so "signup where plan=pro" cannot yet be expressed.
- Per-person numbers depend on clients sending `userId` and `anonymousId`. Events
  ingested before identity was added, or by clients that omit it, are correctly
  invisible to funnels and `uniqueActors` rather than counted as one phantom
  person.
- Retention and cohort analysis are not implemented. The identity columns make
  them possible; the queries do not exist yet.
