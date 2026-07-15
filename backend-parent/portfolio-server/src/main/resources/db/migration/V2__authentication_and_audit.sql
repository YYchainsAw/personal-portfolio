CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

CREATE TABLE admin_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton_key BOOLEAN NOT NULL DEFAULT TRUE,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    totp_key_version INTEGER NOT NULL,
    totp_nonce BYTEA NOT NULL,
    totp_ciphertext BYTEA NOT NULL,
    last_login_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT admin_user_singleton_uk UNIQUE (singleton_key),
    CONSTRAINT admin_user_singleton_ck CHECK (singleton_key),
    CONSTRAINT admin_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT admin_user_username_ck CHECK (username = btrim(username) AND length(username) BETWEEN 3 AND 64),
    CONSTRAINT admin_user_totp_nonce_ck CHECK (octet_length(totp_nonce) = 12)
);

CREATE UNIQUE INDEX admin_user_username_lower_uk ON admin_user (lower(username));
CREATE TRIGGER admin_user_set_updated_at
BEFORE UPDATE ON admin_user
FOR EACH ROW EXECUTE FUNCTION portfolio.set_updated_at();

CREATE TABLE totp_recovery_code (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX totp_recovery_code_available_ix
    ON totp_recovery_code (admin_id, created_at, id)
    WHERE used_at IS NULL;

CREATE TABLE admin_session_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    session_primary_id CHAR(36) UNIQUE
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE SET NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ended_at TIMESTAMPTZ,
    client_summary VARCHAR(255) NOT NULL,
    revocation_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admin_session_metadata_status_ck CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT admin_session_metadata_terminal_ck CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL AND revocation_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED') AND ended_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);

CREATE INDEX admin_session_metadata_admin_status_ix
    ON admin_session_metadata (admin_id, status, created_at DESC);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_admin_id UUID REFERENCES admin_user(id) ON DELETE RESTRICT,
    action VARCHAR(96) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128),
    outcome VARCHAR(16) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT audit_log_outcome_ck CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT audit_log_metadata_object_ck CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX audit_log_created_at_ix ON audit_log (created_at DESC, id);
CREATE INDEX audit_log_actor_ix ON audit_log (actor_admin_id, created_at DESC);

CREATE OR REPLACE FUNCTION portfolio.reject_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = portfolio, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is immutable' USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION portfolio.reject_audit_mutation() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.reject_audit_mutation() TO portfolio_runtime_access;

CREATE TRIGGER audit_log_reject_mutation
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION portfolio.reject_audit_mutation();

CREATE TRIGGER audit_log_reject_truncate
BEFORE TRUNCATE ON audit_log
FOR EACH STATEMENT EXECUTE FUNCTION portfolio.reject_audit_mutation();

GRANT SELECT, INSERT, UPDATE, DELETE ON SPRING_SESSION TO portfolio_runtime_access;
GRANT SELECT, INSERT, UPDATE, DELETE ON SPRING_SESSION_ATTRIBUTES TO portfolio_runtime_access;
GRANT SELECT, INSERT, UPDATE ON admin_user TO portfolio_runtime_access;
GRANT SELECT, INSERT, UPDATE, DELETE ON totp_recovery_code TO portfolio_runtime_access;
GRANT SELECT, INSERT, UPDATE ON admin_session_metadata TO portfolio_runtime_access;
GRANT SELECT, INSERT ON audit_log TO portfolio_runtime_access;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM portfolio_runtime_access;
