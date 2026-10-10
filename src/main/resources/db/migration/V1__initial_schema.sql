CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    api_key_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    rate_window TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    rate_count INTEGER NOT NULL DEFAULT 0 CHECK (rate_count >= 0)
);

CREATE TABLE endpoints (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    url VARCHAR(2048) NOT NULL,
    description VARCHAR(240) NOT NULL,
    event_types JSONB NOT NULL CHECK (jsonb_typeof(event_types) = 'array'),
    encrypted_secret TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (project_id, id)
);
CREATE INDEX endpoints_project_idx ON endpoints(project_id);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    event_type VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    envelope TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (project_id, idempotency_key),
    UNIQUE (project_id, id)
);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    event_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','IN_FLIGHT','SUCCEEDED','DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    cycle_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempts >= 0),
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_http_status INTEGER,
    last_error VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (project_id, event_id) REFERENCES events(project_id, id),
    FOREIGN KEY (project_id, endpoint_id) REFERENCES endpoints(project_id, id),
    UNIQUE (event_id, endpoint_id),
    CHECK (cycle_attempts <= attempt_count),
    CHECK ((status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX deliveries_due_idx ON deliveries(next_attempt_at, id) WHERE status = 'READY';
CREATE INDEX deliveries_expired_idx ON deliveries(lease_until, id) WHERE status = 'IN_FLIGHT';
CREATE INDEX deliveries_project_page_idx ON deliveries(project_id, created_at DESC, id DESC);
CREATE INDEX deliveries_project_pending_idx ON deliveries(project_id) WHERE status IN ('READY','IN_FLIGHT');

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES deliveries(id),
    attempt_number INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (outcome IN ('IN_PROGRESS','SUCCEEDED','FAILED','LEASE_EXPIRED')),
    http_status INTEGER,
    duration_ms BIGINT CHECK (duration_ms >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    UNIQUE (delivery_id, attempt_number),
    CHECK ((outcome = 'IN_PROGRESS') = (finished_at IS NULL))
);
CREATE INDEX attempts_delivery_idx ON delivery_attempts(delivery_id, attempt_number);

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    action VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_project_idx ON audit_log(project_id, id DESC);
