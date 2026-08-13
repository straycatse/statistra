#!/usr/bin/env python3
"""
Throughput, latency and failure-mode benchmark for Statistra.

Deliberately NOT part of CI. Shared runners vary by several times between runs,
so gating a build on absolute numbers produces failures unrelated to the code.
Correctness under load is asserted in PipelineUnderLoadTest, which holds at any
speed; this measures the numbers, to be compared against previous runs on
comparable hardware.

Usage:
    docker compose up -d
    RATE_LIMIT_PER_MINUTE=10000000 ./gradlew bootRun --args='--spring.profiles.active=local'
    python3 benchmark/load_test.py [--phase all|throughput|latency|outage]

The rate limit override matters: the default 6000/min is 100 req/s, which is a
lower ceiling than anything worth measuring here, so without it you benchmark
the rate limiter.
"""
import argparse, http.client, json, statistics, subprocess, sys, threading, time

HOST, PORT = "localhost", 8080
API_KEY = "st_dev_local_key_not_for_production"
ADMIN_TOKEN = "local-dev-admin-token"


def rows():
    r = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres",
         "-d", "statistra", "-tAc", "SELECT count(*) FROM analytics_events;"],
        capture_output=True, text=True)
    try:
        return int(r.stdout.strip())
    except ValueError:
        return -1


def metric(name):
    c = http.client.HTTPConnection(HOST, PORT, timeout=10)
    c.request("GET", "/actuator/prometheus",
              headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    for line in c.getresponse().read().decode().splitlines():
        if line.startswith(name) and not line.startswith("#"):
            try:
                return float(line.rsplit(" ", 1)[1])
            except ValueError:
                pass
    return None


def post(conn, path, payload):
    body = json.dumps(payload)
    t = time.perf_counter()
    try:
        conn.request("POST", path, body,
                     {"Content-Type": "application/json", "X-API-Key": API_KEY})
        r = conn.getresponse()
        r.read()
        return (time.perf_counter() - t) * 1000, r.status
    except Exception:
        return (time.perf_counter() - t) * 1000, 0


def pct(sorted_ms, p):
    return sorted_ms[min(int(len(sorted_ms) * p), len(sorted_ms) - 1)]


def throughput(batch=500, batches=40, conns=8):
    """Peak ingest, and how far Kafka lets acceptance outrun persistence."""
    payload = {"events": [{"eventType": "load_test", "metadata": {"plan": "pro"}}
                          for _ in range(batch)]}
    start = rows()
    lags, stop = [], threading.Event()

    def watch():
        while not stop.is_set():
            v = metric("kafka_consumer_fetch_manager_records_lag_max")
            if v is not None:
                lags.append(v)
            time.sleep(0.25)

    threading.Thread(target=watch, daemon=True).start()
    pool = [http.client.HTTPConnection(HOST, PORT) for _ in range(conns)]
    lat, codes, lock = [], {}, threading.Lock()

    def worker(i):
        local = []
        for _ in range(batches // conns):
            local.append(post(pool[i], "/api/v1/events/batch", payload))
        with lock:
            for ms, st in local:
                lat.append(ms)
                codes[st] = codes.get(st, 0) + 1

    t0 = time.perf_counter()
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(conns)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    accept = time.perf_counter() - t0

    sent = batch * batches
    t1 = time.perf_counter()
    while rows() < start + sent and time.perf_counter() - t1 < 300:
        time.sleep(0.25)
    drain = time.perf_counter() - t1
    stop.set()

    print(f"\n=== Throughput: {sent:,} events, {batches} batch requests, {conns} connections ===")
    print(f"  accepted            : {sent / accept:>10,.0f} events/s")
    print(f"  persisted end-to-end: {sent / (accept + drain):>10,.0f} events/s")
    print(f"  drain after last 202: {drain:>10.2f} s")
    print(f"  peak consumer lag   : {max(lags) if lags else 0:>10,.0f} records")
    print(f"  status codes        : {codes}")
    print("  The accept/persist gap is what Kafka is buying: the backlog is the")
    print("  burst Postgres could not have taken synchronously.")


def latency(n=2000, conns=8):
    """Single-event ingest, the shape a real analytics client produces."""
    lat, codes, lock = [], {}, threading.Lock()

    def worker(_):
        c = http.client.HTTPConnection(HOST, PORT)
        local = []
        for _ in range(n // conns):
            local.append(post(c, "/api/v1/events",
                              {"eventType": "single", "metadata": {"plan": "pro"}}))
        with lock:
            for ms, st in local:
                lat.append(ms)
                codes[st] = codes.get(st, 0) + 1

    t0 = time.perf_counter()
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(conns)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    el = time.perf_counter() - t0
    s = sorted(lat)
    print(f"\n=== Latency: {n:,} single-event POSTs, {conns} connections ===")
    print(f"  throughput : {n / el:,.0f} req/s")
    print(f"  p50        : {pct(s, .50):.1f} ms")
    print(f"  p95        : {pct(s, .95):.1f} ms")
    print(f"  p99        : {pct(s, .99):.1f} ms")
    print(f"  max        : {s[-1]:.1f} ms")
    print(f"  codes      : {codes}")


def outage(seconds=40):
    """
    Stop Postgres mid-ingest. This is the test that justifies Kafka at all.

    It only passes because the API key lookup is cached: when authentication
    read the database on every request, ingest died with Postgres despite the
    broker being healthy, and every event sent during the outage was lost.
    """
    c = http.client.HTTPConnection(HOST, PORT, timeout=10)
    post(c, "/api/v1/events", {"eventType": "warmup"})  # establish the cache entry
    time.sleep(3)
    before = rows()

    print(f"\n=== Outage: Postgres stopped for {seconds}s while ingest continues ===")
    subprocess.run(["docker", "compose", "stop", "postgres"], capture_output=True)
    sent = ok = 0
    bad = {}
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < seconds:
        st = post(c, "/api/v1/events", {"eventType": "during_outage"})[1]
        sent += 1
        if st == 202:
            ok += 1
        else:
            bad[st] = bad.get(st, 0) + 1
        time.sleep(0.05)
    subprocess.run(["docker", "compose", "start", "postgres"], capture_output=True)

    t1 = time.perf_counter()
    while rows() < before + ok and time.perf_counter() - t1 < 180:
        time.sleep(1)
    after = rows()
    print(f"  sent during outage : {sent}")
    print(f"  accepted (202)     : {ok}")
    print(f"  failures           : {bad if bad else 'none'}")
    print(f"  recovered          : {after - before} of {ok}")
    print(f"  VERDICT            : "
          f"{'buffered and drained' if after - before >= ok and ok > 0 else 'EVENTS LOST'}")
    print("  Note: an outage longer than the error handler's 30s max backoff is")
    print("  not covered here. Raise --outage-seconds to probe that.")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", default="all",
                    choices=["all", "throughput", "latency", "outage"])
    ap.add_argument("--outage-seconds", type=int, default=40)
    a = ap.parse_args()

    if metric("kafka_consumer_fetch_manager_records_lag_max") is None:
        print("Cannot read metrics. Is the app running with ADMIN_TOKEN=local-dev-admin-token?")
        sys.exit(1)

    if a.phase in ("all", "throughput"):
        throughput()
    if a.phase in ("all", "latency"):
        latency()
    if a.phase in ("all", "outage"):
        outage(a.outage_seconds)
