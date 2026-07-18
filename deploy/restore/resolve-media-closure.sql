\set ON_ERROR_STOP on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH selected_historical_revision AS (
    SELECT DISTINCT value::UUID AS revision_id
    FROM unnest(string_to_array(:'historical_revision_ids', ',')) AS value
)
SELECT CASE
    WHEN count(*) > 0 AND bool_and(
        EXISTS (
            SELECT 1
            FROM portfolio.content_revision AS revision
            WHERE revision.id = selected.revision_id
        )
        AND NOT EXISTS (
            SELECT 1
            FROM portfolio.publication AS publication
            WHERE publication.current_revision_id = selected.revision_id
        )
    ) THEN 'true'
    ELSE 'false'
END AS historical_revision_is_valid
FROM selected_historical_revision AS selected
\gset

\if :historical_revision_is_valid
\else
\echo historical revision must exist and be non-current
SELECT 1 / 0 AS historical_revision_must_exist_and_be_non_current;
\endif

WITH
selected_historical_revision AS (
    SELECT DISTINCT value::UUID AS revision_id
    FROM unnest(string_to_array(:'historical_revision_ids', ',')) AS value
),
current_publication_revision AS (
    SELECT DISTINCT publication.current_revision_id AS revision_id
    FROM portfolio.publication AS publication
    WHERE publication.current_revision_id IS NOT NULL
),
required_revision AS (
    SELECT
        revision_id,
        bool_or(required_by_current) AS required_by_current,
        bool_or(required_by_historical) AS required_by_historical
    FROM (
        SELECT
            current_revision.revision_id,
            TRUE AS required_by_current,
            FALSE AS required_by_historical
        FROM current_publication_revision AS current_revision
        UNION ALL
        SELECT
            selected.revision_id,
            FALSE AS required_by_current,
            TRUE AS required_by_historical
        FROM selected_historical_revision AS selected
    ) AS revision_union
    GROUP BY revision_id
),
required_reference AS (
    SELECT
        reference.asset_id,
        reference.variant_name,
        bool_or(required.required_by_current) AS required_by_current,
        bool_or(required.required_by_historical) AS required_by_historical
    FROM portfolio.revision_media_reference AS reference
    JOIN required_revision AS required
      ON required.revision_id = reference.revision_id
    GROUP BY reference.asset_id, reference.variant_name
)
SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM required_reference AS reference
    LEFT JOIN portfolio.media_variant AS variant
      ON variant.asset_id = reference.asset_id
     AND variant.variant_name = reference.variant_name
    WHERE variant.id IS NULL
       OR variant.status <> 'READY'
) THEN 'true' ELSE 'false' END AS required_variants_are_ready
\gset

\if :required_variants_are_ready
\else
\echo required revision media variant is missing or not READY
SELECT 1 / 0 AS required_revision_media_variant_must_be_ready;
\endif

WITH
selected_historical_revision AS (
    SELECT DISTINCT value::UUID AS revision_id
    FROM unnest(string_to_array(:'historical_revision_ids', ',')) AS value
),
current_publication_revision AS (
    SELECT DISTINCT publication.current_revision_id AS revision_id
    FROM portfolio.publication AS publication
    WHERE publication.current_revision_id IS NOT NULL
),
required_revision AS (
    SELECT
        revision_id,
        bool_or(required_by_current) AS required_by_current,
        bool_or(required_by_historical) AS required_by_historical
    FROM (
        SELECT
            current_revision.revision_id,
            TRUE AS required_by_current,
            FALSE AS required_by_historical
        FROM current_publication_revision AS current_revision
        UNION ALL
        SELECT
            selected.revision_id,
            FALSE AS required_by_current,
            TRUE AS required_by_historical
        FROM selected_historical_revision AS selected
    ) AS revision_union
    GROUP BY revision_id
),
required_reference AS (
    SELECT
        reference.asset_id,
        reference.variant_name,
        bool_or(required.required_by_current) AS required_by_current,
        bool_or(required.required_by_historical) AS required_by_historical
    FROM portfolio.revision_media_reference AS reference
    JOIN required_revision AS required
      ON required.revision_id = reference.revision_id
    GROUP BY reference.asset_id, reference.variant_name
),
closure AS (
    SELECT
        asset.id AS asset_id,
        'ORIGINAL'::text AS object_kind,
        'ORIGINAL'::text AS variant_name,
        bool_or(reference.required_by_current) AS required_by_current,
        bool_or(reference.required_by_historical) AS required_by_historical,
        asset.provider,
        asset.bucket,
        asset.region,
        asset.object_key,
        asset.mime_type,
        asset.byte_size,
        asset.sha256
    FROM required_reference AS reference
    JOIN portfolio.media_asset AS asset
      ON asset.id = reference.asset_id
    GROUP BY
        asset.id,
        asset.provider,
        asset.bucket,
        asset.region,
        asset.object_key,
        asset.mime_type,
        asset.byte_size,
        asset.sha256
    UNION ALL
    SELECT
        variant.asset_id,
        'VARIANT'::text AS object_kind,
        variant.variant_name,
        reference.required_by_current,
        reference.required_by_historical,
        asset.provider,
        asset.bucket,
        asset.region,
        variant.object_key,
        variant.mime_type,
        variant.byte_size,
        variant.sha256
    FROM required_reference AS reference
    JOIN portfolio.media_variant AS variant
      ON variant.asset_id = reference.asset_id
     AND variant.variant_name = reference.variant_name
     AND variant.status = 'READY'
    JOIN portfolio.media_asset AS asset
      ON asset.id = variant.asset_id
)
SELECT
    asset_id,
    object_kind,
    variant_name,
    CASE WHEN required_by_current THEN 'true' ELSE 'false' END,
    CASE WHEN required_by_historical THEN 'true' ELSE 'false' END,
    provider,
    coalesce(bucket, ''),
    coalesce(region, ''),
    object_key,
    mime_type,
    byte_size,
    sha256
FROM closure
ORDER BY asset_id, object_kind, variant_name;

COMMIT;
