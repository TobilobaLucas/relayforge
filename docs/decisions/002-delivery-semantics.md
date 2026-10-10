# ADR 002: Bounded at-least-once delivery with lease fencing

Status: accepted.

## Context

A receiver can commit a side effect while its acknowledgement is lost. A worker can also send a request and crash before recording the result. The sender cannot safely infer whether either event occurred.

## Decision

Use a stable delivery UUID across retries and replay, and document receiver-side deduplication. Claim work with a random lease token and deadline. A later claim replaces the token; completion checks token ownership before changing the durable state.

Count every claim against a five-attempt cycle budget, including claims whose worker crashes. Retry transient failures with jitter and honour capped retry hints. Move permanent or exhausted work to dead letters, preserving history. Explicit replay starts a new cycle with the same delivery UUID.

## Consequences

Remote effects may happen more than once unless the receiver stores its delivery IDs durably. Worker recovery remains safe for local queue state because stale completions are fenced out. The system can explain terminal failures rather than retry indefinitely.

A slow request may outlive its lease and overlap a new attempt. Timeouts, lease sizing, deduplication, and operational monitoring all matter. Event ordering is not guaranteed, and a finite budget means successful delivery is not inevitable.
