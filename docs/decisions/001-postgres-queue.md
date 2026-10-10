# ADR 001: Use PostgreSQL as the durable delivery queue

Status: accepted.

## Context

Accepting an event must not lose its delivery work. Adding a message broker immediately would introduce another service and require an outbox relay to connect a database transaction to broker publication. This project needs a reliable, inspectable acceptance boundary and a straightforward local setup.

## Decision

Store events and delivery jobs in PostgreSQL in one transaction. Workers claim due jobs with `FOR UPDATE SKIP LOCKED`, commit the claim, then perform HTTP outside the transaction. Partial indexes support ready and expired work.

Use a short project-row lock to protect acceptance quotas and subscription changes. This favours a simple, testable capacity guarantee over maximum write throughput within one project.

## Consequences

The database is both the record of accepted events and the durable work ledger. A worker can crash without an in-memory queue needing to be restored. Local development needs one storage service, and the tests can exercise its actual locking behavior.

The queue consumes database write and index capacity. Per-project acceptance is serialised, and global due-time ordering does not provide tenant fairness. Retention and archival become important as history grows.

If measurement justifies a broker later, keep the event acceptance transaction and add an outbox relay. That relay must tolerate duplicate publication and should not replace the receiver’s delivery deduplication contract.

Reference: PostgreSQL describes `SKIP LOCKED` as useful for queue-like consumers in its [SELECT documentation](https://www.postgresql.org/docs/17/sql-select.html).
