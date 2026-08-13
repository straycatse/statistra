#!/usr/bin/env python3
"""
Throughput, latency and failure-mode benchmark for Statistra.

Runs against a local stack or a real deployment:

    # local
    docker compose up -d
    RATE_LIMIT_PER_MINUTE=10000000 ./gradlew bootRun --args='--spring.profiles.active=local'
    python3 benchmark/load_test.py

    # deployed
    python3 benchmark/load_test.py \
        --base-url https://your-app.up.railway.app \
        --api-key  st_... \
        --admin-token "$ADMIN_TOKEN"

Deliberately NOT part of CI. Shared runners vary by several times between runs,
so gating a build on absolute numbers produces failures unrelated to the code.
Correctness under load is asserted in PipelineUnderLoadTest, which holds at any
speed; this measures the numbers, to be compared against previous runs on
comparable hardware.

Three things to know before trusting a remote run:

  * The rate limiter defaults to 6000/min, which is 100 req/s. Above that you
    are measuring the limiter, not the service. The script detects 429s and
    says so rather than quietly reporting a low number as a capacity limit.
  * Latency measured across the internet is mostly your own round trip.
    --phase ping measures two baselines: a 404 that touches no database, and
    /actuator/health which adds exactly one Postgres round trip. Their
    difference is the app-to-database latency, which is usually the number that
    explains a slow remote run.
  * Events go into the real table under whichever key you supply. Use a
    dedicated benchmark organization, not a live tenant.

The outage phase is local-only and refuses to run against a remote host: it
works by stopping Postgres.
"""
import argparse
import http.client
import json
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse

CFG = {"base": "http://localhost:8080", "key": "st_dev_local_key_not_for_production",
       "token": "local-dev-admin-token", "local": True, "rtt": None}


def connect():
    u = urllib.parse.urlparse(CFG["base"])
    port = u.port or (443 if u.scheme == "https" else 80)
    cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    return cls(u.hostname, port, timeout=30)


def request(conn, method, path, payload=None, admin=False):
    headers = {"Content-Type": "application/json"}
    if admin:
        headers["Authorization"] = f"Bearer {CFG['token']}"
    else:
        headers["X-API-Key"] = CFG["key"]
    body = json.dumps(payload) if payload is not None else None
    t = time.perf_counter()
    try:
        conn.request(method, path, body, headers)
        r = conn.getresponse()
        data = r.read()
        return (time.perf_counter() - t) * 1000, r.status, data
    except Exception:
        return (time.perf_counter() - t) * 1000, 0, b""


def persisted_count():
    """
    Counts through the query API rather than SQL, so this works against a
    deployment where there is no psql to reach for. Scoped to the supplied key,
    which is also why a dedicated benchmark organization keeps the numbers clean.
    """
    c = connect()
    _, status, data = request(
        c, "GET", "/api/v1/analytics/summary?from=2000-01-01T00:00:00Z&to=2099-01-01T00:00:00Z")
    if status != 200:
        return -1
    try:
        return int(json.loads(data)["totalEvents"])
    except Exception:
        return -1


def metric(name):
    c = connect()
    _, status, data = request(c, "GET", "/actuator/prometheus", admin=True)
    if status != 200:
        return None
    for line in data.decode(errors="ignore").splitlines():
        if line.startswith(name) and not line.startswith("#"):
            try:
                v = float(line.rsplit(" ", 1)[1])
            except ValueError:
                continue
            # Kafka reports records-lag-max as NaN when no fetch happened in the
            # sampling window. A single NaN poisons max() for the whole run and
            # reports "nan records" instead of a measurement.
            return None if v != v else v
    return None


PLANS = ["free"] * 85 + ["pro"] * 10 + ["enterprise"] * 5
COUNTRIES = ["SE", "NO", "DK", "FI", "DE", "NL", "GB", "US"]


