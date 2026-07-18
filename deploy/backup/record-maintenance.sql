\set ON_ERROR_STOP on

BEGIN;

SELECT 1 / CASE
    WHEN :'run_type' IN ('DATABASE_BACKUP', 'MEDIA_BACKUP')
     AND :'status' IN ('SUCCEEDED', 'FAILED')
     AND (
         (:'status' = 'SUCCEEDED'
          AND :'artifact_sha' ~ '^[0-9a-f]{64}$'
          AND :'error_category' = 'NONE')
         OR
         (:'status' = 'FAILED'
          AND :'artifact_sha' = ''
          AND :'error_category' IN (
              'CONFIGURATION_FAILED', 'SNAPSHOT_FAILED', 'DATABASE_FAILED',
              'MEDIA_FAILED', 'UPLOAD_FAILED', 'VERIFY_FAILED',
              'MAINTENANCE_WRITE_FAILED',
              'NOTIFICATION_FAILED', 'INTERNAL_FAILED'
          ))
     )
    THEN 1 ELSE 0
END AS allowlisted_backup_result;

INSERT INTO portfolio.maintenance_run (
    id,
    run_type,
    status,
    artifact_checksum,
    error_summary,
    details,
    started_at,
    finished_at
)
VALUES (
    :'run_id'::UUID,
    :'run_type',
    :'status',
    NULLIF(:'artifact_sha', ''),
    NULLIF(:'error_category', 'NONE'),
    '{}'::JSONB,
    :'started_at'::TIMESTAMPTZ,
    :'finished_at'::TIMESTAMPTZ
)
ON CONFLICT (id) DO NOTHING;

SELECT 1 / CASE WHEN EXISTS (
    SELECT 1
    FROM portfolio.maintenance_run AS run
    WHERE run.id = :'run_id'::UUID
      AND run.run_type = :'run_type'
      AND run.status = :'status'
      AND run.artifact_checksum IS NOT DISTINCT FROM NULLIF(:'artifact_sha', '')
      AND run.error_summary IS NOT DISTINCT FROM NULLIF(:'error_category', 'NONE')
      AND run.details = '{}'::JSONB
      AND run.started_at = :'started_at'::TIMESTAMPTZ
      AND run.finished_at = :'finished_at'::TIMESTAMPTZ
) THEN 1 ELSE 0 END AS exact_idempotent_backup_record;

COMMIT;
