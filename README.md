# Java HTTP Load Balancer

A Layer-7 (HTTP) load balancer for Java 21. It uses the JDK's built-in HTTP server
and `java.net.http.HttpClient`, handles each request on a virtual thread, and
depends only on Jackson (for the JSON config).

## Features
- **Strategies:** `round_robin`, `weighted_round_robin` (smooth, nginx-style), `least_connections`, `ip_hash` (sticky)
- **Active health checks:** probes `GET <backend><health_check_path>` on an interval
- **Passive health checks:** a backend that fails or times out is removed right away
- **Automatic retry:** a failed request is retried on another backend. A request with a
  body is replayed only if the backend refused the connection, so it never reached it
  and can't be applied twice.
- **Headers:** adds `X-Forwarded-For`, `X-Forwarded-Host` and `X-Forwarded-Proto`, and drops hop-by-hop headers
- **Status endpoint:** `GET /lb/status` returns per-backend health, connections, request and failure counts
- **Graceful shutdown:** on SIGINT/SIGTERM, in-flight requests get up to 15 seconds to finish

## Build & run
```bash
mvn package                      # compiles, runs tests, builds target/loadbalancer.jar
```
```bash
for p in 9001 9002 9003; do java -cp target/loadbalancer.jar com.example.loadbalancer.DemoBackend $p & done
```
```bash
java -jar target/loadbalancer.jar -config config.json
```
```bash
curl localhost:8080/
```
```bash
curl localhost:8080/lb/status
```

## Config (`config.json`)
| key | default | meaning |
|---|---|---|
| `listen` | `:8080` | address to listen on (`host:port` or `:port`) |
| `strategy` | `round_robin` | balancing algorithm |
| `max_retries` | `0` | extra backends to try if one fails |
| `backend_timeout` | `30s` | time to wait for response headers |
| `health_check_path` | `/health` | path probed on each backend |
| `health_check_interval` | `5s` | how often to probe |
| `health_check_timeout` | `2s` | probe timeout |
| `max_body_bytes` | `10485760` | larger request bodies get `413` (bodies are buffered so they can be retried) |
| `backends[].url` / `.weight` | — / `1` | upstream servers |

Durations accept `ms`, `s`, `m` and `h`. An unknown key fails at startup, which catches typos.

## Layout (`src/main/java/com/example/loadbalancer/`)
- `Main.java` – startup, signal handling, graceful shutdown
- `Config.java` – JSON config, defaults, validation
- `Backend.java` – backend state (alive flag, counters)
- `strategy/` – `Strategy` interface plus the four algorithms
- `LoadBalancer.java` – request forwarding, retry/failover, status endpoint
- `HealthChecker.java` – active health checker
- `DemoBackend.java` – demo upstream server
- `src/test/.../LoadBalancerTest.java` – JUnit 5 tests