def sample_event(i, event_type="load_test"):
    """
    Spread metadata across realistic cardinalities.

    Stamping every event with {"plan": "pro"} makes the filtered query a
    predicate that matches everything, which a planner correctly answers with a
    sequential scan. The result looks identical to a missing index, so the
    comparison says nothing. Varying it means filter=plan:enterprise selects
    about 5% and the index has a reason to exist.
    """
    return {"eventType": event_type,
            "metadata": {"plan": PLANS[i % len(PLANS)],
                         "country": COUNTRIES[i % len(COUNTRIES)],
                         "build": f"v{i % 40}"}}


def pct(s, p):
    return s[min(int(len(s) * p), len(s) - 1)]


def report(label, lat, codes, elapsed, n):
    s = sorted(lat)
    print(f"  {label}")
    print(f"    throughput : {n / elapsed:,.0f} req/s")
    print(f"    p50 / p95 / p99 : {pct(s,.50):.1f} / {pct(s,.95):.1f} / {pct(s,.99):.1f} ms")
    print(f"    max        : {s[-1]:.1f} ms")
    print(f"    codes      : {codes}")
    if codes.get(429):
        print("    WARNING: 429s present. You are measuring the rate limiter.")
        print("             Raise RATE_LIMIT_PER_MINUTE on the deployment to measure capacity.")
    if codes.get(0):
        print("    WARNING: connection errors. Client, network or server saturation.")


