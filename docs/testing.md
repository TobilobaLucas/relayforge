# Testing the behavior that matters

Run `sh ./mvnw verify`, or `.\mvnw.cmd verify` on Windows. Tests start an isolated PostgreSQL 17.10 instance using Zonky’s embedded PostgreSQL launcher. This is a native PostgreSQL process with real transactions and locks, not an in-memory substitute. Flyway creates the schema before the application tests run.

The test launcher runs on common desktop platforms. PostgreSQL binaries are downloaded as Maven test dependencies. Run as a regular OS user; PostgreSQL refuses `initdb` as root. The production jar contains neither the embedded database launcher nor the demo receiver.

## The important scenarios

| Scenario | What the check establishes |
| --- | --- |
| Concurrent identical submissions | Twelve callers share one idempotency key; only one event, delivery, and quota charge commit. |
| Object key reordering | Equivalent nested object order returns the same event; changed content conflicts. |
| Insertion failure | A deliberate delivery constraint failure rolls back the event, quota, and audit record together. |
| Concurrent claims | Eight workers claim forty jobs without duplicate live claims. |
| Expired lease | A new worker reclaims the job; the previous token cannot complete it. |
| Crash on final attempt | Exhausted expired claims become dead letters rather than getting stuck in flight. |
| Replay | The same delivery ID receives a new retry cycle while lifetime attempt history remains intact. |
| Transient and permanent errors | Retryable failures receive a future due time; permanent failures stop immediately. |
| Capacity and rate limits | Rejected requests do not create partial work; duplicates do not consume quota again. |
| Tenant access | Another project’s IDs cannot be read, paused, replayed, or used to expose attempt history. |
| Key replacement | The old credential fails after rotation and the new credential succeeds. |
| HTTP signing | A separate receiver-side HMAC implementation verifies the exact bytes sent. |
| Redirect response | A redirect is terminal and its target is never contacted. |
| DNS rebinding | A destination that changes after URL validation is rejected at connection resolution. |
| Temporary DNS outage | Failed resolution retries without weakening the blocked-address policy. |
| Reduced retry budget | Previously queued attempts become dead letters once they have consumed the new budget. |
| Slow receiver | Header response timeout returns the job to a retryable state. |
| Worker bound | A worker limited to two slots drains a queue while never exceeding that limit. |
| Pagination | Equal timestamps still traverse every row once through the UUID tie-breaker. |
| Secret encryption | Fresh nonces, ciphertext integrity, and endpoint binding are checked. |
| Address policy | Parameterised checks cover private, reserved, IPv6, and public addresses. |

`@AutoConfigureObservability` enables the Prometheus exporter in the integration test context; Spring Boot otherwise disables that exporter for tests. Metrics security is tested with both operator and tenant credentials.

## Reports and CI

JUnit reports appear in `target/surefire-reports`. JaCoCo produces `target/site/jacoco/index.html` during `verify`. Coverage is evidence of exercised branches, not proof of correctness; there is no arbitrary percentage gate in this version.

The CI workflow runs verification on Linux and archives reports. A separate Compose job runs the Python demo against the container stack. CodeQL and Dependabot configurations provide ongoing repository checks once enabled on GitHub. Having a workflow file does not mean those external jobs have already run; [verification evidence](verification.md) records the local results.

## What these checks do not prove

The concurrency tests deliberately overlap operations, but they cannot enumerate every possible interleaving. Lease recovery is tested through expired database timestamps rather than killing a container mid-write. These tests do not establish a throughput limit, a high-availability database design, cross-region behavior, infrastructure SSRF protection, or a complete security audit.

Before making a performance claim, publish the environment, load shape, endpoint latency, dataset size, error rate, and raw results. Before deploying publicly, test the actual ingress, egress policy, database privileges, and recovery procedure.
