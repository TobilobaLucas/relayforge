# About RelayForge

I built RelayForge to explore a backend problem that comes up in real products: how do you notify other services reliably when something happens?

Imagine an online store that creates an order. Its billing and fulfilment services may both need to know about it. Sending those notifications during the original request is fragile. If one receiver is slow or offline, the store should still be able to accept the order. RelayForge accepts the event first, stores the work in PostgreSQL, and sends signed webhooks in the background.

Each project has its own API key and webhook endpoints. An event is sent only to endpoints subscribed to its type. If a receiver fails temporarily, RelayForge retries after a delay. If the failure is permanent, the delivery stays in a dead-letter state so I can inspect its attempts and replay it after fixing the receiver.

The part I find most interesting is what happens when several workers run at once or a worker crashes. PostgreSQL lets workers claim different jobs without holding a database transaction open during the HTTP request. Each claim gets a lease token, so an old worker cannot overwrite the result from a newer one. Submitting the same event twice with the same idempotency key also creates only one event.

I used Java 21 and Spring Boot for the API and workers, PostgreSQL for storage and job coordination, and Flyway for database migrations. The repository includes tests against real PostgreSQL, a local demo that shows retries and replay, an OpenAPI page, metrics, and setup instructions.

RelayForge sends webhooks **at least once within a limited retry budget**. A receiver may see the same delivery more than once, so it should use the stable `Webhook-Id` to avoid repeating its own work. The [README](README.md) explains how to run the project, and the [architecture guide](docs/architecture.md) explains the design in more detail.
