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
  * Latency measured across the internet is mostly your own round trip. Treat
    remote p50 as "network plus service" and compare it against --phase ping,
    which measures the same path doing almost no work.
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
       "token": "local-dev-admin-token", "local": True}


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
                return float(line.rsplit(" ", 1)[1])
            except ValueError:
                pass
    return None


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
    lat, codes, lock = [], {}, threading.Lock()

    def worker(_):
        c = connect()
        local = []
        for _ in range(n // conns):
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
    report(label, lat, codes, elapsed, len(lat))
    return elapsed, codes


def phase_ping(n=200):
    """Baseline for everything else: the same network path doing almost no work."""
    print("\n=== Ping: GET /actuator/health ===")
    lat, codes = [], {}
    c = connect()
    for _ in range(n):
        ms, st, _ = request(c, "GET", "/actuator/health")
        lat.append(ms)
        codes[st] = codes.get(st, 0) + 1
    report("health", lat, codes, sum(lat) / 1000, n)
    print("    Subtract this from ingest latency to get the service's own cost.")


def phase_throughput(batch, batches, conns):
    print(f"\n=== Throughput: {batch * batches:,} events via {batches} batch requests ===")
    payload = {"events": [{"eventType": "load_test", "metadata": {"plan": "pro"}}
                          for _ in range(batch)]}
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
    sent = batch * batches

    if start < 0:
        stop.set()
        print("    Could not read counts through the query API; skipping drain measurement.")
        return

    t1 = time.perf_counter()
    while persisted_count() < start + sent and time.perf_counter() - t1 < 300:
        time.sleep(1)
    drain = time.perf_counter() - t1
    stop.set()

    print(f"    accepted            : {sent / accept:>10,.0f} events/s")
    print(f"    persisted end-to-end: {sent / (accept + drain):>10,.0f} events/s")
    print(f"    drain after last 202: {drain:>10.1f} s")
    print(f"    peak consumer lag   : {max(lags) if lags else 0:>10,.0f} records")
    print("    The accept/persist gap is what Kafka buys: the backlog is the burst")
    print("    Postgres could not have taken synchronously.")


def phase_latency(n, conns):
    print(f"\n=== Latency: {n:,} single-event POSTs ===")
    drive("/api/v1/events", {"eventType": "single", "metadata": {"plan": "pro"}},
          n, conns, "single ingest")


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
        ("summary + filter", "/api/v1/analytics/summary?filter=plan:pro"),
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
    print("\n  Compare these against the ping baseline. A filter query that tracks")
    print("  the unfiltered one is a sign the GIN index is not being chosen.")


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
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--api-key", default="st_dev_local_key_not_for_production")
    ap.add_argument("--admin-token", default="local-dev-admin-token")
    ap.add_argument("--phase", default="all",
                    choices=["all", "ping", "throughput", "latency", "query", "outage"])
    ap.add_argument("--batch", type=int, default=500)
    ap.add_argument("--batches", type=int, default=40)
    ap.add_argument("--events", type=int, default=2000, help="single-event POSTs")
    ap.add_argument("--connections", type=int, default=8)
    ap.add_argument("--outage-seconds", type=int, default=40)
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

    if a.phase in ("all", "ping"):
        phase_ping()
    if a.phase in ("all", "throughput"):
        phase_throughput(a.batch, a.batches, a.connections)
    if a.phase in ("all", "latency"):
        phase_latency(a.events, a.connections)
    if a.phase in ("all", "query"):
        phase_query()
    if a.phase in ("all", "outage"):
        phase_outage(a.outage_seconds)
