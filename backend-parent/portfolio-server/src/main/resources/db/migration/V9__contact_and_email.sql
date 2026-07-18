CREATE TABLE portfolio.contact_message (
    id UUID NOT NULL,
    visitor_name VARCHAR(100) NOT NULL,
    visitor_email VARCHAR(320) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    body VARCHAR(5000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    dedupe_key CHAR(64) NOT NULL,
    privacy_accepted_at TIMESTAMPTZ NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT contact_message_pk PRIMARY KEY (id),
    CONSTRAINT contact_message_status_ck CHECK (
        status IN ('UNREAD', 'READ', 'ARCHIVED', 'SPAM')
    ),
    CONSTRAINT contact_message_dedupe_key_ck CHECK (
        dedupe_key ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT contact_message_version_ck CHECK (version >= 0),
    CONSTRAINT contact_message_privacy_time_ck CHECK (
        privacy_accepted_at <= created_at
    )
);

CREATE INDEX contact_message_inbox_idx
    ON portfolio.contact_message (status, created_at DESC, id DESC);

CREATE INDEX contact_message_dedupe_idx
    ON portfolio.contact_message (dedupe_key, created_at DESC);

CREATE INDEX contact_message_retention_idx
    ON portfolio.contact_message (created_at, id);

CREATE TRIGGER contact_message_set_updated_at
BEFORE UPDATE ON portfolio.contact_message
FOR EACH ROW
EXECUTE FUNCTION portfolio.set_updated_at();

CREATE TABLE portfolio.email_outbox (
    id UUID NOT NULL,
    contact_message_id UUID NOT NULL,
    template_name VARCHAR(80) NOT NULL,
    to_address VARCHAR(320) NOT NULL,
    stable_message_id VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    last_error_summary VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT email_outbox_pk PRIMARY KEY (id),
    CONSTRAINT email_outbox_contact_message_fk FOREIGN KEY (contact_message_id)
        REFERENCES portfolio.contact_message(id) ON DELETE CASCADE,
    CONSTRAINT email_outbox_contact_message_id_uk UNIQUE (contact_message_id),
    CONSTRAINT email_outbox_stable_message_id_uk UNIQUE (stable_message_id),
    CONSTRAINT email_outbox_status_ck CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD', 'CANCELED')
    ),
    CONSTRAINT email_outbox_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT email_outbox_lease_state_ck CHECK (
        (
            status = 'SENDING'
            AND lease_owner IS NOT NULL
            AND lease_owner = btrim(lease_owner)
            AND lease_owner <> ''
            AND lease_until IS NOT NULL
        )
        OR (
            status <> 'SENDING'
            AND lease_owner IS NULL
            AND lease_until IS NULL
        )
    ),
    CONSTRAINT email_outbox_sent_at_ck CHECK (
        (status = 'SENT') = (sent_at IS NOT NULL)
    )
);

CREATE INDEX email_outbox_ready_idx
    ON portfolio.email_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX email_outbox_expired_lease_idx
    ON portfolio.email_outbox (lease_until, created_at, id)
    WHERE status = 'SENDING';

CREATE TRIGGER email_outbox_set_updated_at
BEFORE UPDATE ON portfolio.email_outbox
FOR EACH ROW
EXECUTE FUNCTION portfolio.set_updated_at();

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.contact_message,
    portfolio.email_outbox
FROM PUBLIC;

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.contact_message,
    portfolio.email_outbox
FROM portfolio_runtime_access;

GRANT SELECT, INSERT, DELETE ON TABLE
    portfolio.contact_message
TO portfolio_runtime_access;

GRANT UPDATE (status, version, updated_at)
    ON TABLE portfolio.contact_message
TO portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE
    portfolio.email_outbox
TO portfolio_runtime_access;

GRANT UPDATE (
    status,
    attempts,
    next_attempt_at,
    lease_owner,
    lease_until,
    last_error_summary,
    sent_at,
    updated_at
)
    ON TABLE portfolio.email_outbox
TO portfolio_runtime_access;
