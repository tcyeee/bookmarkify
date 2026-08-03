-- bookmarkify 数据库结构基线（schema of record）
--
-- **这个文件不是迁移，是"库现在长什么样"的唯一权威快照。**
--
-- 存在的理由：本项目的迁移是手工应用的（无 Flyway），且 deploy/migrations/ 下的历史
-- 迁移脚本会被定期清理 —— 那些是一次性的变更记录，清掉是对的。但"数据库当前的结构"
-- 不该跟着一起消失：没有它，新环境起不来、本地无法复现、索引与约束无人 review、
-- 出问题时也没有回滚基线。
--
-- 维护方式：**结构变更后刷新这一份**，不要往里追加 ALTER。它应该始终可以在一个空库上
-- 直接执行出一套完整的表结构。
--
-- 生成时间：2026-08-02（本机没装 pg_dump，由 pg_catalog 重建）
-- 表数量：25
--
-- 已知与 pg_dump 输出的差异：不含序列/权限/注释/触发器（本库均未使用），
-- 不含数据，UNIQUE 约束统一以 CREATE UNIQUE INDEX 形式出现。

CREATE TABLE access_token (
    id                           varchar(64) NOT NULL,
    uid                          varchar(64) NOT NULL,
    name                         varchar(100) NOT NULL,
    token_hash                   varchar(64) NOT NULL,
    token_prefix                 varchar(32) NOT NULL,
    last_used_at                 timestamp,
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT access_token_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX idx_access_token_hash ON public.access_token USING btree (token_hash);
CREATE INDEX idx_access_token_uid ON public.access_token USING btree (uid);

CREATE TABLE admin_grid_config (
    id                           varchar(64) NOT NULL,
    admin_id                     varchar(64) NOT NULL,
    grid_id                      varchar(128) NOT NULL,
    config_json                  json,
    create_time                  timestamp DEFAULT now() NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT admin_grid_config_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX idx_admin_grid_config_owner ON public.admin_grid_config USING btree (admin_id, grid_id);

CREATE TABLE ai_call_log (
    id                           varchar(40) NOT NULL,
    provider                     varchar(20) DEFAULT 'DEEPSEEK'::character varying NOT NULL,
    scene                        varchar(32) NOT NULL,
    model                        varchar(64),
    subject                      varchar(200),
    success                      boolean NOT NULL,
    http_status                  integer,
    request_body                 text,
    response_body                text,
    prompt_tokens                integer,
    completion_tokens            integer,
    total_tokens                 integer,
    duration_ms                  bigint DEFAULT 0 NOT NULL,
    error_msg                    varchar(500),
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT ai_call_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_ai_call_log_create_time ON public.ai_call_log USING btree (create_time DESC);
CREATE INDEX idx_ai_call_log_scene ON public.ai_call_log USING btree (scene, create_time DESC);

CREATE TABLE background_config (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    type                         varchar(20) NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    background_image_id          varchar(40),
    background_gradient_id       varchar(40),
    CONSTRAINT chk_background_config_one_link CHECK (((
CASE
    WHEN (background_image_id IS NOT NULL) THEN 1
    ELSE 0
END +
CASE
    WHEN (background_gradient_id IS NOT NULL) THEN 1
    ELSE 0
END) = 1)),
    CONSTRAINT fk_background_config_gradient FOREIGN KEY (background_gradient_id) REFERENCES background_gradient(id),
    CONSTRAINT fk_background_config_image FOREIGN KEY (background_image_id) REFERENCES background_image(id),
    CONSTRAINT background_config_pkey PRIMARY KEY (id)
);

CREATE TABLE background_gradient (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    name                         varchar(200),
    create_time                  timestamp DEFAULT now() NOT NULL,
    is_default                   boolean DEFAULT false NOT NULL,
    gradient                     text NOT NULL,
    direction                    integer DEFAULT 0 NOT NULL,
    CONSTRAINT background_gradient_pkey PRIMARY KEY (id)
);

CREATE TABLE background_image (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    file_id                      varchar(40) NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    is_default                   boolean DEFAULT false NOT NULL,
    CONSTRAINT background_image_pkey PRIMARY KEY (id)
);

CREATE TABLE bookmark (
    id                           varchar(40) NOT NULL,
    url_host                     varchar(200) NOT NULL,
    url_scheme                   varchar(10) NOT NULL,
    app_name                     varchar(100),
    title                        varchar(200),
    description                  varchar(1000),
    parse_status                 varchar(20) DEFAULT 'LOADING'::character varying NOT NULL,
    is_activity                  boolean DEFAULT false NOT NULL,
    verify_flag                  boolean DEFAULT false NOT NULL,
    parse_err_msg                text,
    create_time                  timestamp DEFAULT now() NOT NULL,
    update_time                  timestamp,
    url_path                     varchar(500) DEFAULT '/'::character varying NOT NULL,
    nsfw                         boolean DEFAULT false NOT NULL,
    anti_crawler_blocked         boolean DEFAULT false NOT NULL,
    nsfw_reason                  varchar(50),
    last_parse_at                timestamp,
    last_check_at                timestamp,
    next_check_at                timestamp,
    consecutive_fail             smallint DEFAULT 0 NOT NULL,
    locked_fields                varchar(200),
    site_id                      varchar(40),
    url_query                    varchar(1000) DEFAULT ''::character varying NOT NULL,
    url_fragment                 varchar(500) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT bookmark_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_bookmark_due_check ON public.bookmark USING btree (parse_status, COALESCE(next_check_at, '1970-01-01 00:00:00'::timestamp without time zone));
CREATE INDEX idx_bookmark_pending ON public.bookmark USING btree (COALESCE(update_time, create_time)) WHERE ((parse_status)::text = 'PENDING'::text);
CREATE INDEX idx_bookmark_site ON public.bookmark USING btree (site_id);
CREATE UNIQUE INDEX uk_bookmark_canonical ON public.bookmark USING btree (site_id, url_path, url_query, url_fragment);

CREATE TABLE bookmark_category (
    id                           varchar(64) NOT NULL,
    bookmark_id                  varchar(64) NOT NULL,
    category_id                  varchar(64) NOT NULL,
    source                       varchar(32) DEFAULT 'DEEPSEEK'::character varying NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    deleted                      boolean DEFAULT false NOT NULL,
    CONSTRAINT bookmark_category_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_bookmark_category_bookmark ON public.bookmark_category USING btree (bookmark_id);

CREATE TABLE bookmark_ping_log (
    id                           varchar(40) NOT NULL,
    bookmark_id                  varchar(40) NOT NULL,
    url_host                     varchar(200) NOT NULL,
    alive                        boolean,
    triggered_parse              boolean DEFAULT false NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    outcome                      varchar(16) NOT NULL,
    CONSTRAINT bookmark_ping_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_ping_log_bookmark ON public.bookmark_ping_log USING btree (bookmark_id, create_time DESC);
CREATE INDEX idx_ping_log_bookmark_id ON public.bookmark_ping_log USING btree (bookmark_id);
CREATE INDEX idx_ping_log_create_time ON public.bookmark_ping_log USING btree (create_time);

CREATE TABLE bookmark_sweep_log (
    id                           varchar(64) NOT NULL,
    task_label                   varchar(64) NOT NULL,
    candidates                   integer DEFAULT 0 NOT NULL,
    backlog                      bigint DEFAULT 0 NOT NULL,
    probed                       integer DEFAULT 0 NOT NULL,
    short_circuited              integer DEFAULT 0 NOT NULL,
    alive_count                  integer DEFAULT 0 NOT NULL,
    dead_count                   integer DEFAULT 0 NOT NULL,
    unknown_count                integer DEFAULT 0 NOT NULL,
    triggered_parse              integer DEFAULT 0 NOT NULL,
    deferred_parse               integer DEFAULT 0 NOT NULL,
    breaker_reason               varchar(500),
    duration_ms                  bigint DEFAULT 0 NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT bookmark_sweep_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_sweep_log_create_time ON public.bookmark_sweep_log USING btree (create_time DESC);
CREATE INDEX idx_sweep_log_breaker ON public.bookmark_sweep_log USING btree (create_time DESC) WHERE (breaker_reason IS NOT NULL);

CREATE TABLE bookmark_user_link (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    bookmark_id                  varchar(40),
    layout_node_id               varchar(40) NOT NULL,
    title                        varchar(200),
    description                  varchar(1000),
    url_full                     varchar(1000),
    create_time                  timestamp DEFAULT now() NOT NULL,
    deleted                      boolean DEFAULT false NOT NULL,
    pinned                       boolean DEFAULT false NOT NULL,
    link_type                    varchar(20) DEFAULT 'OTHER'::character varying NOT NULL,
    open_count                   integer DEFAULT 0 NOT NULL,
    dispatch_attempts            integer DEFAULT 0 NOT NULL,
    CONSTRAINT bookmark_user_link_pkey PRIMARY KEY (id)
);

-- 同一用户不能重复收藏同一个 canonical 页面。这是判重的**权威约束**：
-- 应用层的 assertNotAlreadyLinked 是 check-then-act，两个并发请求可以同时通过，
-- 真正兜底的是这条索引（addOne / linkOne 捕获 DuplicateKeyException 翻成 E126）。
-- 排除软删（删掉之后要能重新添加）与导入占位（'LOADING' 是常量，一个用户可以有很多条）。
CREATE UNIQUE INDEX uk_bul_uid_bookmark ON public.bookmark_user_link USING btree (uid, bookmark_id)
    WHERE deleted = false AND bookmark_id IS NOT NULL AND bookmark_id <> 'LOADING'::text;
CREATE INDEX idx_bul_uid_live ON public.bookmark_user_link USING btree (uid, bookmark_id) WHERE deleted = false;
CREATE INDEX idx_bul_layout_node ON public.bookmark_user_link USING btree (layout_node_id);
CREATE INDEX idx_bul_bookmark ON public.bookmark_user_link USING btree (bookmark_id) WHERE deleted = false;

CREATE TABLE category (
    id                           varchar(64) NOT NULL,
    slug                         varchar(64) NOT NULL,
    name                         varchar(64) NOT NULL,
    description                  varchar(500),
    color                        varchar(16),
    sort                         integer DEFAULT 0 NOT NULL,
    deleted                      boolean DEFAULT false NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    last_modified                timestamp DEFAULT now() NOT NULL,
    CONSTRAINT category_pkey PRIMARY KEY (id)
);

CREATE TABLE layout_node_function (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    layout_node_id               varchar(40) NOT NULL,
    type                         varchar(30) NOT NULL,
    create_at                    timestamp DEFAULT now() NOT NULL,
    CONSTRAINT layout_node_function_pkey PRIMARY KEY (id)
);

CREATE TABLE oss_object (
    id                           varchar(40) NOT NULL,
    object_key                   varchar(512) NOT NULL,
    content_hash                 varchar(80),
    addressing                   varchar(16) DEFAULT 'LEGACY'::character varying NOT NULL,
    source                       varchar(32) NOT NULL,
    size                         bigint,
    mime                         varchar(128),
    width                        integer,
    height                       integer,
    is_vector                    boolean DEFAULT false NOT NULL,
    environment                  varchar(16) DEFAULT 'PROD'::character varying NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    last_seen_at                 timestamp,
    last_ref_at                  timestamp,
    state                        varchar(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    CONSTRAINT oss_object_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_oss_object_hash ON public.oss_object USING btree (content_hash) WHERE (content_hash IS NOT NULL);
CREATE UNIQUE INDEX idx_oss_object_key ON public.oss_object USING btree (object_key);
CREATE INDEX idx_oss_object_state ON public.oss_object USING btree (state, last_ref_at);

CREATE TABLE scrape_snapshot (
    id                           varchar(64) NOT NULL,
    bookmark_id                  varchar(64) NOT NULL,
    url                          varchar(1000) NOT NULL,
    ok                           boolean NOT NULL,
    request                      jsonb,
    response                     jsonb,
    error_msg                    varchar(1000),
    duration_ms                  integer DEFAULT 0 NOT NULL,
    fetched_at                   timestamp DEFAULT now() NOT NULL,
    CONSTRAINT scrape_snapshot_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_scrape_snapshot_bookmark ON public.scrape_snapshot USING btree (bookmark_id, fetched_at DESC);
CREATE INDEX idx_scrape_snapshot_response ON public.scrape_snapshot USING gin (response jsonb_path_ops);

CREATE TABLE scrapper_call_log (
    id                           varchar(40) NOT NULL,
    url                          varchar(500) NOT NULL,
    url_host                     varchar(200) NOT NULL,
    success                      boolean NOT NULL,
    http_status                  integer,
    source                       varchar(20),
    cached                       boolean,
    duration_ms                  bigint NOT NULL,
    error_msg                    varchar(500),
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT scrapper_call_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_scrapper_call_log_create_time ON public.scrapper_call_log USING btree (create_time);
CREATE INDEX idx_scrapper_call_log_url_host ON public.scrapper_call_log USING btree (url_host);

CREATE TABLE site (
    id                           varchar(40) NOT NULL,
    host                         varchar(200) NOT NULL,
    scheme                       varchar(10) NOT NULL,
    link_type                    varchar(20) DEFAULT 'DOMAIN'::character varying NOT NULL,
    brand_name                   varchar(200),
    short_name                   varchar(100),
    nsfw                         boolean DEFAULT false NOT NULL,
    nsfw_reason                  varchar(50),
    is_alive                     boolean DEFAULT true NOT NULL,
    last_check_at                timestamp,
    next_check_at                timestamp,
    consecutive_fail             smallint DEFAULT 0 NOT NULL,
    verify_flag                  boolean DEFAULT false NOT NULL,
    locked_fields                varchar(200),
    create_time                  timestamp DEFAULT now() NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT site_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_site_next_check ON public.site USING btree (next_check_at);
CREATE UNIQUE INDEX uk_site_host ON public.site USING btree (host);

CREATE TABLE site_asset (
    id                           varchar(64) NOT NULL,
    bookmark_id                  varchar(64),
    role                         varchar(20) NOT NULL,
    extractor                    varchar(40) NOT NULL,
    quality                      varchar(20) DEFAULT 'DEGRADED'::character varying NOT NULL,
    origin_url                   varchar(1000) NOT NULL,
    resolved_url                 varchar(1000) NOT NULL,
    storage_url                  varchar(1000),
    width                        integer,
    height                       integer,
    byte_size                    bigint,
    mime                         varchar(100),
    is_vector                    boolean DEFAULT false NOT NULL,
    content_hash                 varchar(80),
    is_primary                   boolean DEFAULT false NOT NULL,
    error_msg                    varchar(500),
    fetched_at                   timestamp DEFAULT now() NOT NULL,
    owner_type                   varchar(10),
    owner_id                     varchar(64),
    file_id                      varchar(40),
    CONSTRAINT site_asset_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_site_asset_file ON public.site_asset USING btree (file_id);
CREATE INDEX idx_site_asset_hash ON public.site_asset USING btree (owner_type, owner_id, content_hash);
CREATE INDEX idx_site_asset_role ON public.site_asset USING btree (owner_type, owner_id, role, is_primary);
CREATE UNIQUE INDEX idx_site_asset_unique ON public.site_asset USING btree (owner_type, owner_id, extractor, resolved_url);

CREATE TABLE site_display_pref (
    bookmark_id                  varchar(64),
    display_mode                 varchar(20) NOT NULL,
    icon_padding                 integer DEFAULT 25 NOT NULL,
    icon_bg_color                varchar(32),
    pinned_asset_id              varchar(64),
    updated_by                   varchar(64),
    update_time                  timestamp DEFAULT now() NOT NULL,
    id                           varchar(40) NOT NULL,
    site_id                      varchar(40),
    CONSTRAINT site_display_pref_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_site_display_pref_owner ON public.site_display_pref USING btree (site_id, display_mode);

CREATE TABLE site_page_meta (
    bookmark_id                  varchar(64) NOT NULL,
    title                        varchar(500),
    description                  varchar(2000),
    site_name                    varchar(200),
    site_short_name              varchar(100),
    canonical_url                varchar(1000),
    lang                         varchar(20),
    theme_color                  varchar(32),
    meta_sources                 jsonb,
    fetch_layer                  varchar(20),
    http_status                  integer,
    anti_crawler                 boolean DEFAULT false NOT NULL,
    fetched_at                   timestamp DEFAULT now() NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT site_page_meta_pkey PRIMARY KEY (bookmark_id)
);

CREATE TABLE system_config (
    id                           varchar(64) NOT NULL,
    config_key                   varchar(128) NOT NULL,
    config_value                 text,
    update_time                  timestamp DEFAULT now() NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT system_config_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX idx_system_config_key ON public.system_config USING btree (config_key);

CREATE TABLE user_info (
    id                           varchar(40) NOT NULL,
    nick_name                    varchar(200),
    device_id                    varchar(200),
    email                        varchar(200),
    password                     varchar(200),
    avatar_file_id               varchar(40),
    role                         varchar(20) DEFAULT 'USER'::character varying NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    deleted                      boolean DEFAULT false NOT NULL,
    disabled                     boolean DEFAULT false NOT NULL,
    verified                     boolean DEFAULT false NOT NULL,
    google_id                    varchar(100),
    google_email                 varchar(200),
    github_id                    varchar(100),
    github_login                 varchar(100),
    CONSTRAINT user_info_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_user_info_google_id ON public.user_info USING btree (google_id) WHERE (google_id IS NOT NULL);

CREATE TABLE user_layout_node (
    id                           varchar(40) NOT NULL,
    parent_id                    varchar(40),
    type                         varchar(30) DEFAULT 'BOOKMARK'::character varying NOT NULL,
    uid                          varchar(40) NOT NULL,
    name                         varchar(200),
    created_at                   timestamp DEFAULT now() NOT NULL,
    CONSTRAINT user_layout_node_pkey PRIMARY KEY (id)
);
-- 部分索引：drainStuckLoading 每 30 秒按这个条件扫一次，而 BOOKMARK_LOADING
-- 在稳态下只有正在抓取的那几条 + 导入积压，全表索引在这里是纯浪费
CREATE INDEX idx_uln_loading ON public.user_layout_node USING btree (created_at) WHERE type = 'BOOKMARK_LOADING'::text;
CREATE INDEX idx_uln_uid ON public.user_layout_node USING btree (uid);
CREATE INDEX idx_uln_parent ON public.user_layout_node USING btree (parent_id) WHERE parent_id IS NOT NULL;

CREATE TABLE user_preference (
    id                           varchar(40) NOT NULL,
    uid                          varchar(40) NOT NULL,
    background_config_id         varchar(40),
    bookmark_open_mode           varchar(30) DEFAULT 'NEW_TAB'::character varying NOT NULL,
    minimal_mode                 boolean DEFAULT false NOT NULL,
    bookmark_gap                 varchar(30) DEFAULT 'DEFAULT'::character varying NOT NULL,
    bookmark_image_size          varchar(30) DEFAULT 'MEDIUM'::character varying NOT NULL,
    show_title                   boolean DEFAULT true NOT NULL,
    show_desktop_add_entry       boolean DEFAULT true NOT NULL,
    page_mode                    varchar(30) DEFAULT 'VERTICAL_SCROLL'::character varying NOT NULL,
    node_sort_map_json           json,
    update_time                  timestamp DEFAULT now() NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT user_preference_pkey PRIMARY KEY (id)
);

CREATE TABLE user_share (
    id                           varchar(64) NOT NULL,
    uid                          varchar(40) NOT NULL,
    note                         varchar(500),
    expire_time                  timestamp,
    status                       varchar(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    update_time                  timestamp DEFAULT now() NOT NULL,
    reject_reason                text,
    CONSTRAINT user_share_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_user_share_uid ON public.user_share USING btree (uid);

CREATE TABLE user_share_bookmark (
    id                           varchar(64) NOT NULL,
    share_id                     varchar(64) NOT NULL,
    bookmark_user_link_id        varchar(64) NOT NULL,
    sort                         integer DEFAULT 0 NOT NULL,
    CONSTRAINT user_share_bookmark_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_user_share_bookmark_share_id ON public.user_share_bookmark USING btree (share_id);

