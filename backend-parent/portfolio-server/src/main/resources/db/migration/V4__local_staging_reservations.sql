CREATE TABLE portfolio.local_staging_policy (
    singleton_key SMALLINT PRIMARY KEY,
    active_capacity INTEGER NOT NULL,
    scan_entry_ceiling INTEGER NOT NULL,
    worst_case_entries_per_reservation SMALLINT NOT NULL,
    reserved_headroom INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT local_staging_policy_singleton_ck CHECK (singleton_key = 1),
    CONSTRAINT local_staging_policy_active_capacity_ck CHECK (
        active_capacity BETWEEN 1 AND 1000000
    ),
    CONSTRAINT local_staging_policy_scan_entry_ceiling_ck CHECK (
        scan_entry_ceiling BETWEEN 1 AND 1000000
    ),
    CONSTRAINT local_staging_policy_worst_case_entries_ck CHECK (
        worst_case_entries_per_reservation = 6
    ),
    CONSTRAINT local_staging_policy_reserved_headroom_ck CHECK (
        reserved_headroom BETWEEN 1 AND 1000000
    ),
    CONSTRAINT local_staging_policy_capacity_ck CHECK (
        active_capacity::NUMERIC * worst_case_entries_per_reservation::NUMERIC
            + reserved_headroom::NUMERIC
        < scan_entry_ceiling::NUMERIC
    )
);

CREATE TABLE portfolio.local_staging_reservation (
    asset_id UUID PRIMARY KEY,
    sha256 VARCHAR(64) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    cleanup_job_id UUID NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cleanup_after TIMESTAMPTZ NOT NULL,
    CONSTRAINT local_staging_reservation_cleanup_job_uk UNIQUE (cleanup_job_id),
    CONSTRAINT local_staging_reservation_cleanup_job_fk
        FOREIGN KEY (cleanup_job_id)
        REFERENCES portfolio.background_job(id) ON DELETE RESTRICT,
    CONSTRAINT local_staging_reservation_sha256_ck CHECK (
        sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT local_staging_reservation_mime_type_ck CHECK (
        mime_type IN ('image/jpeg', 'image/png', 'application/pdf')
    ),
    CONSTRAINT local_staging_reservation_generation_ck CHECK (generation >= 0),
    CONSTRAINT local_staging_reservation_cleanup_after_ck CHECK (
        cleanup_after >= reserved_at + interval '24 hours'
    )
);

CREATE INDEX local_staging_reservation_cleanup_after_idx
    ON portfolio.local_staging_reservation (cleanup_after, asset_id);

CREATE FUNCTION portfolio.enforce_local_staging_reservation_state()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.generation <> 0 THEN
            RAISE EXCEPTION 'local staging reservation must start at generation zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.asset_id IS DISTINCT FROM OLD.asset_id
       OR NEW.sha256 IS DISTINCT FROM OLD.sha256
       OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
       OR NEW.reserved_at IS DISTINCT FROM OLD.reserved_at
       OR NEW.cleanup_after IS DISTINCT FROM OLD.cleanup_after THEN
        RAISE EXCEPTION 'local staging reservation identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.generation::NUMERIC <> OLD.generation::NUMERIC + 1
       OR NEW.cleanup_job_id IS NOT DISTINCT FROM OLD.cleanup_job_id THEN
        RAISE EXCEPTION 'local staging reservation successor is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION portfolio.enforce_local_staging_reservation_state()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION portfolio.enforce_local_staging_reservation_state()
    FROM portfolio_runtime_access;

CREATE TRIGGER local_staging_reservation_state_guard
BEFORE INSERT OR UPDATE ON portfolio.local_staging_reservation
FOR EACH ROW EXECUTE FUNCTION portfolio.enforce_local_staging_reservation_state();

REVOKE ALL PRIVILEGES ON TABLE portfolio.local_staging_policy FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.local_staging_policy
    FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON TABLE portfolio.local_staging_reservation FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.local_staging_reservation
    FROM portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE portfolio.local_staging_policy
    TO portfolio_runtime_access;
GRANT SELECT, INSERT, DELETE ON TABLE portfolio.local_staging_reservation
    TO portfolio_runtime_access;
GRANT UPDATE (generation, cleanup_job_id)
    ON TABLE portfolio.local_staging_reservation
    TO portfolio_runtime_access;
