# Operating RelayForge

The Compose stack and disposable Java launcher are development environments. A public deployment needs a real database, secret storage, a TLS ingress, and outbound network policy. This guide explains the application controls and what remains the operator’s responsibility.

## Build and start

```bash
sh ./mvnw verify
java -jar target/relayforge-1.0.0.jar
```

Supply environment variables before starting the jar:

| Variable | Required / default | Purpose |
| --- | --- | --- |
| `RELAY_ADMIN_TOKEN` | Required, 32–128 characters | Operator authentication. Generate a random value. |
| `RELAY_ENCRYPTION_KEY` | Required, Base64 of exactly 32 bytes | Encrypts endpoint signing secrets. Keep it stable across restarts. |
| `DATABASE_URL` | Local PostgreSQL JDBC URL | Connection URL; use TLS options for a remote database. |
| `DATABASE_USERNAME` | `relayforge` | Database user. |
| `DATABASE_PASSWORD` | Development-only default | Set explicitly outside local development. |
| `PORT` | `8080` | HTTP listen port. |
| `RELAY_WORKER_ENABLED` | `true` | Enable queue polling on this instance. |
| `RELAY_CONCURRENCY` | `16`, range 1–128 | Maximum active attempts per instance. |
| `RELAY_POLL_DELAY_MS` | `500` | Delay between polling runs. |
| `RELAY_DEVELOPMENT_HOSTS` | Empty | Exact comma-separated hosts permitted to use private addresses and HTTP. Keep empty in production. |

Other values can be supplied through Spring Boot’s normal property binding, such as `RELAY_MAX_PENDING_PER_PROJECT`, `RELAY_EVENTS_PER_MINUTE`, `RELAY_MAX_ATTEMPTS`, `RELAY_REQUEST_TIMEOUT`, and `RELAY_LEASE_DURATION`. The default request timeout is ten seconds per configured network phase, and the lease is 45 seconds. Configuration requires the lease to be at least three times the request timeout.

The network timeout is not a hard wall-clock deadline covering DNS resolution and every possible address fallback. Set OS resolver timeouts and an egress policy for your environment. Lease recovery and fencing protect queue state if an attempt outlives its lease, but a remote receiver can still see duplicates.

## Database and migrations

Flyway applies versioned SQL migrations at startup. The schema is defined in `src/main/resources/db/migration`. Never edit an applied migration in a running deployment; add a new version. The initial schema may be changed while developing this unreleased repository, but a released database must preserve its migration checksums.

The development user owns the schema. A production deployment should separate migration privileges from runtime privileges and run migrations before instances start. PostgreSQL constraints and transactions are part of the queue’s correctness, so swapping in a different database requires a real redesign and integration tests.

Keep the encryption key with the database’s recovery material. A database restored without that key can show event history but cannot decrypt endpoint secrets. API keys are stored as hashes and cannot be recovered from the database; rotate a lost project key.

## Multiple instances

Instances sharing one database can poll the same queue. `SKIP LOCKED` coordinates claims; the per-project acceptance lock coordinates quota checks. All instances must use the same encryption key and operator token. Their per-instance concurrency limits add together, so start with a modest total and measure PostgreSQL connection use and receiver latency.

Keep the retry budget consistent across instances as well. If you reduce `RELAY_MAX_ATTEMPTS`, the reaper moves pending jobs that have already consumed the new budget to dead letters with `attempt_budget_exhausted`. Active claims can still finish; expired final claims remain recoverable through the reaper. Increase the budget or explicitly replay a dead letter only after deciding that more attempts are appropriate.

Set `RELAY_WORKER_ENABLED=false` on instances reserved for API traffic. Worker-enabled instances still run the HTTP application; place their HTTP ports on an internal network if they do not serve public traffic. There is no separate headless worker binary in this release.

Shutdown stops new polling and gives active worker tasks up to 30 seconds to finish. Unfinished claims recover through lease expiry. The database is the durable work ledger; no shutdown hook has to flush an in-memory queue.

## Health and metrics

`/actuator/health/liveness` describes application liveness. `/actuator/health/readiness` includes the database. Both are public and do not reveal detailed connection information. Use readiness for traffic routing and liveness for restart decisions.

`/actuator/prometheus` requires the operator token. Tenant keys cannot scrape it. The optional Compose Prometheus instance receives its credential through a temporary file, and its configuration does not contain a committed token.

Useful metrics include:

| Metric | Interpretation |
| --- | --- |
| `relay_queue_deliveries{status="READY"}` | Pending jobs, including jobs delayed for retry or a paused endpoint. |
| `relay_queue_deliveries{status="IN_FLIGHT"}` | Claimed work. |
| `relay_queue_deliveries{status="DEAD"}` | Retained terminal failures. |
| `relay_delivery_attempts_total{outcome=...}` | Completed worker attempts; resets with process restart. |
| `relay_delivery_duration_seconds_*` | Transport attempt duration. |
| `http_server_requests_seconds_*` | API timing and response status. |
| Hikari connection metrics | Database pool pressure. |

Queue gauges refresh every 15 seconds and represent retained database rows. Attempt counters are per-process telemetry, so the database attempt history remains the audit source. There are no per-tenant metric labels, which avoids unbounded label cardinality. The supplied alert rules are examples; tune thresholds to an observed baseline. Alert delivery is not configured.

## A receiver is down

1. Inspect `/deliveries` for the affected project, then the attempt history for a delivery.
2. Confirm whether failures are transient responses, permanent responses, transport errors, or expired leases. The service retains status codes and bounded error categories, not response bodies.
3. Pause the endpoint if sending should stop. Existing in-flight requests may finish.
4. Fix or resume the receiver. Resume the endpoint when it is ready.
5. Replay dead-letter deliveries deliberately. A replay keeps the delivery ID; the receiver still needs deduplication.

Do not replay an entire backlog simply because a receiver is healthy again. Confirm the business effect is still wanted. Pending transient retries continue without replay.

## The queue is growing

First check whether the receiver is slow or paused, whether the database connection pool is saturated, and whether workers are enabled. A full project queue returns `429` instead of accepting unbounded delivery work. More workers help only when there is spare receiver and database capacity.

The current scheduler prioritises globally due work. It does not guarantee tenant fairness or a per-endpoint concurrency cap. A busy destination can consume many worker slots. For a large shared service, add fairness and destination-specific limits before promising isolation of latency.

## Backup and retention

Take PostgreSQL backups, test restoration, and retain the encryption key through the same recovery window. This repository does not provision a backup schedule.

Events, attempts, deliveries, and audit records currently remain until explicitly managed by an operator. Their growth eventually affects aggregate queries and storage. A retention policy must decide how long idempotency keys remain reserved and how much replay history to retain. Do not delete an event while its delivery history is still referenced. An archival design is listed in the roadmap.

## Public deployment

Put API traffic behind a TLS ingress and configure request body and connection limits there. Use a managed PostgreSQL database or an independently operated database with backups. Store credentials in a secret manager, restrict operator and metrics routes at the network layer, and keep development host exceptions empty.

Application URL validation should be paired with a firewall or egress proxy that denies private networks, metadata services, and disallowed destinations. Apply database resource limits and infrastructure-level protection to authentication and general HTTP traffic; the application’s event quota is not a complete abuse-control system.

The image runs without root, has a read-only filesystem in Compose, and exposes readiness for the container runtime. Image tags should be updated and pinned to reviewed digests when producing deployment artifacts. No cloud environment or public deployment is provisioned by this repository.
