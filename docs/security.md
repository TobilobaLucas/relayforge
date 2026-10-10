# Security boundaries

RelayForge has two classes of users: a trusted operator and a project client. Project clients can configure outbound receivers, so URLs and receiver responses are untrusted. The security design focuses on separating projects and preventing webhook delivery from becoming an unrestricted network client.

## Authentication and project access

The operator token is provided through configuration and compared using fixed-length SHA-256 digests and a constant-time comparison. Project keys contain 32 random bytes and are stored as SHA-256 hashes. Hashing is suitable here because the keys are generated with high entropy; this is not a password-storage design.

Authentication sets a `Tenant` principal containing the project ID. Tenant services apply that ID to reads and writes, and other projects’ resource IDs return `404`. The database also enforces project consistency with composite foreign keys. Operator and tenant roles have separate route permissions. Authentication does not create an HTTP session or use cookies; CSRF protection is disabled for the bearer-token API.

The operator is a privileged service credential, with no human user management or fine-grained operator roles. Access to that token permits project provisioning and key replacement. General request throttling, user login, and project-member permissions are outside the current scope.

## Signing secret storage

Endpoint secrets are encrypted using AES-256-GCM, a fresh 96-bit nonce, and the endpoint UUID as authenticated additional data. Copying ciphertext to another endpoint does not produce a usable secret. Secrets are returned only at creation and do not appear in endpoint reads or logs.

The encryption key is supplied as Base64-encoded 32 bytes. Losing it prevents delivery from decrypting stored secrets. Replacing it without re-encrypting stored values also breaks delivery. Automated encryption-key rotation and overlapping webhook signing secrets are not implemented; plan rotation before using this as a long-lived public service.

Event payloads are stored as plaintext database text. Payload encryption, classification, and retention require deployment-specific decisions. Avoid putting access tokens or unnecessary personal information in webhook data.

## Outbound request safety

Registration requires an absolute HTTPS URL without user info or a fragment. Resolved destinations must be public. The policy rejects loopback, private, link-local, multicast, shared-address, and selected special-purpose and documentation ranges. IPv6 is limited to permitted global unicast ranges.

The transport validates the URL again and supplies its own DNS resolver to the HTTP connection manager. That resolver returns only vetted addresses for the actual connection, so a hostname cannot pass registration with a public address and then silently connect to a private address. Redirect handling, cookie storage, automatic retries, and response-body buffering are disabled. TLS uses the HTTP client’s standard certificate and hostname verification.

A temporary DNS lookup failure during delivery is treated as a retryable transport failure. A resolved address rejected by the network policy is a permanent unsafe-destination failure. Registration still rejects endpoints that cannot currently be resolved and validated.

For local demos, an explicit exact-host allowlist permits HTTP and private addresses. The default list is empty. The Compose configuration enables only `receiver`; the disposable launcher enables only `localhost`. Never carry these exceptions into a public environment.

The address policy is intentionally conservative, but it is not a replacement for an infrastructure egress policy or a constantly updated registry of every special-purpose allocation. Use a firewall or egress proxy to protect internal services and cloud metadata endpoints. Limit DNS resolution at the OS or network level.

## Receiver verification

Verify `Webhook-Signature` against the **raw body bytes**, the `Webhook-Id`, and its signed timestamp. Reject timestamps outside an accepted window and compare the HMAC in constant time. Store the delivery ID with the side effect to prevent duplicate processing. An attempt header is informational and is not part of the signed message.

The examples verify signatures and use a five-minute clock window, but their in-memory deduplication is only a demonstration. A real receiver needs durable deduplication and a reliable clock. Receiver configuration routes in the demo are intentionally unauthenticated and published only on loopback; they are not part of the production application.

## Logs and errors

Logs contain request IDs, paths, methods, status codes, duration, delivery IDs, and outcomes. They do not log bearer tokens, signing secrets, webhook bodies, receiver response bodies, or query strings. Unexpected server errors are described generically to clients, with a request ID for operator investigation.

Do not paste `.env`, API keys, or sensitive payloads into issues. Health probes and the API schema are public by design; operational metrics require the operator token. A deployment may restrict schema access through its ingress.

## Reporting a vulnerability

For a public GitHub repository, enable private vulnerability reporting in the repository settings and use that feature. Avoid public issues containing a working exploit against a running deployment or credentials. This repository does not invent a security contact address for its future owner.
