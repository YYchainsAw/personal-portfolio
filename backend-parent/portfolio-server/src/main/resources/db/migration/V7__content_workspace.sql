CREATE TABLE portfolio.site_profile (
    id UUID NOT NULL,
    monogram VARCHAR(16) NOT NULL DEFAULT '',
    email VARCHAR(320) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT site_profile_pk PRIMARY KEY (id)
);

INSERT INTO portfolio.site_profile (id)
VALUES ('00000000-0000-0000-0000-000000000001');

CREATE TABLE portfolio.site_profile_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    display_name TEXT NOT NULL,
    secondary_name TEXT NOT NULL,
    CONSTRAINT site_profile_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT site_profile_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT site_profile_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.site_seo_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    CONSTRAINT site_seo_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT site_seo_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT site_seo_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.site_accessibility_copy_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    skip_text TEXT NOT NULL,
    primary_nav TEXT NOT NULL,
    mobile_nav TEXT NOT NULL,
    open_menu TEXT NOT NULL,
    close_menu TEXT NOT NULL,
    language_text TEXT NOT NULL,
    back_to_top TEXT NOT NULL,
    project_tags TEXT NOT NULL,
    CONSTRAINT site_accessibility_copy_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT site_accessibility_copy_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT site_accessibility_copy_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.site_navigation_item (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    target VARCHAR(32) NOT NULL,
    sort_order INTEGER NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT site_navigation_item_pk PRIMARY KEY (id),
    CONSTRAINT site_navigation_item_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT site_navigation_item_target_ck
        CHECK (target IN ('about', 'work', 'roadmap', 'contact')),
    CONSTRAINT site_navigation_item_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT site_navigation_item_site_target_uk UNIQUE (site_id, target),
    CONSTRAINT site_navigation_item_site_sort_order_uk UNIQUE (site_id, sort_order)
);

