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
replayed.

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

## Ingest

`POST /api/v1/events` and `POST /api/v1/events/batch` (max 500 events, 1 MB body).

```json
{
  "eventId": "optional-uuid",
  "eventType": "page_view",
  "occurredAt": "2026-08-12T10:00:00Z",
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

Each step reports the actors who reached it, conversion from the previous step
and from the top, and the median time taken.

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
| `MAX_BODY_BYTES` | `1048576` | Ingest body ceiling |
| `MAX_BATCH_SIZE` | `500` | Events per batch request |
| `RATE_LIMIT_PER_MINUTE` | `6000` | Ingest requests per org per minute |
| `PORT` | `8080` | HTTP port |

## Schema

Flyway owns the schema (`src/main/resources/db/migration`), applied at startup.
Hibernate runs with `ddl-auto=validate`, so a mismatch between entities and the
migrated schema fails the boot rather than silently altering tables.

To change the schema, add a new `V<n>__description.sql`. Never edit an applied
migration: Flyway checksums them and will refuse to start.

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

Testcontainers starts real Postgres and Kafka, so only Docker is required. The
suite covers the pipeline end to end, idempotency under redelivery, tenant
isolation, and the query SQL.

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

Records that cannot be processed after retries go to `analytics-events.DLT`
rather than being dropped, so failures stay inspectable.

## Known limitations

- Rate limiting is in-memory and per instance, and uses a fixed window, so a
  client can burst across a boundary. It is an abuse floor, not a billing meter.
- `analytics_events` is unpartitioned with no retention policy. Partitioning by
  month is the first thing to add as the table grows.
- The body-size cap checks bytes as they are read, but a reverse-proxy limit
  should sit in front of it in production.
- No user accounts. Organizations are provisioned by an operator through the
  admin API.
