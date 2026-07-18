\set ON_ERROR_STOP on

BEGIN;

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
    :'drill_id'::UUID,
    'RESTORE_DRILL',
    :'status',
    :'report_sha',
    NULLIF(:'error_category', 'NONE'),
    '{}'::JSONB,
    :'started_at'::TIMESTAMPTZ,
    :'finished_at'::TIMESTAMPTZ
)
ON CONFLICT (id) DO NOTHING;

SELECT 1 / CASE WHEN EXISTS (
    SELECT 1
    FROM portfolio.maintenance_run AS run
    WHERE run.id = :'drill_id'::UUID
      AND run.run_type = 'RESTORE_DRILL'
      AND run.status = :'status'
      AND run.artifact_checksum = :'report_sha'
      AND run.error_summary IS NOT DISTINCT FROM NULLIF(:'error_category', 'NONE')
      AND run.details = '{}'::JSONB
      AND run.started_at = :'started_at'::TIMESTAMPTZ
      AND run.finished_at = :'finished_at'::TIMESTAMPTZ
) THEN 1 ELSE 0 END AS exact_idempotent_restore_drill_record;

COMMIT;
