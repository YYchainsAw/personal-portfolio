CREATE INDEX background_job_terminal_retention_idx
    ON portfolio.background_job (updated_at, created_at, id)
    WHERE status IN ('SUCCEEDED', 'DEAD');

CREATE FUNCTION portfolio.delete_expired_terminal_background_jobs(
    requested_batch_size INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF requested_batch_size IS NULL
       OR requested_batch_size < 1
       OR requested_batch_size > 1000 THEN
        RAISE EXCEPTION
            USING ERRCODE = '22023',
                  MESSAGE = 'background job retention batch size is invalid';
    END IF;

    WITH retention_boundary AS MATERIALIZED (
        SELECT pg_catalog.clock_timestamp() - interval '30 days' AS cutoff
    ), candidate AS MATERIALIZED (
        SELECT job.id
        FROM portfolio.background_job AS job
        CROSS JOIN retention_boundary
        WHERE job.status IN ('SUCCEEDED', 'DEAD')
          AND job.updated_at < retention_boundary.cutoff
          AND NOT EXISTS (
              SELECT 1
              FROM portfolio.local_staging_reservation AS reservation
              WHERE reservation.cleanup_job_id = job.id
          )
        ORDER BY job.updated_at, job.created_at, job.id
        FOR UPDATE OF job SKIP LOCKED
        LIMIT requested_batch_size
    ), deleted AS (
        DELETE FROM portfolio.background_job AS job
        USING candidate
        WHERE job.id = candidate.id
        RETURNING job.id
    )
    SELECT pg_catalog.count(*)::INTEGER
    INTO deleted_count
    FROM deleted;

    RETURN deleted_count;
END;
$$;

REVOKE ALL ON FUNCTION
    portfolio.delete_expired_terminal_background_jobs(INTEGER)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION
    portfolio.delete_expired_terminal_background_jobs(INTEGER)
    FROM portfolio_runtime_access;
GRANT EXECUTE ON FUNCTION
    portfolio.delete_expired_terminal_background_jobs(INTEGER)
    TO portfolio_runtime_access;
