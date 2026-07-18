CREATE TABLE portfolio.analytics_retention_checkpoint (
    site_date DATE NOT NULL,
    aggregation_version VARCHAR(32) NOT NULL,
    first_cutoff TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT analytics_retention_checkpoint_pk PRIMARY KEY (site_date),
    CONSTRAINT analytics_retention_checkpoint_version_ck CHECK (
        aggregation_version ~ '^[a-z0-9][a-z0-9-]{0,31}$'
    ),
    CONSTRAINT analytics_retention_checkpoint_date_ck CHECK (
        site_date <= (first_cutoff AT TIME ZONE 'Asia/Hong_Kong')::DATE
    )
);

CREATE FUNCTION portfolio.analytics_date_lock_key(requested_site_date DATE)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
STRICT
SET search_path = pg_catalog, portfolio
AS $$
DECLARE
    digest_bytes BYTEA;
    unsigned_value NUMERIC := 0;
    byte_index INTEGER;
BEGIN
    digest_bytes := portfolio.digest(
        pg_catalog.convert_to(
            'portfolio:analytics:date:'
                || pg_catalog.to_char(requested_site_date, 'YYYY-MM-DD'),
            'UTF8'
        ),
        'sha256'
    );
    FOR byte_index IN 0..7 LOOP
        unsigned_value := unsigned_value * 256
            + pg_catalog.get_byte(digest_bytes, byte_index);
    END LOOP;
    IF unsigned_value >= 9223372036854775808 THEN
        unsigned_value := unsigned_value - 18446744073709551616;
    END IF;
    RETURN unsigned_value::BIGINT;
END;
$$;

