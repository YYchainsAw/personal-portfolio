ALTER TABLE portfolio.local_staging_policy
    ADD COLUMN volume_id VARCHAR(64),
    ADD CONSTRAINT local_staging_policy_volume_id_ck CHECK (
        volume_id IS NULL OR volume_id ~ '^[0-9a-f]{64}$'
    );

CREATE FUNCTION portfolio.claim_local_staging_volume(
    requested_volume_id TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    claimed_count INTEGER;
BEGIN
    IF requested_volume_id IS NULL
       OR requested_volume_id !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION
            USING ERRCODE = '22023',
                  MESSAGE = 'local staging volume id is invalid';
    END IF;

    UPDATE portfolio.local_staging_policy AS policy
    SET volume_id = requested_volume_id
    WHERE policy.singleton_key = 1
      AND (
          policy.volume_id IS NULL
          OR policy.volume_id = requested_volume_id
      );
    GET DIAGNOSTICS claimed_count = ROW_COUNT;

    RETURN claimed_count = 1;
END;
$$;

REVOKE ALL ON FUNCTION portfolio.claim_local_staging_volume(TEXT)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION portfolio.claim_local_staging_volume(TEXT)
    FROM portfolio_runtime_access;
GRANT EXECUTE ON FUNCTION portfolio.claim_local_staging_volume(TEXT)
    TO portfolio_runtime_access;

REVOKE INSERT ON TABLE portfolio.local_staging_policy
    FROM portfolio_runtime_access;
GRANT SELECT ON TABLE portfolio.local_staging_policy
    TO portfolio_runtime_access;
GRANT INSERT (
    singleton_key,
    active_capacity,
    scan_entry_ceiling,
    worst_case_entries_per_reservation,
    reserved_headroom
) ON TABLE portfolio.local_staging_policy
    TO portfolio_runtime_access;
