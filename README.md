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
| `GET /api/v1/analytics/summary` | Totals, distinct types, first and last event |
| `GET /api/v1/events` | Paginated raw events |
| `GET /api/v1/event-types` | Every event type sent, with counts |

Time series include empty buckets as zero. A plain `GROUP BY` omits periods with
no events, and a chart drawn from that closes the gaps and misstates the data.

Buckets are **always UTC**, and the range is half-open: `from` inclusive, `to`
exclusive. A `day` bucket is a UTC midnight-to-midnight day wherever the service
runs, so the same query returns the same answer from a laptop and from a
deployment. Per-tenant reporting timezones are not supported yet; a client
wanting local days should request `hour` buckets and roll them up.

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