CREATE FUNCTION portfolio.analytics_date_has_exact_aggregate_coverage(
    requested_site_date DATE
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
STRICT
SET search_path = pg_catalog, portfolio
AS $$
    WITH day_events AS MATERIALIZED (
        SELECT event.event_type,
               event.page_key,
               event.project_id,
               coalesce(event.referrer_domain, '(none)') referrer_domain,
               event.device_class,
               event.locale,
               event.visitor_day_key
        FROM portfolio.analytics_event event
        WHERE event.site_date = requested_site_date
    ), event_types(event_type) AS (
        VALUES
            ('PAGE_VIEW'::VARCHAR),
            ('PROJECT_VIEW'::VARCHAR),
            ('RESUME_DOWNLOAD'::VARCHAR),
            ('DEMO_DOWNLOAD'::VARCHAR),
            ('OUTBOUND_CLICK'::VARCHAR)
    ), required(metric, event_type) AS (
        VALUES
            ('PV'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('DAILY_UV'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'PROJECT_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'RESUME_DOWNLOAD'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'DEMO_DOWNLOAD'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'OUTBOUND_CLICK'::VARCHAR)
    ), current_version AS (
        SELECT count(*)::INTEGER matched_rows,
               count(DISTINCT daily.aggregation_version)::INTEGER matched_versions,
               min(daily.aggregation_version) aggregation_version
        FROM portfolio.analytics_daily daily
        JOIN required required_metric
          ON required_metric.metric = daily.metric
         AND required_metric.event_type = daily.event_type
        WHERE daily.site_date = requested_site_date
          AND daily.dimension = 'ALL'
          AND daily.dimension_value = '(all)'
    ), all_rows(metric, event_type, dimension,
                dimension_value, metric_count) AS (
        SELECT 'PV'::VARCHAR,
               'PAGE_VIEW'::VARCHAR,
               'ALL'::VARCHAR,
               '(all)'::VARCHAR,
               count(*)::BIGINT
        FROM day_events
        WHERE event_type = 'PAGE_VIEW'
        UNION ALL
        SELECT 'DAILY_UV'::VARCHAR,
               'PAGE_VIEW'::VARCHAR,
               'ALL'::VARCHAR,
               '(all)'::VARCHAR,
               count(DISTINCT visitor_day_key)::BIGINT
        FROM day_events
        WHERE event_type = 'PAGE_VIEW'
        UNION ALL
        SELECT 'EVENT_COUNT'::VARCHAR,
               types.event_type,
               'ALL'::VARCHAR,
               '(all)'::VARCHAR,
               count(events.event_type)::BIGINT
        FROM event_types types
        LEFT JOIN day_events events
          ON events.event_type = types.event_type
        GROUP BY types.event_type
    ), expanded AS MATERIALIZED (
        SELECT event.event_type,
               event.visitor_day_key,
               value.dimension,
               value.dimension_value
        FROM day_events event
        CROSS JOIN LATERAL (
            VALUES
                ('PAGE'::VARCHAR, event.page_key::VARCHAR),
                ('REFERRER'::VARCHAR, event.referrer_domain::VARCHAR),
                ('DEVICE'::VARCHAR, event.device_class::VARCHAR),
                ('LOCALE'::VARCHAR, event.locale::VARCHAR)
        ) value(dimension, dimension_value)
        UNION ALL
        SELECT event_type,
               visitor_day_key,
               'PROJECT'::VARCHAR,
               project_id::VARCHAR
        FROM day_events
        WHERE project_id IS NOT NULL
    ), dimension_rows(metric, event_type, dimension,
                      dimension_value, metric_count) AS (
        SELECT 'EVENT_COUNT'::VARCHAR,
               event_type,
               dimension,
               dimension_value,
               count(*)::BIGINT
        FROM expanded
        GROUP BY event_type, dimension, dimension_value
        UNION ALL
        SELECT 'PV'::VARCHAR,
               'PAGE_VIEW'::VARCHAR,
               dimension,
               dimension_value,
               count(*)::BIGINT
        FROM expanded
        WHERE event_type = 'PAGE_VIEW'
        GROUP BY dimension, dimension_value
        UNION ALL
        SELECT 'DAILY_UV'::VARCHAR,
               'PAGE_VIEW'::VARCHAR,
               dimension,
               dimension_value,
               count(DISTINCT visitor_day_key)::BIGINT
        FROM expanded
        WHERE event_type = 'PAGE_VIEW'
        GROUP BY dimension, dimension_value
    ), expected AS (
        SELECT * FROM all_rows
        UNION ALL
        SELECT * FROM dimension_rows
    ), symmetric_difference AS (
        (
            SELECT expected.metric,
                   expected.event_type,
                   expected.dimension,
                   expected.dimension_value,
                   expected.metric_count,
                   current_version.aggregation_version
            FROM expected
            CROSS JOIN current_version
            EXCEPT
            SELECT daily.metric,
                   daily.event_type,
                   daily.dimension,
                   daily.dimension_value,
                   daily.metric_count,
                   daily.aggregation_version
            FROM portfolio.analytics_daily daily
            WHERE daily.site_date = requested_site_date
        )
        UNION ALL
        (
            SELECT daily.metric,
                   daily.event_type,
                   daily.dimension,
                   daily.dimension_value,
                   daily.metric_count,
                   daily.aggregation_version
            FROM portfolio.analytics_daily daily
            WHERE daily.site_date = requested_site_date
            EXCEPT
            SELECT expected.metric,
                   expected.event_type,
                   expected.dimension,
                   expected.dimension_value,
                   expected.metric_count,
                   current_version.aggregation_version
            FROM expected
            CROSS JOIN current_version
        )
    )
    SELECT current_version.matched_rows = 7
       AND current_version.matched_versions = 1
       AND NOT EXISTS (SELECT 1 FROM symmetric_difference)
    FROM current_version;
$$;

CREATE FUNCTION portfolio.prepare_analytics_retention_checkpoint(
    requested_site_date DATE,
    requested_cutoff TIMESTAMPTZ
)
RETURNS VARCHAR(32)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, portfolio, pg_temp
AS $$
DECLARE
    matched_rows INTEGER;
    matched_versions INTEGER;
    selected_version VARCHAR(32);
BEGIN
    IF requested_site_date IS NULL
       OR requested_cutoff IS NULL
       OR requested_cutoff > (
           pg_catalog.clock_timestamp() - INTERVAL '30 days'
       )
       OR requested_site_date > (
           requested_cutoff AT TIME ZONE 'Asia/Hong_Kong'
       )::DATE THEN
        RAISE EXCEPTION 'analytics retention checkpoint input is invalid'
            USING ERRCODE = '22023';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        portfolio.analytics_date_lock_key(requested_site_date)
    );

    WITH required(metric, event_type) AS (
        VALUES
            ('PV'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('DAILY_UV'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'PAGE_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'PROJECT_VIEW'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'RESUME_DOWNLOAD'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'DEMO_DOWNLOAD'::VARCHAR),
            ('EVENT_COUNT'::VARCHAR, 'OUTBOUND_CLICK'::VARCHAR)
    )
    SELECT count(*)::INTEGER,
           count(DISTINCT daily.aggregation_version)::INTEGER,
           min(daily.aggregation_version)
    INTO matched_rows, matched_versions, selected_version
    FROM portfolio.analytics_daily daily
    JOIN required required_metric
      ON required_metric.metric = daily.metric
     AND required_metric.event_type = daily.event_type
    WHERE daily.site_date = requested_site_date
      AND daily.dimension = 'ALL'
      AND daily.dimension_value = '(all)';

    IF matched_rows <> 7 OR matched_versions <> 1 THEN
        RAISE EXCEPTION 'analytics retention aggregate coverage is incomplete'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_retention_checkpoint_coverage_ck';
    END IF;

    IF EXISTS (
        WITH day_events AS MATERIALIZED (
            SELECT event.event_type,
                   event.page_key,
                   event.project_id,
                   coalesce(event.referrer_domain, '(none)') referrer_domain,
                   event.device_class,
                   event.locale,
                   event.visitor_day_key
            FROM portfolio.analytics_event event
            WHERE event.site_date = requested_site_date
        ), event_types(event_type) AS (
            VALUES
                ('PAGE_VIEW'::VARCHAR),
                ('PROJECT_VIEW'::VARCHAR),
                ('RESUME_DOWNLOAD'::VARCHAR),
                ('DEMO_DOWNLOAD'::VARCHAR),
                ('OUTBOUND_CLICK'::VARCHAR)
        ), all_rows(metric, event_type, dimension,
                    dimension_value, metric_count) AS (
            SELECT 'PV'::VARCHAR,
                   'PAGE_VIEW'::VARCHAR,
                   'ALL'::VARCHAR,
                   '(all)'::VARCHAR,
                   count(*)::BIGINT
            FROM day_events
            WHERE event_type = 'PAGE_VIEW'
            UNION ALL
            SELECT 'DAILY_UV'::VARCHAR,
                   'PAGE_VIEW'::VARCHAR,
                   'ALL'::VARCHAR,
                   '(all)'::VARCHAR,
                   count(DISTINCT visitor_day_key)::BIGINT
            FROM day_events
            WHERE event_type = 'PAGE_VIEW'
            UNION ALL
            SELECT 'EVENT_COUNT'::VARCHAR,
                   types.event_type,
                   'ALL'::VARCHAR,
                   '(all)'::VARCHAR,
                   count(events.event_type)::BIGINT
            FROM event_types types
            LEFT JOIN day_events events
              ON events.event_type = types.event_type
            GROUP BY types.event_type
        ), expanded AS MATERIALIZED (
            SELECT event.event_type,
                   event.visitor_day_key,
                   value.dimension,
                   value.dimension_value
            FROM day_events event
            CROSS JOIN LATERAL (
                VALUES
                    ('PAGE'::VARCHAR, event.page_key::VARCHAR),
                    ('REFERRER'::VARCHAR, event.referrer_domain::VARCHAR),
                    ('DEVICE'::VARCHAR, event.device_class::VARCHAR),
                    ('LOCALE'::VARCHAR, event.locale::VARCHAR)
            ) value(dimension, dimension_value)
            UNION ALL
            SELECT event_type,
                   visitor_day_key,
                   'PROJECT'::VARCHAR,
                   project_id::VARCHAR
            FROM day_events
            WHERE project_id IS NOT NULL
        ), dimension_rows(metric, event_type, dimension,
                          dimension_value, metric_count) AS (
            SELECT 'EVENT_COUNT'::VARCHAR,
                   event_type,
                   dimension,
                   dimension_value,
                   count(*)::BIGINT
            FROM expanded
            GROUP BY event_type, dimension, dimension_value
            UNION ALL
            SELECT 'PV'::VARCHAR,
                   'PAGE_VIEW'::VARCHAR,
                   dimension,
                   dimension_value,
                   count(*)::BIGINT
            FROM expanded
            WHERE event_type = 'PAGE_VIEW'
            GROUP BY dimension, dimension_value
            UNION ALL
            SELECT 'DAILY_UV'::VARCHAR,
                   'PAGE_VIEW'::VARCHAR,
                   dimension,
                   dimension_value,
                   count(DISTINCT visitor_day_key)::BIGINT
            FROM expanded
            WHERE event_type = 'PAGE_VIEW'
            GROUP BY dimension, dimension_value
        ), expected AS (
            SELECT * FROM all_rows
            UNION ALL
            SELECT * FROM dimension_rows
        ), symmetric_difference AS (
            (
                SELECT metric,
                       event_type,
                       dimension,
                       dimension_value,
                       metric_count,
                       selected_version aggregation_version
                FROM expected
                EXCEPT
                SELECT daily.metric,
                       daily.event_type,
                       daily.dimension,
                       daily.dimension_value,
                       daily.metric_count,
                       daily.aggregation_version
                FROM portfolio.analytics_daily daily
                WHERE daily.site_date = requested_site_date
            )
            UNION ALL
            (
                SELECT daily.metric,
                       daily.event_type,
                       daily.dimension,
                       daily.dimension_value,
                       daily.metric_count,
                       daily.aggregation_version
                FROM portfolio.analytics_daily daily
                WHERE daily.site_date = requested_site_date
                EXCEPT
                SELECT metric,
                       event_type,
                       dimension,
                       dimension_value,
                       metric_count,
                       selected_version aggregation_version
                FROM expected
            )
        )
        SELECT 1
        FROM symmetric_difference
    ) THEN
        RAISE EXCEPTION 'analytics retention aggregate coverage is incomplete'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_retention_checkpoint_coverage_ck';
    END IF;

    INSERT INTO portfolio.analytics_retention_checkpoint(
        site_date, aggregation_version, first_cutoff
    ) VALUES (
        requested_site_date, selected_version, requested_cutoff
    )
    ON CONFLICT (site_date) DO NOTHING;

    SELECT checkpoint.aggregation_version
    INTO selected_version
    FROM portfolio.analytics_retention_checkpoint checkpoint
    WHERE checkpoint.site_date = requested_site_date;
    RETURN selected_version;
END;
$$;

CREATE FUNCTION portfolio.purge_analytics_event_batch(
    requested_site_date DATE,
    requested_cutoff TIMESTAMPTZ,
    requested_batch_size INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, portfolio, pg_temp
AS $$
DECLARE
    deleted_rows INTEGER;
BEGIN
    IF requested_site_date IS NULL
       OR requested_cutoff IS NULL
       OR requested_batch_size IS NULL
       OR requested_batch_size < 1
       OR requested_batch_size > 5000
       OR requested_cutoff > (
           pg_catalog.clock_timestamp() - INTERVAL '30 days'
       ) THEN
        RAISE EXCEPTION 'analytics retention purge input is invalid'
            USING ERRCODE = '22023';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        portfolio.analytics_date_lock_key(requested_site_date)
    );

    IF NOT EXISTS (
        SELECT 1
        FROM portfolio.analytics_retention_checkpoint checkpoint
        WHERE checkpoint.site_date = requested_site_date
    ) THEN
        RAISE EXCEPTION 'analytics event deletion requires retention checkpoint'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_event_retention_checkpoint_ck';
    END IF;

    WITH candidates AS MATERIALIZED (
        SELECT event.id
        FROM portfolio.analytics_event event
        WHERE event.site_date = requested_site_date
          AND event.received_at < requested_cutoff
        ORDER BY event.received_at, event.id
        LIMIT requested_batch_size
    )
    DELETE FROM portfolio.analytics_event event
    USING candidates
    WHERE event.id = candidates.id;

    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    RETURN deleted_rows;
END;
$$;

CREATE FUNCTION portfolio.reject_analytics_event_after_retention_checkpoint()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, portfolio
AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock_shared(
        portfolio.analytics_date_lock_key(NEW.site_date)
    );
    IF EXISTS (
        SELECT 1
        FROM portfolio.analytics_retention_checkpoint checkpoint
        WHERE checkpoint.site_date = NEW.site_date
    ) THEN
        RAISE EXCEPTION 'analytics event date has entered retention'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_event_retention_checkpoint_ck';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION portfolio.reject_uncheckpointed_analytics_event_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, portfolio
AS $$
BEGIN
    IF NOT EXISTS (
           SELECT 1
           FROM portfolio.analytics_retention_checkpoint checkpoint
           WHERE checkpoint.site_date = OLD.site_date
       ) THEN
        RAISE EXCEPTION 'analytics event deletion requires retention checkpoint'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_event_retention_checkpoint_ck';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION portfolio.reject_analytics_daily_after_retention_checkpoint()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, portfolio
AS $$
DECLARE
    affected_site_date DATE;
BEGIN
    affected_site_date := CASE WHEN TG_OP = 'INSERT' THEN NEW.site_date ELSE OLD.site_date END;
    PERFORM pg_catalog.pg_advisory_xact_lock(
        portfolio.analytics_date_lock_key(affected_site_date)
    );
    IF EXISTS (
        SELECT 1
        FROM portfolio.analytics_retention_checkpoint checkpoint
        WHERE checkpoint.site_date = affected_site_date
    ) THEN
        RAISE EXCEPTION 'retained analytics aggregates are immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'analytics_daily_retention_checkpoint_ck';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER analytics_event_reject_retained_date
BEFORE INSERT ON portfolio.analytics_event
FOR EACH ROW
EXECUTE FUNCTION portfolio.reject_analytics_event_after_retention_checkpoint();

CREATE TRIGGER analytics_event_require_retention_checkpoint
BEFORE DELETE ON portfolio.analytics_event
FOR EACH ROW
EXECUTE FUNCTION portfolio.reject_uncheckpointed_analytics_event_delete();

CREATE TRIGGER analytics_daily_freeze_retained_date
BEFORE INSERT OR UPDATE OR DELETE ON portfolio.analytics_daily
FOR EACH ROW
EXECUTE FUNCTION portfolio.reject_analytics_daily_after_retention_checkpoint();

REVOKE ALL PRIVILEGES
    ON TABLE portfolio.analytics_retention_checkpoint
    FROM PUBLIC;
REVOKE ALL PRIVILEGES
    ON TABLE portfolio.analytics_retention_checkpoint
    FROM portfolio_runtime_access;
REVOKE DELETE
    ON TABLE portfolio.analytics_event
    FROM portfolio_runtime_access;
GRANT SELECT
    ON TABLE portfolio.analytics_retention_checkpoint
    TO portfolio_runtime_access;

REVOKE ALL
    ON FUNCTION portfolio.analytics_date_lock_key(DATE)
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.analytics_date_lock_key(DATE)
    FROM portfolio_runtime_access;
REVOKE ALL
    ON FUNCTION portfolio.analytics_date_has_exact_aggregate_coverage(DATE)
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.analytics_date_has_exact_aggregate_coverage(DATE)
    FROM portfolio_runtime_access;
GRANT EXECUTE
    ON FUNCTION portfolio.analytics_date_has_exact_aggregate_coverage(DATE)
    TO portfolio_runtime_access;
REVOKE ALL
    ON FUNCTION portfolio.prepare_analytics_retention_checkpoint(DATE, TIMESTAMPTZ)
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.prepare_analytics_retention_checkpoint(DATE, TIMESTAMPTZ)
    FROM portfolio_runtime_access;
GRANT EXECUTE
    ON FUNCTION portfolio.prepare_analytics_retention_checkpoint(DATE, TIMESTAMPTZ)
    TO portfolio_runtime_access;

REVOKE ALL
    ON FUNCTION portfolio.purge_analytics_event_batch(DATE, TIMESTAMPTZ, INTEGER)
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.purge_analytics_event_batch(DATE, TIMESTAMPTZ, INTEGER)
    FROM portfolio_runtime_access;
GRANT EXECUTE
    ON FUNCTION portfolio.purge_analytics_event_batch(DATE, TIMESTAMPTZ, INTEGER)
    TO portfolio_runtime_access;

REVOKE ALL
    ON FUNCTION portfolio.reject_analytics_event_after_retention_checkpoint()
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.reject_analytics_event_after_retention_checkpoint()
    FROM portfolio_runtime_access;
REVOKE ALL
    ON FUNCTION portfolio.reject_uncheckpointed_analytics_event_delete()
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.reject_uncheckpointed_analytics_event_delete()
    FROM portfolio_runtime_access;
REVOKE ALL
    ON FUNCTION portfolio.reject_analytics_daily_after_retention_checkpoint()
    FROM PUBLIC;
REVOKE ALL
    ON FUNCTION portfolio.reject_analytics_daily_after_retention_checkpoint()
    FROM portfolio_runtime_access;
