# API guide

The base path is `/api/v1`. JSON bodies use `Content-Type: application/json`. The live schema is at `/v3/api-docs`, and Swagger UI is at `/swagger-ui.html`.

## Credentials

There are two credentials with separate roles. The operator token provisions projects and rotates their keys. A project API key can access only that project’s resources. Both use `Authorization: Bearer <token>`. An operator token does not grant access to tenant routes.

API keys and signing secrets are returned only when created. Store them when you receive them. Project key rotation immediately invalidates the previous key; there is no grace period. Endpoint URLs, subscriptions, and signing secrets are immutable in this version. Register a replacement endpoint when they need to change, then pause the old one; existing delivery history remains attached to it.

## Routes

| Method | Route | Credential | Result |
| --- | --- | --- | --- |
| POST | `/admin/projects` | Operator | `201`, project and one-time API key |
| GET | `/admin/projects` | Operator | Up to 100 newest projects |
| GET | `/admin/projects/{id}` | Operator | Project metadata |
| POST | `/admin/projects/{id}/rotate-key` | Operator | New one-time API key |
| GET | `/project` | Project | Current project metadata |
| POST | `/endpoints` | Project | `201`, endpoint and one-time signing secret |
| GET | `/endpoints` | Project | Up to 100 endpoints |
| GET | `/endpoints/{id}` | Project | Endpoint metadata |
| PATCH | `/endpoints/{id}` | Project | Pause or resume with `{"enabled":false}` or `true` |
| POST | `/events` | Project | `202` new acceptance, `200` identical retry |
| GET | `/events/{id}` | Project | Stored event envelope |
| GET | `/deliveries` | Project | Keyset-paginated deliveries |
| GET | `/deliveries/{id}` | Project | Delivery status and counters |
| GET | `/deliveries/{id}/attempts` | Project | Ordered attempt history |
| POST | `/deliveries/{id}/replay` | Project | Requeue a dead-letter delivery |
| GET | `/audit` | Project | Project audit history |

## Register an endpoint

```bash
curl -sS http://localhost:8080/api/v1/endpoints \
  -H "Authorization: Bearer $PROJECT_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "url":"https://your-receiver.example/webhooks/orders",
    "description":"Fulfilment notifications",
    "eventTypes":["orders.created","orders.cancelled"]
  }'
```

Replace the illustrative hostname with a real public HTTPS receiver. Registration checks the URL and its resolved addresses; it does not send a verification request. The project may have 100 endpoints, each subscribed to 1–20 event types. Matching is exact; wildcard subscriptions are not supported. Event types start with a lowercase letter and contain up to 100 lowercase letters, digits, periods, underscores, or hyphens.

The response contains `endpoint` metadata and `signingSecret`. Later reads do not return the secret. A paused endpoint receives no newly accepted events and is skipped during queue claims. Deliveries accepted before pausing remain pending until it resumes, and an already executing attempt may complete.

## Publish an event

```http
POST /api/v1/events
Authorization: Bearer rf_live_...
Idempotency-Key: order-42-created
Content-Type: application/json

{"type":"orders.created","data":{"orderId":"order-42","amount":1299}}
```

```json
{
  "eventId": "b88c977b-8b08-4eb3-b39c-b8ac91fe092a",
  "deliveryCount": 2,
  "duplicate": false
}
```

`data` must be a JSON object. The canonical request is limited to 65,536 UTF-8 bytes. Raw HTTP request bodies have a separate bounded allowance for envelope fields and whitespace. Unknown body properties are rejected to catch client typos.

The key contains 1–128 letters, digits, periods, underscores, colons, or hyphens. Keys are scoped to a project and remain reserved as long as the stored event exists. Object keys are sorted recursively before hashing; arrays retain their order. Number representations are compared according to Jackson’s serialised representation; this is an application convention, not an implementation of an external canonical JSON standard.

