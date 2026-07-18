\set ON_ERROR_STOP on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET TRANSACTION SNAPSHOT :'backup_snapshot';

WITH media_rows AS (
    SELECT
        asset.id AS asset_id,
        'ORIGINAL'::TEXT AS object_kind,
        'ORIGINAL'::TEXT AS variant_name,
        asset.provider,
        asset.bucket,
        asset.region,
        asset.object_key,
        asset.mime_type,
        asset.byte_size,
        asset.sha256
    FROM portfolio.media_asset AS asset
    UNION ALL
    SELECT
        variant.asset_id,
        'VARIANT'::TEXT,
        variant.variant_name,
        asset.provider,
        asset.bucket,
        asset.region,
        variant.object_key,
        variant.mime_type,
        variant.byte_size,
        variant.sha256
    FROM portfolio.media_variant AS variant
    JOIN portfolio.media_asset AS asset ON asset.id = variant.asset_id
), canonical_rows AS (
    SELECT jsonb_agg(
        jsonb_build_object(
            'assetId', asset_id,
            'objectKind', object_kind,
            'variantName', variant_name,
            'provider', provider,
            'bucket', bucket,
            'region', region,
            'objectKey', object_key,
            'mimeType', mime_type,
            'byteSize', byte_size,
            'sha256', sha256
        )
        ORDER BY asset_id, object_kind, variant_name
    ) AS value
    FROM media_rows
), row_counts AS (
    SELECT jsonb_build_object(
        'flywaySchemaHistory', (SELECT count(*) FROM portfolio.flyway_schema_history),
        'publication', (SELECT count(*) FROM portfolio.publication),
        'contentRevision', (SELECT count(*) FROM portfolio.content_revision),
        'revisionMediaReference', (SELECT count(*) FROM portfolio.revision_media_reference),
        'mediaAsset', (SELECT count(*) FROM portfolio.media_asset),
        'mediaVariant', (SELECT count(*) FROM portfolio.media_variant),
        'contactMessage', (SELECT count(*) FROM portfolio.contact_message),
        'adminUser', (SELECT count(*) FROM portfolio.admin_user)
    ) AS value
)
SELECT jsonb_build_object(
    'schemaVersion', 1,
    'snapshotId', :'backup_snapshot',
    'rows', coalesce(canonical_rows.value, '[]'::JSONB),
    'rowCounts', row_counts.value
)::TEXT
FROM canonical_rows CROSS JOIN row_counts;

COMMIT;
