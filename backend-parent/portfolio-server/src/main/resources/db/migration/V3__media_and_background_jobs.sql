CREATE TABLE portfolio.background_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    last_error_summary VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT background_job_idempotency_key_uk UNIQUE (idempotency_key),
    CONSTRAINT background_job_job_type_nonblank_ck CHECK (
        job_type = btrim(job_type) AND length(job_type) > 0
    ),
    CONSTRAINT background_job_idempotency_key_nonblank_ck CHECK (
        idempotency_key = btrim(idempotency_key) AND length(idempotency_key) > 0
    ),
    CONSTRAINT background_job_payload_object_ck CHECK (
        jsonb_typeof(payload) = 'object'
    ),
    CONSTRAINT background_job_status_ck CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')
    ),
    CONSTRAINT background_job_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT background_job_lease_ck CHECK (
        (
            status = 'RUNNING'
            AND lease_owner IS NOT NULL
            AND lease_owner = btrim(lease_owner)
            AND length(lease_owner) > 0
            AND lease_until IS NOT NULL
        )
        OR (
            status <> 'RUNNING'
            AND lease_owner IS NULL
            AND lease_until IS NULL
        )
    )
);

CREATE INDEX background_job_ready_idx
    ON portfolio.background_job (next_run_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX background_job_expired_lease_idx
    ON portfolio.background_job (lease_until)
    WHERE status = 'RUNNING';

CREATE TRIGGER background_job_set_updated_at
BEFORE UPDATE ON portfolio.background_job
FOR EACH ROW EXECUTE FUNCTION portfolio.set_updated_at();

CREATE TABLE portfolio.maintenance_run (
    id UUID PRIMARY KEY,
    run_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    artifact_checksum VARCHAR(64),
    error_summary VARCHAR(500),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT maintenance_run_run_type_nonblank_ck CHECK (
        run_type = btrim(run_type) AND length(run_type) > 0
    ),
    CONSTRAINT maintenance_run_status_ck CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT maintenance_run_artifact_checksum_ck CHECK (
        artifact_checksum IS NULL OR artifact_checksum ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT maintenance_run_details_ck CHECK (
        jsonb_typeof(details) = 'object'
        AND octet_length(details::text) <= 8192
        AND (
            details - ARRAY[
                'input_count',
                'output_count',
                'processed_count',
                'deleted_count',
                'failed_count',
                'skipped_count',
                'object_count',
                'cutoff_epoch_second'
            ]
        ) = '{}'::jsonb
        AND CASE
            WHEN NOT (details ? 'input_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'input_count') <> 'number' THEN FALSE
            WHEN (details ->> 'input_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'input_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'output_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'output_count') <> 'number' THEN FALSE
            WHEN (details ->> 'output_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'output_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'processed_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'processed_count') <> 'number' THEN FALSE
            WHEN (details ->> 'processed_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'processed_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'deleted_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'deleted_count') <> 'number' THEN FALSE
            WHEN (details ->> 'deleted_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'deleted_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'failed_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'failed_count') <> 'number' THEN FALSE
            WHEN (details ->> 'failed_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'failed_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'skipped_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'skipped_count') <> 'number' THEN FALSE
            WHEN (details ->> 'skipped_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'skipped_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'object_count') THEN TRUE
            WHEN jsonb_typeof(details -> 'object_count') <> 'number' THEN FALSE
            WHEN (details ->> 'object_count') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'object_count')::NUMERIC <= 9223372036854775807
        END
        AND CASE
            WHEN NOT (details ? 'cutoff_epoch_second') THEN TRUE
            WHEN jsonb_typeof(details -> 'cutoff_epoch_second') <> 'number' THEN FALSE
            WHEN (details ->> 'cutoff_epoch_second') !~ '^(0|[1-9][0-9]{0,18})$' THEN FALSE
            ELSE (details ->> 'cutoff_epoch_second')::NUMERIC <= 9223372036854775807
        END
    ),
    CONSTRAINT maintenance_run_timing_ck CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (
            status IN ('SUCCEEDED', 'FAILED')
            AND finished_at IS NOT NULL
            AND finished_at >= started_at
        )
    )
);

COMMENT ON COLUMN portfolio.maintenance_run.details IS
    'Bounded operational counts and cutoffs only; values are non-negative 64-bit JSON integers under an explicit top-level allowlist and cannot contain paths, object keys, credentials, exception text, or PII.';

CREATE TABLE portfolio.media_asset (
    id UUID PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    bucket VARCHAR(128),
    region VARCHAR(64),
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT media_asset_storage_identity_uk
        UNIQUE NULLS NOT DISTINCT (provider, bucket, region, object_key),
    CONSTRAINT media_asset_provider_ck CHECK (
        provider IN ('LOCAL', 'TENCENT_COS')
    ),
    CONSTRAINT media_asset_storage_metadata_ck CHECK (
        (provider = 'LOCAL' AND bucket IS NULL AND region IS NULL)
        OR (
            provider = 'TENCENT_COS'
            AND bucket IS NOT NULL
            AND bucket = btrim(bucket)
            AND length(bucket) > 0
            AND region IS NOT NULL
            AND region = btrim(region)
            AND length(region) > 0
        )
    ),
    CONSTRAINT media_asset_object_key_nonblank_ck CHECK (
        object_key = btrim(object_key) AND length(object_key) > 0
    ),
    CONSTRAINT media_asset_original_filename_nonblank_ck CHECK (
        original_filename = btrim(original_filename) AND length(original_filename) > 0
    ),
    CONSTRAINT media_asset_mime_type_nonblank_ck CHECK (
        mime_type = btrim(mime_type) AND length(mime_type) > 0
    ),
    CONSTRAINT media_asset_byte_size_ck CHECK (byte_size > 0),
    CONSTRAINT media_asset_dimensions_ck CHECK (
        (width IS NULL OR width > 0) AND (height IS NULL OR height > 0)
    ),
    CONSTRAINT media_asset_sha256_ck CHECK (
        sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT media_asset_status_ck CHECK (
        status IN ('PROCESSING', 'READY', 'FAILED', 'ARCHIVED', 'PENDING_DELETE')
    ),
    CONSTRAINT media_asset_archive_ck CHECK (
        (
            status IN ('ARCHIVED', 'PENDING_DELETE')
            AND archived_at IS NOT NULL
        )
        OR (
            status NOT IN ('ARCHIVED', 'PENDING_DELETE')
            AND archived_at IS NULL
        )
    ),
    CONSTRAINT media_asset_version_ck CHECK (version >= 0)
);

CREATE INDEX media_asset_status_idx
    ON portfolio.media_asset (status, created_at DESC);
CREATE INDEX media_asset_sha_idx
    ON portfolio.media_asset (sha256);
CREATE INDEX media_asset_created_at_id_idx
    ON portfolio.media_asset (created_at DESC, id DESC);
CREATE INDEX media_asset_archived_idx
    ON portfolio.media_asset (archived_at, id)
    WHERE status = 'ARCHIVED';

CREATE TRIGGER media_asset_set_updated_at
BEFORE UPDATE ON portfolio.media_asset
FOR EACH ROW EXECUTE FUNCTION portfolio.set_updated_at();

CREATE TABLE portfolio.media_variant (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL,
    variant_name VARCHAR(32) NOT NULL,
    format VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT media_variant_asset_fk FOREIGN KEY (asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT media_variant_asset_name_uk UNIQUE (asset_id, variant_name),
    CONSTRAINT media_variant_variant_name_nonblank_ck CHECK (
        variant_name = btrim(variant_name) AND length(variant_name) > 0
    ),
    CONSTRAINT media_variant_format_ck CHECK (
        format IN ('JPEG', 'PNG', 'PDF')
    ),
    CONSTRAINT media_variant_object_key_nonblank_ck CHECK (
        object_key = btrim(object_key) AND length(object_key) > 0
    ),
    CONSTRAINT media_variant_mime_type_nonblank_ck CHECK (
        mime_type = btrim(mime_type) AND length(mime_type) > 0
    ),
    CONSTRAINT media_variant_byte_size_ck CHECK (byte_size > 0),
    CONSTRAINT media_variant_dimensions_ck CHECK (
        (width IS NULL OR width > 0) AND (height IS NULL OR height > 0)
    ),
    CONSTRAINT media_variant_sha256_ck CHECK (
        sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT media_variant_status_ck CHECK (
        status IN ('PROCESSING', 'READY', 'FAILED')
    )
);

CREATE TABLE portfolio.media_translation (
    asset_id UUID NOT NULL,
    locale VARCHAR(10) NOT NULL,
    alt_text VARCHAR(500),
    caption VARCHAR(1000),
    credit VARCHAR(300),
    source_url VARCHAR(2048),
    CONSTRAINT media_translation_pk PRIMARY KEY (asset_id, locale),
    CONSTRAINT media_translation_asset_fk FOREIGN KEY (asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE CASCADE,
    CONSTRAINT media_translation_locale_ck CHECK (
        locale IN ('zh-CN', 'en')
    )
);

REVOKE ALL PRIVILEGES ON TABLE portfolio.background_job FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.background_job FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON TABLE portfolio.maintenance_run FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.maintenance_run FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_asset FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_asset FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_variant FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_variant FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_translation FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE portfolio.media_translation FROM portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE portfolio.background_job TO portfolio_runtime_access;
GRANT UPDATE (
    status, attempts, next_run_at, lease_owner, lease_until, last_error_summary
) ON TABLE portfolio.background_job TO portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE portfolio.maintenance_run TO portfolio_runtime_access;
GRANT UPDATE (
    status, artifact_checksum, error_summary, details, finished_at
) ON TABLE portfolio.maintenance_run TO portfolio_runtime_access;

GRANT SELECT, INSERT, DELETE ON TABLE portfolio.media_asset TO portfolio_runtime_access;
GRANT UPDATE (
    status, archived_at, version
) ON TABLE portfolio.media_asset TO portfolio_runtime_access;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE portfolio.media_variant TO portfolio_runtime_access;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE portfolio.media_translation TO portfolio_runtime_access;