CREATE TABLE portfolio.site_navigation_item_translation (
    navigation_item_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT site_navigation_item_translation_pk
        PRIMARY KEY (navigation_item_id, locale),
    CONSTRAINT site_navigation_item_translation_navigation_fk
        FOREIGN KEY (navigation_item_id)
        REFERENCES portfolio.site_navigation_item(id) ON DELETE CASCADE,
    CONSTRAINT site_navigation_item_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.hero_section (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT hero_section_pk PRIMARY KEY (id),
    CONSTRAINT hero_section_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT hero_section_site_uk UNIQUE (site_id)
);

CREATE TABLE portfolio.hero_section_translation (
    hero_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    eyebrow TEXT NOT NULL,
    display_name TEXT NOT NULL,
    secondary_name TEXT NOT NULL,
    role_text TEXT NOT NULL,
    headline TEXT NOT NULL,
    introduction TEXT NOT NULL,
    availability TEXT NOT NULL,
    primary_cta TEXT NOT NULL,
    secondary_cta TEXT NOT NULL,
    visual_label TEXT NOT NULL,
    stage_label TEXT NOT NULL,
    CONSTRAINT hero_section_translation_pk PRIMARY KEY (hero_id, locale),
    CONSTRAINT hero_section_translation_hero_fk FOREIGN KEY (hero_id)
        REFERENCES portfolio.hero_section(id) ON DELETE CASCADE,
    CONSTRAINT hero_section_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.hero_media (
    hero_id UUID NOT NULL,
    media_asset_id UUID NOT NULL,
    object_position VARCHAR(64) NOT NULL,
    credit TEXT NOT NULL,
    source_url TEXT NOT NULL,
    CONSTRAINT hero_media_pk PRIMARY KEY (hero_id),
    CONSTRAINT hero_media_hero_fk FOREIGN KEY (hero_id)
        REFERENCES portfolio.hero_section(id) ON DELETE CASCADE,
    CONSTRAINT hero_media_asset_fk FOREIGN KEY (media_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT hero_media_source_url_ck CHECK (source_url ~ '^https://')
);

CREATE TABLE portfolio.about_section_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    title TEXT NOT NULL,
    statement TEXT NOT NULL,
    focus_label TEXT NOT NULL,
    focus_title TEXT NOT NULL,
    focus_intro TEXT NOT NULL,
    CONSTRAINT about_section_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT about_section_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT about_section_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.work_section_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    title TEXT NOT NULL,
    introduction TEXT NOT NULL,
    image_notice TEXT NOT NULL,
    open_slot_label TEXT NOT NULL,
    open_slot_title TEXT NOT NULL,
    open_slot_text TEXT NOT NULL,
    open_slot_meta TEXT NOT NULL,
    CONSTRAINT work_section_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT work_section_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT work_section_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.contact_section_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    title TEXT NOT NULL,
    introduction TEXT NOT NULL,
    email_label TEXT NOT NULL,
    work_cta TEXT NOT NULL,
    roadmap_cta TEXT NOT NULL,
    footer_note TEXT NOT NULL,
    CONSTRAINT contact_section_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT contact_section_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT contact_section_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.privacy_notice_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    body_markdown TEXT NOT NULL DEFAULT '',
    CONSTRAINT privacy_notice_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT privacy_notice_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT privacy_notice_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.social_link (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    platform VARCHAR(32) NOT NULL,
    url TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT social_link_pk PRIMARY KEY (id),
    CONSTRAINT social_link_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT social_link_url_ck CHECK (url ~ '^https://'),
    CONSTRAINT social_link_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT social_link_site_platform_uk UNIQUE (site_id, platform),
    CONSTRAINT social_link_site_sort_order_uk UNIQUE (site_id, sort_order)
);

CREATE TABLE portfolio.profile_fact (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    external_key VARCHAR(96) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT profile_fact_pk PRIMARY KEY (id),
    CONSTRAINT profile_fact_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT profile_fact_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT profile_fact_site_external_key_uk UNIQUE (site_id, external_key),
    CONSTRAINT profile_fact_site_sort_order_uk UNIQUE (site_id, sort_order)
);

CREATE TABLE portfolio.profile_fact_translation (
    fact_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    value_text TEXT NOT NULL,
    CONSTRAINT profile_fact_translation_pk PRIMARY KEY (fact_id, locale),
    CONSTRAINT profile_fact_translation_fact_fk FOREIGN KEY (fact_id)
        REFERENCES portfolio.profile_fact(id) ON DELETE CASCADE,
    CONSTRAINT profile_fact_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.profile_skill (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    external_key VARCHAR(96) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT profile_skill_pk PRIMARY KEY (id),
    CONSTRAINT profile_skill_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT profile_skill_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT profile_skill_site_external_key_uk UNIQUE (site_id, external_key),
    CONSTRAINT profile_skill_site_sort_order_uk UNIQUE (site_id, sort_order)
);

CREATE TABLE portfolio.profile_skill_translation (
    profile_skill_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    name TEXT NOT NULL,
    status_text TEXT NOT NULL,
    CONSTRAINT profile_skill_translation_pk PRIMARY KEY (profile_skill_id, locale),
    CONSTRAINT profile_skill_translation_profile_skill_fk FOREIGN KEY (profile_skill_id)
        REFERENCES portfolio.profile_skill(id) ON DELETE CASCADE,
    CONSTRAINT profile_skill_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.tag (
    id UUID NOT NULL,
    normalized_key VARCHAR(96) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT tag_pk PRIMARY KEY (id),
    CONSTRAINT tag_normalized_key_uk UNIQUE (normalized_key)
);

CREATE TABLE portfolio.tag_translation (
    tag_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT tag_translation_pk PRIMARY KEY (tag_id, locale),
    CONSTRAINT tag_translation_tag_fk FOREIGN KEY (tag_id)
        REFERENCES portfolio.tag(id) ON DELETE CASCADE,
    CONSTRAINT tag_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.skill (
    id UUID NOT NULL,
    normalized_key VARCHAR(96) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skill_pk PRIMARY KEY (id),
    CONSTRAINT skill_normalized_key_uk UNIQUE (normalized_key)
);

CREATE TABLE portfolio.skill_translation (
    skill_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT skill_translation_pk PRIMARY KEY (skill_id, locale),
    CONSTRAINT skill_translation_skill_fk FOREIGN KEY (skill_id)
        REFERENCES portfolio.skill(id) ON DELETE CASCADE,
    CONSTRAINT skill_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.project (
    id UUID NOT NULL,
    external_key VARCHAR(96) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    number_label VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    publication_dirty BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT project_pk PRIMARY KEY (id),
    CONSTRAINT project_external_key_uk UNIQUE (external_key),
    CONSTRAINT project_slug_uk UNIQUE (slug),
    CONSTRAINT project_slug_ck CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT project_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT project_sort_order_uk UNIQUE (sort_order)
);

CREATE TABLE portfolio.project_translation (
    project_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    status_label TEXT NOT NULL,
    eyebrow TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    seo_title TEXT NOT NULL DEFAULT '',
    seo_description TEXT NOT NULL DEFAULT '',
    CONSTRAINT project_translation_pk PRIMARY KEY (project_id, locale),
    CONSTRAINT project_translation_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT project_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.project_tag (
    project_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT project_tag_pk PRIMARY KEY (project_id, tag_id),
    CONSTRAINT project_tag_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT project_tag_tag_fk FOREIGN KEY (tag_id)
        REFERENCES portfolio.tag(id) ON DELETE RESTRICT,
    CONSTRAINT project_tag_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT project_tag_project_sort_order_uk UNIQUE (project_id, sort_order)
);

CREATE TABLE portfolio.project_skill (
    project_id UUID NOT NULL,
    skill_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT project_skill_pk PRIMARY KEY (project_id, skill_id),
    CONSTRAINT project_skill_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT project_skill_skill_fk FOREIGN KEY (skill_id)
        REFERENCES portfolio.skill(id) ON DELETE RESTRICT,
    CONSTRAINT project_skill_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT project_skill_project_sort_order_uk UNIQUE (project_id, sort_order)
);

CREATE TABLE portfolio.project_media (
    project_id UUID NOT NULL,
    media_asset_id UUID NOT NULL,
    usage VARCHAR(32) NOT NULL,
    sort_order INTEGER NOT NULL,
    layout VARCHAR(16) NOT NULL,
    object_position VARCHAR(64) NOT NULL,
    credit TEXT NOT NULL,
    source_url TEXT NOT NULL,
    CONSTRAINT project_media_pk PRIMARY KEY (project_id, media_asset_id, usage),
    CONSTRAINT project_media_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT project_media_asset_fk FOREIGN KEY (media_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT project_media_usage_ck CHECK (usage IN ('COVER', 'CARD', 'DETAIL')),
    CONSTRAINT project_media_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT project_media_layout_ck CHECK (layout IN ('wide', 'standard')),
    CONSTRAINT project_media_source_url_ck CHECK (source_url ~ '^https://'),
    CONSTRAINT project_media_project_usage_sort_order_uk
        UNIQUE (project_id, usage, sort_order)
);

CREATE TABLE portfolio.project_content_block (
    id UUID NOT NULL,
    project_id UUID NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    width VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    alignment VARCHAR(16) NOT NULL DEFAULT 'LEFT',
    emphasis VARCHAR(16) NOT NULL DEFAULT 'NONE',
    columns SMALLINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT project_content_block_pk PRIMARY KEY (id),
    CONSTRAINT project_content_block_project_fk FOREIGN KEY (project_id)
        REFERENCES portfolio.project(id) ON DELETE CASCADE,
    CONSTRAINT project_content_block_type_ck CHECK (block_type IN (
        'MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS',
        'DOWNLOAD', 'LINK'
    )),
    CONSTRAINT project_content_block_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT project_content_block_width_ck
        CHECK (width IN ('NARROW', 'STANDARD', 'WIDE', 'FULL')),
    CONSTRAINT project_content_block_alignment_ck
        CHECK (alignment IN ('LEFT', 'CENTER', 'RIGHT')),
    CONSTRAINT project_content_block_emphasis_ck
        CHECK (emphasis IN ('NONE', 'SOFT', 'STRONG')),
    CONSTRAINT project_content_block_columns_ck CHECK (columns BETWEEN 1 AND 4),
    CONSTRAINT project_content_block_project_sort_order_uk
        UNIQUE (project_id, sort_order)
);

CREATE TABLE portfolio.project_content_block_translation (
    block_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    title TEXT,
    description TEXT,
    CONSTRAINT project_content_block_translation_pk PRIMARY KEY (block_id, locale),
    CONSTRAINT project_content_block_translation_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT project_content_block_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.content_block_media (
    block_id UUID NOT NULL,
    media_asset_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT content_block_media_pk PRIMARY KEY (block_id, media_asset_id, role),
    CONSTRAINT content_block_media_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_media_asset_fk FOREIGN KEY (media_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT content_block_media_role_ck
        CHECK (role IN ('PRIMARY', 'GALLERY', 'COVER', 'FILE')),
    CONSTRAINT content_block_media_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT content_block_media_block_role_sort_order_uk
        UNIQUE (block_id, role, sort_order)
);

CREATE TABLE portfolio.content_block_markdown_translation (
    block_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    markdown TEXT NOT NULL,
    CONSTRAINT content_block_markdown_translation_pk PRIMARY KEY (block_id, locale),
    CONSTRAINT content_block_markdown_translation_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_markdown_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.content_block_video (
    block_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    url TEXT NOT NULL,
    cover_asset_id UUID,
    CONSTRAINT content_block_video_pk PRIMARY KEY (block_id),
    CONSTRAINT content_block_video_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_video_cover_asset_fk FOREIGN KEY (cover_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT content_block_video_provider_ck
        CHECK (provider IN ('BILIBILI', 'YOUTUBE', 'VIMEO')),
    CONSTRAINT content_block_video_url_ck CHECK (url ~ '^https://')
);

CREATE TABLE portfolio.content_block_code (
    block_id UUID NOT NULL,
    code_text TEXT NOT NULL,
    language VARCHAR(32) NOT NULL,
    show_line_numbers BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT content_block_code_pk PRIMARY KEY (block_id),
    CONSTRAINT content_block_code_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE
);

CREATE TABLE portfolio.content_block_quote_translation (
    block_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    quote_text TEXT NOT NULL,
    source_text TEXT NOT NULL,
    CONSTRAINT content_block_quote_translation_pk PRIMARY KEY (block_id, locale),
    CONSTRAINT content_block_quote_translation_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_quote_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.content_block_action (
    block_id UUID NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    media_asset_id UUID,
    url TEXT,
    open_new_tab BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT content_block_action_pk PRIMARY KEY (block_id),
    CONSTRAINT content_block_action_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_action_media_asset_fk FOREIGN KEY (media_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT content_block_action_type_ck CHECK (action_type IN ('DOWNLOAD', 'LINK')),
    CONSTRAINT content_block_action_target_type_ck
        CHECK (target_type IN ('MEDIA', 'EXTERNAL')),
    CONSTRAINT content_block_action_target_ck CHECK (
        (
            target_type = 'MEDIA'
            AND media_asset_id IS NOT NULL
            AND url IS NULL
        ) OR (
            target_type = 'EXTERNAL'
            AND media_asset_id IS NULL
            AND url IS NOT NULL
            AND url ~ '^https://'
        )
    )
);

CREATE TABLE portfolio.content_block_metric (
    id UUID NOT NULL,
    block_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    numeric_value NUMERIC,
    CONSTRAINT content_block_metric_pk PRIMARY KEY (id),
    CONSTRAINT content_block_metric_block_fk FOREIGN KEY (block_id)
        REFERENCES portfolio.project_content_block(id) ON DELETE CASCADE,
    CONSTRAINT content_block_metric_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT content_block_metric_block_sort_order_uk UNIQUE (block_id, sort_order)
);

CREATE TABLE portfolio.content_block_metric_translation (
    metric_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    value_text TEXT NOT NULL,
    suffix TEXT NOT NULL DEFAULT '',
    CONSTRAINT content_block_metric_translation_pk PRIMARY KEY (metric_id, locale),
    CONSTRAINT content_block_metric_translation_metric_fk FOREIGN KEY (metric_id)
        REFERENCES portfolio.content_block_metric(id) ON DELETE CASCADE,
    CONSTRAINT content_block_metric_translation_locale_ck
        CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.roadmap_header_translation (
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    label TEXT NOT NULL,
    title TEXT NOT NULL,
    introduction TEXT NOT NULL,
    CONSTRAINT roadmap_header_translation_pk PRIMARY KEY (site_id, locale),
    CONSTRAINT roadmap_header_translation_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT roadmap_header_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.roadmap_stage (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    external_key VARCHAR(96) NOT NULL,
    number_label VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT roadmap_stage_pk PRIMARY KEY (id),
    CONSTRAINT roadmap_stage_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT roadmap_stage_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT roadmap_stage_site_external_key_uk UNIQUE (site_id, external_key),
    CONSTRAINT roadmap_stage_site_sort_order_uk UNIQUE (site_id, sort_order)
);

CREATE TABLE portfolio.roadmap_stage_translation (
    stage_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    period TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    CONSTRAINT roadmap_stage_translation_pk PRIMARY KEY (stage_id, locale),
    CONSTRAINT roadmap_stage_translation_stage_fk FOREIGN KEY (stage_id)
        REFERENCES portfolio.roadmap_stage(id) ON DELETE CASCADE,
    CONSTRAINT roadmap_stage_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.roadmap_outcome (
    id UUID NOT NULL,
    stage_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT roadmap_outcome_pk PRIMARY KEY (id),
    CONSTRAINT roadmap_outcome_stage_fk FOREIGN KEY (stage_id)
        REFERENCES portfolio.roadmap_stage(id) ON DELETE CASCADE,
    CONSTRAINT roadmap_outcome_sort_order_ck CHECK (sort_order >= 0),
    CONSTRAINT roadmap_outcome_stage_sort_order_uk UNIQUE (stage_id, sort_order)
);

CREATE TABLE portfolio.roadmap_outcome_translation (
    outcome_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    outcome_text TEXT NOT NULL,
    CONSTRAINT roadmap_outcome_translation_pk PRIMARY KEY (outcome_id, locale),
    CONSTRAINT roadmap_outcome_translation_outcome_fk FOREIGN KEY (outcome_id)
        REFERENCES portfolio.roadmap_outcome(id) ON DELETE CASCADE,
    CONSTRAINT roadmap_outcome_translation_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE TABLE portfolio.resume_document (
    id UUID NOT NULL,
    site_id UUID NOT NULL,
    locale VARCHAR(5) NOT NULL,
    media_asset_id UUID NOT NULL,
    version_label VARCHAR(64) NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    document_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT resume_document_pk PRIMARY KEY (id),
    CONSTRAINT resume_document_site_fk FOREIGN KEY (site_id)
        REFERENCES portfolio.site_profile(id) ON DELETE CASCADE,
    CONSTRAINT resume_document_media_asset_fk FOREIGN KEY (media_asset_id)
        REFERENCES portfolio.media_asset(id) ON DELETE RESTRICT,
    CONSTRAINT resume_document_locale_ck CHECK (locale IN ('zh-CN', 'en'))
);

CREATE UNIQUE INDEX resume_document_current_locale_uk
    ON portfolio.resume_document (site_id, locale)
    WHERE is_current;

CREATE INDEX project_dirty_idx
    ON portfolio.project (publication_dirty)
    WHERE publication_dirty;
CREATE INDEX project_content_block_project_idx
    ON portfolio.project_content_block (project_id, sort_order);
CREATE INDEX roadmap_stage_site_idx
    ON portfolio.roadmap_stage (site_id, sort_order);

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.site_profile,
    portfolio.site_profile_translation,
    portfolio.site_seo_translation,
    portfolio.site_accessibility_copy_translation,
    portfolio.site_navigation_item,
    portfolio.site_navigation_item_translation,
    portfolio.hero_section,
    portfolio.hero_section_translation,
    portfolio.hero_media,
    portfolio.about_section_translation,
    portfolio.work_section_translation,
    portfolio.contact_section_translation,
    portfolio.privacy_notice_translation,
    portfolio.social_link,
    portfolio.profile_fact,
    portfolio.profile_fact_translation,
    portfolio.profile_skill,
    portfolio.profile_skill_translation,
    portfolio.tag,
    portfolio.tag_translation,
    portfolio.skill,
    portfolio.skill_translation,
    portfolio.project,
    portfolio.project_translation,
    portfolio.project_tag,
    portfolio.project_skill,
    portfolio.project_media,
    portfolio.project_content_block,
    portfolio.project_content_block_translation,
    portfolio.content_block_media,
    portfolio.content_block_markdown_translation,
    portfolio.content_block_video,
    portfolio.content_block_code,
    portfolio.content_block_quote_translation,
    portfolio.content_block_action,
    portfolio.content_block_metric,
    portfolio.content_block_metric_translation,
    portfolio.roadmap_header_translation,
    portfolio.roadmap_stage,
    portfolio.roadmap_stage_translation,
    portfolio.roadmap_outcome,
    portfolio.roadmap_outcome_translation,
    portfolio.resume_document
FROM PUBLIC;

REVOKE ALL PRIVILEGES ON TABLE
    portfolio.site_profile,
    portfolio.site_profile_translation,
    portfolio.site_seo_translation,
    portfolio.site_accessibility_copy_translation,
    portfolio.site_navigation_item,
    portfolio.site_navigation_item_translation,
    portfolio.hero_section,
    portfolio.hero_section_translation,
    portfolio.hero_media,
    portfolio.about_section_translation,
    portfolio.work_section_translation,
    portfolio.contact_section_translation,
    portfolio.privacy_notice_translation,
    portfolio.social_link,
    portfolio.profile_fact,
    portfolio.profile_fact_translation,
    portfolio.profile_skill,
    portfolio.profile_skill_translation,
    portfolio.tag,
    portfolio.tag_translation,
    portfolio.skill,
    portfolio.skill_translation,
    portfolio.project,
    portfolio.project_translation,
    portfolio.project_tag,
    portfolio.project_skill,
    portfolio.project_media,
    portfolio.project_content_block,
    portfolio.project_content_block_translation,
    portfolio.content_block_media,
    portfolio.content_block_markdown_translation,
    portfolio.content_block_video,
    portfolio.content_block_code,
    portfolio.content_block_quote_translation,
    portfolio.content_block_action,
    portfolio.content_block_metric,
    portfolio.content_block_metric_translation,
    portfolio.roadmap_header_translation,
    portfolio.roadmap_stage,
    portfolio.roadmap_stage_translation,
    portfolio.roadmap_outcome,
    portfolio.roadmap_outcome_translation,
    portfolio.resume_document
FROM portfolio_runtime_access;

GRANT SELECT ON TABLE portfolio.site_profile TO portfolio_runtime_access;
GRANT UPDATE (monogram, email, version, updated_at)
    ON TABLE portfolio.site_profile TO portfolio_runtime_access;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    portfolio.site_profile_translation,
    portfolio.site_seo_translation,
    portfolio.site_accessibility_copy_translation,
    portfolio.site_navigation_item,
    portfolio.site_navigation_item_translation,
    portfolio.hero_section,
    portfolio.hero_section_translation,
    portfolio.hero_media,
    portfolio.about_section_translation,
    portfolio.work_section_translation,
    portfolio.contact_section_translation,
    portfolio.privacy_notice_translation,
    portfolio.social_link,
    portfolio.profile_fact,
    portfolio.profile_fact_translation,
    portfolio.profile_skill,
    portfolio.profile_skill_translation,
    portfolio.tag,
    portfolio.tag_translation,
    portfolio.skill,
    portfolio.skill_translation,
    portfolio.project,
    portfolio.project_translation,
    portfolio.project_tag,
    portfolio.project_skill,
    portfolio.project_media,
    portfolio.project_content_block,
    portfolio.project_content_block_translation,
    portfolio.content_block_media,
    portfolio.content_block_markdown_translation,
    portfolio.content_block_video,
    portfolio.content_block_code,
    portfolio.content_block_quote_translation,
    portfolio.content_block_action,
    portfolio.content_block_metric,
    portfolio.content_block_metric_translation,
    portfolio.roadmap_header_translation,
    portfolio.roadmap_stage,
    portfolio.roadmap_stage_translation,
    portfolio.roadmap_outcome,
    portfolio.roadmap_outcome_translation,
    portfolio.resume_document
TO portfolio_runtime_access;