def drive(path, payload, n, conns, label):
    """
    Returns the number of requests that were actually accepted, not the number
    asked for. Splitting n as `n // conns` silently dropped the remainder, so 60
    requests over 8 connections sent 56 while the caller still waited for 60 to
    arrive, and the drain could only ever time out. The remainder is now spread
    across the first workers, and callers size their expectations from the
    responses rather than from the request.
    """
    lat, codes, lock = [], {}, threading.Lock()
    share = [n // conns + (1 if i < n % conns else 0) for i in range(conns)]

    def worker(i):
        c = connect()
        local = []
        for _ in range(share[i]):
            local.append(request(c, "POST", path, payload)[:2])
        with lock:
            for ms, st in local:
                lat.append(ms)
                codes[st] = codes.get(st, 0) + 1

    t0 = time.perf_counter()
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(conns)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    elapsed = time.perf_counter() - t0
    assert len(lat) == n, f"drive sent {len(lat)} of {n} requests"
    report(label, lat, codes, elapsed, len(lat))
    return elapsed, codes


def warmup(seconds, conns=4):
    """
    Drive traffic and throw the results away before measuring anything.

    Without this the first phase to run measures a cold JVM: interpreted
    bytecode until the JIT catches up, an empty Hikari pool, unresolved DNS,
    cold TLS. That is not a property of the service, it is a property of having
    just deployed, and it made a fresh remote run report an app-to-database
    round trip of ~150 ms that is really ~1 ms.

    Whichever phase ran first absorbed all of it, which is exactly the phase
    everything else was being compared against.
    """
    if seconds <= 0:
        print("  warmup skipped; the first phase will absorb JIT and pool startup")
        return
    print(f"  warming up for {seconds}s (results discarded)...", end="", flush=True)
    stop = time.perf_counter() + seconds
    count = [0]
    lock = threading.Lock()

    def worker(_):
        c = connect()
        n = 0
        while time.perf_counter() < stop:
            request(c, "GET", "/api/v1/nope")
            request(c, "GET", "/actuator/health")
            request(c, "GET", "/api/v1/analytics/summary?interval=day")
            # The write path needs the most compilation, so it is worth warming
            # even though these few events land in the table. Every phase reads
            # counts as a delta, so they do not distort any result.
            request(c, "POST", "/api/v1/events", {"eventType": "warmup"})
            n += 4
        with lock:
            count[0] += n

    ts = [threading.Thread(target=worker, args=(i,)) for i in range(conns)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    print(f" {count[0]:,} requests")


def phase_ping(n=100):
    """
    Two baselines, because the difference between them is the diagnosis.

    A 404 on an unknown path is pure network: the API key resolves from cache,
    the router finds nothing, and no query is issued. /actuator/health runs the
    datasource health indicator, so it is that same path plus exactly one round
    trip to Postgres.

    Subtracting the two measures the app-to-database latency directly, which is
    the number that explains almost everything else in a remote run. On a
    same-region private network it should be low single digits.
    """
    print("\n=== Baselines ===")
    c = connect()
    request(c, "GET", "/api/v1/nope")  # warm the API key cache before timing

    def probe(path, label):
        lat, codes = [], {}
        conn = connect()
        for _ in range(n):
            ms, st, _ = request(conn, "GET", path)
            lat.append(ms)
            codes[st] = codes.get(st, 0) + 1
        s = sorted(lat)
        print(f"  {label:<34} p50 {pct(s,.50):>7.1f} ms   p95 {pct(s,.95):>7.1f} ms   {codes}")
        return pct(s, .50)

    net = probe("/api/v1/nope", "network only (404, no db)")
    CFG["rtt"] = net
    db = probe("/actuator/health", "network + one db round trip")
    print(f"\n  inferred app-to-database round trip: {db - net:.1f} ms")
    if db - net > 20:
        print("  That is high. On Railway, same-region private networking should be")
        print("  low single digits. Check that Postgres is in the same region as the")
        print("  app: a cross-region database explains slow writes and slow queries")
        print("  at once, and no amount of query tuning will fix it.")
    print(f"  Subtract {net:.1f} ms, not the health figure, from ingest latency.")


def phase_throughput(batch, batches, conns):
    print(f"\n=== Throughput: {batch * batches:,} events via {batches} batch requests ===")
    payload = {"events": [sample_event(i) for i in range(batch)]}
    start = persisted_count()
    lags, stop = [], threading.Event()

    def watch():
        while not stop.is_set():
            v = metric("kafka_consumer_fetch_manager_records_lag_max")
            if v is not None:
                lags.append(v)
            time.sleep(0.5)

    watcher = threading.Thread(target=watch, daemon=True)
    watcher.start()
    accept, codes = drive("/api/v1/events/batch", payload, batches, conns, "batch ingest")
    # Count what the service accepted, not what we tried to send. A rejected
    # batch never reaches Kafka, so waiting for it turns a partial failure into
    # a meaningless timeout instead of a visible one.
    sent = batch * codes.get(202, 0)
    if sent != batch * batches:
        print(f"    NOTE: {codes.get(202, 0)} of {batches} batches accepted; "
              f"expecting {sent:,} events, not {batch * batches:,}")
    if sent == 0:
        print("    Nothing was accepted. Skipping drain.")
        stop.set()
        return

    if start < 0:
        stop.set()
        print("    Could not read counts through the query API; skipping drain measurement.")
        return

    t1 = time.perf_counter()
    while persisted_count() < start + sent and time.perf_counter() - t1 < 300:
        time.sleep(1)
    drain = time.perf_counter() - t1
    stop.set()
    final = persisted_count()
    timed_out = final < start + sent

    print(f"    accepted            : {sent / accept:>10,.0f} events/s")
    print(f"    persisted end-to-end: {sent / (accept + drain):>10,.0f} events/s")
    print(f"    drain after last 202: {drain:>10.1f} s")
    if lags:
        print(f"    peak consumer lag   : {max(lags):>10,.0f} records")
    else:
        print("    peak consumer lag   :        n/a  (metric unreadable; check --admin-token)")
    if timed_out:
        print(f"    WARNING: drain did not finish within {drain:.0f}s. Only "
              f"{final - start:,} of {sent:,} events arrived, so the persisted")
        print("             figure above is a lower bound, not a measurement.")
    print("    The accept/persist gap is what Kafka buys: the backlog is the burst")
    print("    Postgres could not have taken synchronously.")


def phase_latency(n, conns):
    print(f"\n=== Latency: {n:,} single-event POSTs ===")
    drive("/api/v1/events", sample_event(7, "single"), n, conns, "single ingest")

    if CFG["rtt"]:
        ceiling = conns / (CFG["rtt"] / 1000.0)
        print(f"    client ceiling: {ceiling:,.0f} req/s "
              f"({conns} connections at {CFG['rtt']:.1f} ms round trip)")
        print("    Each connection sends one request at a time, so this is the most")
        print("    the client can ask for regardless of server capacity. Approaching")
        print("    it means you measured your own concurrency, not the service.")


def phase_query(n=100):
    """
    Read-side cost at whatever volume is already stored. This is the half that
    matters as the table grows: ingest stays flat, aggregates do not.
    """
    print(f"\n=== Query latency ({n} requests each, against current data volume) ===")
    total = persisted_count()
    print(f"  rows visible to this key: {total:,}\n" if total >= 0 else "")
    queries = [
        ("timeseries day", "/api/v1/analytics/timeseries?interval=day"),
        ("timeseries hour", "/api/v1/analytics/timeseries?interval=hour"),
        ("breakdown eventType", "/api/v1/analytics/breakdown?groupBy=eventType"),
        ("breakdown metadata", "/api/v1/analytics/breakdown?groupBy=metadata.plan"),
        ("summary", "/api/v1/analytics/summary"),
        ("summary + filter (5%)", "/api/v1/analytics/summary?filter=plan:enterprise"),
        ("summary + filter (85%)", "/api/v1/analytics/summary?filter=plan:free"),
        ("raw events page", "/api/v1/events?limit=100"),
        ("event types", "/api/v1/event-types"),
    ]
    for label, path in queries:
        c = connect()
        lat, codes = [], {}
        for _ in range(n):
            ms, st, _ = request(c, "GET", path)
            lat.append(ms)
            codes[st] = codes.get(st, 0) + 1
        s = sorted(lat)
        flag = "" if codes.get(200) == n else f"  <-- {codes}"
        print(f"  {label:<22} p50 {pct(s,.50):>7.1f} ms   p95 {pct(s,.95):>7.1f} ms{flag}")
    if CFG["rtt"]:
        print(f"\n  Subtract the {CFG['rtt']:.1f} ms network baseline: what is left is the")
        print("  service's own cost. The two filtered rows are the interesting pair.")
        print("  The 5% filter should beat the 85% one; if they match, the GIN index")
        print("  is not being used. If both beat the unfiltered summary, it is.")
        print("  Only meaningful against data this script generated: it varies plan,")
        print("  country and build so the predicates have realistic selectivity.")


def phase_outage(seconds):
    """
    Stop Postgres mid-ingest. The test that justifies Kafka at all.

    Local only, and refuses otherwise: it works by stopping a container, which
    against a deployment would mean taking production down to run a benchmark.
    """
    if not CFG["local"]:
        print("\n=== Outage: SKIPPED ===")
        print("  Refusing to run against a remote host. This phase stops Postgres.")
        print("  Run it locally, where an outage costs nothing.")
        return

    c = connect()
    request(c, "POST", "/api/v1/events", {"eventType": "warmup"})
    time.sleep(3)
    before = persisted_count()

    print(f"\n=== Outage: Postgres stopped for {seconds}s while ingest continues ===")
    subprocess.run(["docker", "compose", "stop", "postgres"], capture_output=True)
    sent = ok = 0
    bad = {}
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < seconds:
        st = request(c, "POST", "/api/v1/events", {"eventType": "during_outage"})[1]
        sent += 1
        if st == 202:
            ok += 1
        else:
            bad[st] = bad.get(st, 0) + 1
        time.sleep(0.05)
    subprocess.run(["docker", "compose", "start", "postgres"], capture_output=True)

    t1 = time.perf_counter()
    while persisted_count() < before + ok and time.perf_counter() - t1 < 180:
        time.sleep(1)
    after = persisted_count()
    print(f"  sent / accepted : {sent} / {ok}")
    print(f"  failures        : {bad if bad else 'none'}")
    print(f"  recovered       : {after - before} of {ok}")
    print(f"  VERDICT         : "
          f"{'buffered and drained' if ok and after - before >= ok else 'EVENTS LOST'}")
    print("  An outage longer than the error handler's 30s max backoff is not")
    print("  covered here. Raise --outage-seconds to probe it.")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(
        description="Benchmark Statistra against a local stack or a deployment.",
        epilog=(
            "Phases:\n"
            "  baseline    network round trip, and app-to-database round trip.\n"
            "              Run this first: it tells you whether anything else is\n"
            "              worth reading. ('ping' is accepted as an old alias.)\n"
            "  throughput  peak batch ingest, and how far acceptance outruns\n"
            "              persistence. The gap is what Kafka is buying.\n"
            "  latency     single-event POSTs, the shape a real client produces.\n"
            "  query       read-side cost at the volume already stored.\n"
            "  outage      stops Postgres mid-ingest. Local only.\n"
            "  all         every phase above, in that order (default).\n"),
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080",
                    help="Where to point. Defaults to the local stack.")
    ap.add_argument("--api-key", default="st_dev_local_key_not_for_production",
                    help="Tenant key events are sent under. Use a benchmark org, not a live tenant.")
    ap.add_argument("--admin-token", default="local-dev-admin-token",
                    help="Needed to read consumer lag from /actuator/prometheus.")
    ap.add_argument("--phase", default="all",
                    choices=["all", "baseline", "ping", "throughput", "latency", "query", "outage"],
                    help="Which phase to run. See the list below. Default: all.")
    ap.add_argument("--batch", type=int, default=500,
                    help="Events per batch request in the throughput phase.")
    ap.add_argument("--batches", type=int, default=40,
                    help="Number of batch requests in the throughput phase.")
    ap.add_argument("--events", type=int, default=2000,
                    help="Single-event POSTs in the latency phase.")
    ap.add_argument("--connections", type=int, default=8,
                    help="Concurrent connections used to drive load.")
    ap.add_argument("--outage-seconds", type=int, default=40,
                    help="How long to keep Postgres stopped in the outage phase.")
    ap.add_argument("--warmup-seconds", type=int, default=15,
                    help="Discarded traffic before measuring, so the first phase does "
                         "not absorb JIT and connection-pool startup. 0 disables.")
    a = ap.parse_args()

    CFG["base"] = a.base_url.rstrip("/")
    CFG["key"] = a.api_key
    CFG["token"] = a.admin_token
    CFG["local"] = urllib.parse.urlparse(CFG["base"]).hostname in ("localhost", "127.0.0.1", "::1")

    c = connect()
    if request(c, "GET", "/actuator/health")[1] != 200:
        print(f"{CFG['base']} is not answering /actuator/health. Wrong URL, or the app is down.")
        sys.exit(1)
    if persisted_count() < 0:
        print("The supplied API key cannot read the query API. Check --api-key.")
        sys.exit(1)
    if metric("kafka_consumer_fetch_manager_records_lag_max") is None:
        print("Note: metrics unreadable, consumer lag will not be reported. Check --admin-token.\n")

    print(f"target: {CFG['base']}  ({'local' if CFG['local'] else 'REMOTE'})")
    if not CFG["local"]:
        print("Remote run. Events land in the real table under this key, and latency")
        print("includes your network round trip. Use a dedicated benchmark org.")

    warmup(a.warmup_seconds)

    if a.phase in ("all", "baseline", "ping"):
        phase_ping()
    if a.phase in ("all", "throughput"):
        phase_throughput(a.batch, a.batches, a.connections)
    if a.phase in ("all", "latency"):
        phase_latency(a.events, a.connections)
    if a.phase in ("all", "query"):
        phase_query()
    if a.phase in ("all", "outage"):
        phase_outage(a.outage_seconds)
