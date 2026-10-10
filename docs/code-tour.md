# A short code tour

Start with the event transaction and the queue. Those two pieces explain most of the system’s reliability behavior.

## Acceptance: `EventService`

`publish` checks the request shape, calculates a canonical request hash, and locks the project. The idempotency check runs before quota accounting. A new event and all its deliveries are inserted inside one Spring transaction.

Look for what happens if a delivery insert fails. There is no separate asynchronous publishing step to lose; PostgreSQL rolls back the event and quota changes as well. The integration test deliberately makes that insertion fail.

## Coordination: `ProjectService`

The project row lock is shared by event acceptance, endpoint changes, and replay. `reserveCapacity` counts pending delivery slots while that lock is held. `consumeEventQuota` uses the database’s calendar minute, so a second application instance cannot reset a client’s quota simply by restarting.

This is a simple correctness boundary with a clear cost: one project’s acceptance writes are serialised. If a workload needs much higher per-project throughput, the lock and aggregate quota count are things to measure and redesign.

## Delivery lifecycle: `DeliveryQueue`

`claim` locks a bounded set of due jobs and skips rows already claimed by another transaction. Each claim gets a new random token and a durable attempt row. The transport does not run while the database transaction is open.

`complete` verifies that token before applying an outcome. `reapExhaustedWork` handles a crash during the final allowed attempt and work that has already consumed a reduced retry budget. Together they explain why two workers do not own the same live claim, why an old worker cannot overwrite a reclaimed job, and why exhausted work cannot stay queued indefinitely.

## HTTP boundary: `HttpWebhookTransport`

The transport uses the stored event envelope, decrypts the endpoint-specific secret, adds an HMAC signature, and performs one outbound HTTP attempt. Its connection manager resolves through the URL policy instead of trusting a hostname checked only during registration. It rejects redirects and does not buffer response bodies.

The response becomes a bounded result: status, error category, duration, and a capped retry hint. Network policy and TLS remain important when running outside the demo.

## Execution: `DeliveryWorker` and `DeliveryProcessor`

The worker owns polling and concurrency limits. The processor owns the transport-to-queue completion path and telemetry. The separation makes it possible to test worker limits with a blocking fake transport while retaining the real database queue.

## Security and API

`AuthenticationFilter` resolves a project API key to a tenant principal. Controllers pass its project ID to services rather than accepting a client-supplied project ID. `SecretVault` binds encrypted secrets to endpoint IDs using authenticated encryption.

The controllers handle HTTP status, validation, and location headers. `ApiExceptionHandler` and `RequestFilter` provide client-safe errors, request IDs, and a bounded raw request body. The SQL schema adds project-consistent foreign keys and lifecycle constraints.

## Evidence

`RelayForgeIntegrationTest` uses real PostgreSQL, real service transactions, HTTP security filters, and local HTTP receivers. Read the concurrent submission, claim, recovery, and signing tests first. The [demo scripts](../scripts) provide a quicker live demonstration of retries and replay.
