# Changelog

## 1.0.0 — 2026-10-10

Initial release of RelayForge.

- Project-scoped API keys, operator provisioning, and immediate key replacement.
- HTTPS webhook subscriptions with encrypted signing secrets and HMAC-SHA256 delivery signatures.
- Transactional event acceptance, canonical idempotency checks, pending capacity limits, and durable minute quotas.
- PostgreSQL-backed worker claims, lease recovery, fenced completion, bounded retry cycles, and dead-letter replay.
- Cursor-paginated delivery history, attempt records, and project audit history.
- Structured logs, request IDs, health probes, and authenticated Prometheus metrics.
- Real PostgreSQL integration tests, local failure demos, Docker Compose, CI configuration, and architecture documentation.
- Temporary DNS failure recovery, retry-budget reduction handling, and automated Java formatting checks.
