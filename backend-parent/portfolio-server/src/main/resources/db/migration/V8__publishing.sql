CREATE TABLE portfolio.content_revision (
    id UUID NOT NULL,
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id UUID NOT NULL,
    version BIGINT NOT NULL,
    snapshot_schema_version INTEGER NOT NULL,
    snapshot JSONB NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    published_by UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT content_revision_pk PRIMARY KEY (id),
    CONSTRAINT content_revision_aggregate_type_ck CHECK (
        aggregate_type IN ('SITE', 'PROJECT', 'PROJECT_CATALOG')
    ),
    CONSTRAINT content_revision_version_ck CHECK (version > 0),
    CONSTRAINT content_revision_snapshot_schema_version_ck CHECK (
        snapshot_schema_version > 0
    ),
    CONSTRAINT content_revision_checksum_ck CHECK (
        checksum ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT content_revision_admin_fk FOREIGN KEY (published_by)
        REFERENCES portfolio.admin_user(id) ON DELETE RESTRICT,
    CONSTRAINT content_revision_aggregate_version_uk
        UNIQUE (aggregate_type, aggregate_id, version)
);

CREATE TABLE portfolio.publication (
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_revision_id UUID,
    current_slug VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    CONSTRAINT publication_pk PRIMARY KEY (aggregate_type, aggregate_id),
    CONSTRAINT publication_aggregate_type_ck CHECK (
        aggregate_type IN ('SITE', 'PROJECT', 'PROJECT_CATALOG')
    ),
    CONSTRAINT publication_status_ck CHECK (
        status IN ('PUBLISHED', 'ARCHIVED')
    ),
    CONSTRAINT publication_revision_fk FOREIGN KEY (current_revision_id)
        REFERENCES portfolio.content_revision(id) ON DELETE RESTRICT,
    CONSTRAINT publication_version_ck CHECK (version >= 0),
    CONSTRAINT publication_current_revision_ck CHECK (
        status = 'ARCHIVED' OR current_revision_id IS NOT NULL
    ),
    CONSTRAINT publication_current_slug_ck CHECK (
        current_slug IS NULL
        OR current_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    )
);

INSERT INTO portfolio.publication (
    aggregate_type,
    aggregate_id,
    status,
    version
)
VALUES
    (
        'SITE',
        '00000000-0000-0000-0000-000000000001'::UUID,
        'ARCHIVED',
        0
    ),
    (
        'PROJECT_CATALOG',
        '00000000-0000-0000-0000-000000000002'::UUID,
        'ARCHIVED',
        0
    );

CREATE TABLE portfolio.slug_redirect (
    old_slug VARCHAR(120) NOT NULL,
    project_id UUID NOT NULL,
    new_slug VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT slug_redirect_pk PRIMARY KEY (old_slug),
    CONSTRAINT slug_redirect_old_slug_ck CHECK (
        old_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT slug_redirect_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT slug_redirect_new_slug_ck CHECK (
        new_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT slug_redirect_distinct_ck CHECK (old_slug <> new_slug)
);

CREATE TABLE portfolio.revision_media_reference (
    revision_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    variant_name VARCHAR(32) NOT NULL,
    usage VARCHAR(32) NOT NULL,
    CONSTRAINT revision_media_reference_pk
        PRIMARY KEY (revision_id, asset_id, variant_name, usage),
    CONSTRAINT revision_media_reference_revision_fk FOREIGN KEY (revision_id)
        REFERENCES portfolio.content_revision(id) ON DELETE RESTRICT,
    CONSTRAINT revision_media_reference_asset_fk FOREIGN KEY (asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT revision_media_reference_variant_fk
        FOREIGN KEY (asset_id, variant_name)
        REFERENCES portfolio.media_variant(asset_id, variant_name)
        ON DELETE RESTRICT
);

CREATE INDEX idx_content_revision_aggregate
    ON portfolio.content_revision (
        aggregate_type,
        aggregate_id,
        version DESC
    );

CREATE UNIQUE INDEX uq_publication_current_slug
    ON portfolio.publication (current_slug)
    WHERE status = 'PUBLISHED'
      AND current_slug IS NOT NULL;

CREATE INDEX idx_revision_media_asset
    ON portfolio.revision_media_reference (asset_id, variant_name);

CREATE OR REPLACE FUNCTION portfolio.reject_published_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'published revisions are immutable'
        USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION
    portfolio.reject_published_history_mutation()
FROM PUBLIC;

REVOKE ALL ON FUNCTION
    portfolio.reject_published_history_mutation()
FROM portfolio_runtime_access;

CREATE TRIGGER content_revision_immutable
BEFORE UPDATE OR DELETE ON portfolio.content_revision
FOR EACH ROW
EXECUTE FUNCTION portfolio.reject_published_history_mutation();

CREATE TRIGGER revision_media_reference_immutable
BEFORE UPDATE OR DELETE ON portfolio.revision_media_reference
FOR EACH ROW
EXECUTE FUNCTION portfolio.reject_published_history_mutation();

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.content_revision,
    portfolio.publication,
    portfolio.slug_redirect,
    portfolio.revision_media_reference
FROM PUBLIC;

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.content_revision,
    portfolio.publication,
    portfolio.slug_redirect,
    portfolio.revision_media_reference
FROM portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE
    portfolio.content_revision,
    portfolio.revision_media_reference
TO portfolio_runtime_access;

GRANT SELECT, INSERT, UPDATE ON TABLE
    portfolio.publication
TO portfolio_runtime_access;

GRANT SELECT, INSERT ON TABLE
    portfolio.slug_redirect
TO portfolio_runtime_access;