An identical request with the same key returns `200`, the original event ID, and `duplicate:true`. It does not consume quota or create new deliveries. Changed content with the same key returns `409`. A retry after a network timeout should reuse the key. A separate business event should use a new key.

An event with no enabled matching subscribers is still accepted, with `deliveryCount:0`. Endpoints added later do not receive previously accepted events.

## Inspect deliveries

```http
GET /api/v1/deliveries?status=DEAD&limit=20
Authorization: Bearer rf_live_...
```

`status` accepts `READY`, `IN_FLIGHT`, `SUCCEEDED`, or `DEAD`. `eventId` can narrow the list to one event. Limits range from 1 to 100, with 20 as the default.

The response is `{"items":[...],"nextCursor":"..."}`. Send the returned cursor as `cursor` for the next page. Keep filters unchanged during a traversal. A null cursor means the end of the current result set. Treat cursors as opaque; they are not authorisation tokens.

Attempt history distinguishes `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, and `LEASE_EXPIRED`. A failed attempt may lead to another retry or to dead letters; inspect the delivery’s status for the current lifecycle state.

Audit history uses the same page shape, but its `nextCursor` is an integer ID to send as `beforeId`, rather than the delivery page’s encoded cursor. The API key determines whose history is returned.

## Replay after fixing a receiver

```http
POST /api/v1/deliveries/{id}/replay
Authorization: Bearer rf_live_...
```

Only a `DEAD` delivery can be replayed. Replay keeps its delivery ID and previous attempts, starts a new retry cycle, and consumes pending capacity and one submission from the project’s minute quota. A replayed delivery for a paused endpoint waits until that endpoint resumes. Ten replays are allowed per delivery.

## Webhook contract

RelayForge sends the stored envelope as an HTTP POST:

```json
{
  "id": "b88c977b-8b08-4eb3-b39c-b8ac91fe092a",
  "type": "orders.created",
  "createdAt": "2026-10-07T00:00:00Z",
  "data": {"orderId":"order-42","amount":1299}
}
```

| Header | Meaning |
| --- | --- |
| `Webhook-Id` | Stable delivery UUID; deduplicate side effects using this value. |
| `Webhook-Event-Id` | The event UUID shared by its fanout deliveries. |
| `Webhook-Attempt` | Monotonically increasing claim number, including replay. |
| `Webhook-Signature` | `t=<epoch-seconds>,v1=<lowercase-hex-hmac>` |

The signing message is `timestamp + "." + deliveryId + "." + rawBody`. The secret is used literally as UTF-8 text, including its `whsec_` prefix; do not Base64-decode it. Use HMAC-SHA256, compare in constant time, and reject timestamps outside your accepted window. Verify the raw bytes before JSON parsing. The [Python receiver](../examples/receiver.py) demonstrates a five-minute window.

Return any `2xx` after durably recording the side effect. `408`, `429`, `5xx`, and transport errors retry within the budget. Redirects and other non-2xx statuses stop immediately. RelayForge does not read or retain response bodies.

## Errors and quotas

Errors use `application/problem+json` with a stable `code` and `requestId`:

```json
{
  "type":"urn:relayforge:problem:idempotency_conflict",
  "title":"Conflict",
  "status":409,
  "detail":"This idempotency key was already used for a different event.",
  "code":"idempotency_conflict",
  "requestId":"client-request-42"
}
```

`400` covers invalid input and unsafe endpoint URLs, `401` invalid credentials, `403` the wrong credential role, `404` missing or inaccessible tenant resources, `409` state conflicts, `413` large payloads, and `429` quota limits. Database trouble during authentication returns `503`; other unexpected failures return a generic `500` with a request ID.

The default project limit is 10,000 pending deliveries and 1,000 new events or replays per database-clock calendar minute. Fanout consumes one event submission but one pending slot per delivery. Duplicate submissions consume neither. Rate-limit errors do not currently include a `Retry-After` header; clients should back off and use their original idempotency key when appropriate.
