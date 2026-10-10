# Roadmap

The current version is a complete demonstration of durable acceptance and delivery. These are the next changes that would matter for a shared public service, in roughly the order they should be investigated.

1. **Retention and archival.** Set explicit lifetimes for events, attempts, and audits. Preserve active work and define when an idempotency key can be reused. Measure queue queries against a large retained dataset before choosing partitioning.
2. **Destination limits and fair scheduling.** Bound in-flight attempts per endpoint and prevent one project’s backlog from dominating all workers. Add concurrency tests that exercise the scheduling policy across tenants.
3. **Receiver failure controls.** Add a destination circuit breaker with shared durable state or an explicit per-instance policy. Decide how it interacts with retries, paused endpoints, and replay.
4. **Secret rotation.** Support overlapping webhook signing secrets and encryption key versions, with a documented re-encryption and rollback procedure.
5. **Operator identity and permissions.** Replace the shared operator token with an OIDC-backed administration boundary and scoped roles when there are multiple human operators.
6. **Load and fault experiments.** Publish reproducible results for measured workloads. Include large history tables, slow destinations, worker termination, and database interruptions.
7. **A broker only when justified.** If the PostgreSQL queue becomes the measured bottleneck, add an outbox relay and a broker while keeping idempotent consumers and receiver deduplication.

Each item needs a behavior change, an operational decision, and tests. Adding another technology without those requirements would make this project harder to explain without improving its guarantees.
