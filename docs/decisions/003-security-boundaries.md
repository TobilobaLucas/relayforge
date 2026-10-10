# ADR 003: Separate project credentials and verify outbound destinations

Status: accepted.

## Context

Webhook clients need credentials scoped to their own records and can submit URLs that become outbound HTTP destinations. A URL checked only once can change its resolved address before a later retry.

## Decision

Use random project API keys stored as hashes, with a separate operator token for provisioning and metrics. Determine the tenant from authentication rather than a request field. Encrypt endpoint signing secrets using AES-GCM and bind each ciphertext to its endpoint ID.

Require HTTPS and permitted public addresses by default. Validate at registration, validate again before sending, and resolve through a connection manager that returns only vetted addresses. Disable redirect following and automatic transport retries. Keep development exceptions explicit and host-specific.

## Consequences

Tenant queries and database relationships both enforce project ownership. Signing secrets remain recoverable for delivery without appearing in normal API reads. Connection-time validation closes the gap between registration and later DNS resolution.

A fresh connection per attempt costs additional handshakes. An infrastructure egress policy is still required for a public deployment, and secret rotation needs a deliberate migration rather than swapping an environment variable. The operator token is intentionally a privileged service credential, not a full human identity system.
