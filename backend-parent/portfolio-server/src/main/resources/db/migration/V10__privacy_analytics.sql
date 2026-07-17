CREATE TABLE portfolio.analytics_event (
    id UUID NOT NULL,
    client_event_id UUID NOT NULL,
    site_date DATE NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    visitor_day_key CHAR(64) NOT NULL,
    session_day_key CHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    page_key VARCHAR(200) NOT NULL,
    project_id UUID,
    referrer_domain VARCHAR(253),
    device_class VARCHAR(16) NOT NULL,
    locale VARCHAR(10) NOT NULL,
    rules_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT analytics_event_pk PRIMARY KEY (id),
    CONSTRAINT analytics_event_client_event_id_uk UNIQUE (client_event_id),
    CONSTRAINT analytics_event_visitor_day_key_ck CHECK (
        visitor_day_key ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT analytics_event_session_day_key_ck CHECK (
        session_day_key ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT analytics_event_site_date_ck CHECK (
        site_date = (received_at AT TIME ZONE 'Asia/Hong_Kong')::DATE
    ),
    CONSTRAINT analytics_event_event_type_ck CHECK (
        event_type IN (
            'PAGE_VIEW',
            'PROJECT_VIEW',
            'RESUME_DOWNLOAD',
            'DEMO_DOWNLOAD',
            'OUTBOUND_CLICK'
        )
    ),
    CONSTRAINT analytics_event_page_key_ck CHECK (
        page_key IN (
            'HOME',
            'ABOUT',
            'WORK',
            'ROADMAP',
            'CONTACT',
            'PRIVACY',
            'PROJECT_DETAIL'
        )
    ),
    CONSTRAINT analytics_event_referrer_domain_ck CHECK (
        referrer_domain IS NULL
        OR referrer_domain IN ('(direct)', '(none)')
        OR (
            referrer_domain ~
                '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$'
            AND referrer_domain !~ '^[0-9]+(\.[0-9]+){3}$'
            AND referrer_domain !~ '[0-9a-f]{32}[.-]?[0-9a-f]{32}'
        )
    ),
    CONSTRAINT analytics_event_device_class_ck CHECK (
        device_class IN ('DESKTOP', 'MOBILE', 'TABLET', 'OTHER')
    ),
    CONSTRAINT analytics_event_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE INDEX analytics_event_date_idx
    ON portfolio.analytics_event (site_date, event_type);

CREATE INDEX analytics_event_dedupe_idx
    ON portfolio.analytics_event (
        session_day_key,
        event_type,
        page_key,
        project_id,
        received_at DESC
    );

CREATE INDEX analytics_event_retention_idx
    ON portfolio.analytics_event (received_at, id);

CREATE TABLE portfolio.analytics_daily (
    site_date DATE NOT NULL,
    metric VARCHAR(24) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    dimension VARCHAR(24) NOT NULL,
    dimension_value VARCHAR(253) NOT NULL,
    metric_count BIGINT NOT NULL,
    aggregation_version VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT analytics_daily_pk PRIMARY KEY (
        site_date,
        metric,
        event_type,
        dimension,
        dimension_value
    ),
    CONSTRAINT analytics_daily_metric_ck CHECK (
        metric IN ('PV', 'DAILY_UV', 'EVENT_COUNT')
    ),
    CONSTRAINT analytics_daily_event_type_ck CHECK (
        event_type IN (
            'PAGE_VIEW',
            'PROJECT_VIEW',
            'RESUME_DOWNLOAD',
            'DEMO_DOWNLOAD',
            'OUTBOUND_CLICK'
        )
    ),
    CONSTRAINT analytics_daily_metric_event_ck CHECK (
        metric = 'EVENT_COUNT'
        OR (
            metric IN ('PV', 'DAILY_UV')
            AND event_type = 'PAGE_VIEW'
        )
    ),
    CONSTRAINT analytics_daily_dimension_ck CHECK (
        dimension IN ('ALL', 'PAGE', 'PROJECT', 'REFERRER', 'DEVICE', 'LOCALE')
    ),
    CONSTRAINT analytics_daily_dimension_value_ck CHECK (
        CASE dimension
            WHEN 'ALL' THEN dimension_value = '(all)'
            WHEN 'PAGE' THEN dimension_value IN (
                'HOME',
                'ABOUT',
                'WORK',
                'ROADMAP',
                'CONTACT',
                'PRIVACY',
                'PROJECT_DETAIL'
            )
            WHEN 'PROJECT' THEN dimension_value ~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            WHEN 'REFERRER' THEN
                dimension_value IN ('(direct)', '(none)')
                OR (
                    dimension_value ~
                        '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$'
                    AND dimension_value !~ '^[0-9]+(\.[0-9]+){3}$'
                    AND dimension_value !~ '[0-9a-f]{64}'
                )
            WHEN 'DEVICE' THEN
                dimension_value IN ('DESKTOP', 'MOBILE', 'TABLET', 'OTHER')
            WHEN 'LOCALE' THEN dimension_value IN ('zh-CN', 'en')
            ELSE FALSE
        END
    ),
    CONSTRAINT analytics_daily_hmac_leak_ck CHECK (
        dimension_value !~ '[0-9a-f]{32}[.-]?[0-9a-f]{32}'
    ),
    CONSTRAINT analytics_daily_metric_count_ck CHECK (metric_count >= 0)
);

CREATE INDEX analytics_daily_report_idx
    ON portfolio.analytics_daily (
        site_date,
        dimension,
        metric,
        metric_count DESC
    );

CREATE TRIGGER analytics_daily_set_updated_at
BEFORE UPDATE ON portfolio.analytics_daily
FOR EACH ROW
EXECUTE FUNCTION portfolio.set_updated_at();

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.analytics_event,
    portfolio.analytics_daily
FROM PUBLIC;

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.analytics_event,
    portfolio.analytics_daily
FROM portfolio_runtime_access;

GRANT SELECT, INSERT, DELETE ON TABLE
    portfolio.analytics_event,
    portfolio.analytics_daily
TO portfolio_runtime_access;

GRANT UPDATE (metric_count, aggregation_version, updated_at)
    ON TABLE portfolio.analytics_daily
TO portfolio_runtime_access;
