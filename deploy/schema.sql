-- bookmarkify 数据库结构基线（schema of record）
--
-- **这个文件不是迁移，是"库现在长什么样"的唯一权威快照。**
--
-- 存在的理由：本项目的迁移是手工应用的（无 Flyway），且 deploy/migrations/ 下的历史
-- 迁移脚本会被定期清理 —— 那些是一次性的变更记录，清掉是对的。但"数据库当前的结构"
-- 不该跟着一起消失：没有它，新环境起不来、本地无法复现、索引与约束无人 review、
-- 出问题时也没有回滚基线。
--
-- 维护方式：**结构变更后重新导出这一份**，不要往里追加 ALTER，也不要手工改名。
-- 它应该始终可以在一个空库上直接执行出一套完整的表结构。
--
--   pg_dump --schema-only --no-owner --no-privileges --no-comments --schema=public
--
-- 生成时间：2026-08-03，由 pg_dump 18.4 从生产库（PostgreSQL 17.4）直接导出，
-- 时点是三层正名（site / page / bookmark）迁移执行完成之后。
--
-- 两次教训，都写在这里免得重犯：
-- ① 曾经在没装 pg_dump 的机器上按 pg_catalog 手工重建过一份，结果漏了 4 条索引、
--    多写 1 条不存在的、表数量还标错。**手工重建的快照一定会漂移。**
-- ② 曾经在旧基线上"文本套用"改名来代替重新导出，于是漏掉了这中间另一个迁移
--    删掉的 page.nsfw / nsfw_reason 两列 —— 文本变换只能反映它知道的那次变更。
--
-- 与 pg_dump 原始输出的差异：去掉了 \restrict/\unrestrict（psql 18 专有指令，
-- 旧版客户端执行会报错），CREATE SCHEMA public 加了 IF NOT EXISTS。不含数据、权限、注释。
--


-- Dumped from database version 17.4 (Debian 17.4-1.pgdg120+2)
-- Dumped by pg_dump version 18.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: access_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.access_token (
    id character varying(64) NOT NULL,
    uid character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    token_hash character varying(64) NOT NULL,
    token_prefix character varying(32) NOT NULL,
    last_used_at timestamp without time zone,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: admin_grid_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_grid_config (
    id character varying(64) NOT NULL,
    admin_id character varying(64) NOT NULL,
    grid_id character varying(128) NOT NULL,
    config_json json,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: ai_call_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_call_log (
    id character varying(40) NOT NULL,
    provider character varying(20) DEFAULT 'DEEPSEEK'::character varying NOT NULL,
    scene character varying(32) NOT NULL,
    model character varying(64),
    subject character varying(200),
    success boolean NOT NULL,
    http_status integer,
    request_body text,
    response_body text,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    duration_ms bigint DEFAULT 0 NOT NULL,
    error_msg character varying(500),
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: background_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.background_config (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    type character varying(20) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    background_image_id character varying(40),
    background_gradient_id character varying(40),
    CONSTRAINT chk_background_config_one_link CHECK (((
CASE
    WHEN (background_image_id IS NOT NULL) THEN 1
    ELSE 0
END +
CASE
    WHEN (background_gradient_id IS NOT NULL) THEN 1
    ELSE 0
END) = 1))
);


--
-- Name: background_gradient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.background_gradient (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    name character varying(200),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    gradient text NOT NULL,
    direction integer DEFAULT 0 NOT NULL
);


--
-- Name: background_image; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.background_image (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    file_id character varying(40) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    is_default boolean DEFAULT false NOT NULL
);


--
-- Name: bookmark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bookmark (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    page_id character varying(40),
    layout_node_id character varying(40) NOT NULL,
    title character varying(200),
    description character varying(1000),
    url_full character varying(1000) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    pinned boolean DEFAULT false NOT NULL,
    link_type character varying(20) DEFAULT 'OTHER'::character varying NOT NULL,
    open_count integer DEFAULT 0 NOT NULL,
    dispatch_attempts integer DEFAULT 0 NOT NULL
);


--
-- Name: category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.category (
    id character varying(64) NOT NULL,
    slug character varying(64) NOT NULL,
    name character varying(64) NOT NULL,
    description character varying(500),
    color character varying(16),
    sort integer DEFAULT 0 NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    last_modified timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: config_change_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.config_change_log (
    id character varying(64) NOT NULL,
    config_key character varying(128) NOT NULL,
    old_value text,
    new_value text NOT NULL,
    operator_id character varying(64),
    operator_name character varying(200),
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: layout_node_function; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.layout_node_function (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    layout_node_id character varying(40) NOT NULL,
    type character varying(30) NOT NULL,
    create_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: oss_object; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oss_object (
    id character varying(40) NOT NULL,
    object_key character varying(512) NOT NULL,
    content_hash character varying(80),
    addressing character varying(16) DEFAULT 'LEGACY'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    size bigint,
    mime character varying(128),
    width integer,
    height integer,
    is_vector boolean DEFAULT false NOT NULL,
    environment character varying(16) DEFAULT 'PROD'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp without time zone,
    last_ref_at timestamp without time zone,
    state character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: page; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page (
    id character varying(40) NOT NULL,
    url_host character varying(200) NOT NULL,
    url_scheme character varying(10) NOT NULL,
    app_name character varying(100),
    title character varying(200),
    description character varying(1000),
    parse_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    is_activity boolean DEFAULT false NOT NULL,
    verify_flag boolean DEFAULT false NOT NULL,
    parse_err_msg text,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone,
    url_path character varying(500) DEFAULT '/'::character varying NOT NULL,
    anti_crawler_blocked boolean DEFAULT false NOT NULL,
    last_parse_at timestamp without time zone,
    last_check_at timestamp without time zone,
    next_check_at timestamp without time zone,
    consecutive_fail smallint DEFAULT 0 NOT NULL,
    locked_fields character varying(200),
    site_id character varying(40) NOT NULL,
    url_query character varying(1000) DEFAULT ''::character varying NOT NULL,
    url_fragment character varying(500) DEFAULT ''::character varying NOT NULL
);


--
-- Name: page_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_category (
    id character varying(64) NOT NULL,
    page_id character varying(64) NOT NULL,
    category_id character varying(64) NOT NULL,
    source character varying(32) DEFAULT 'DEEPSEEK'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: page_meta; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_meta (
    page_id character varying(64) NOT NULL,
    title character varying(500),
    description character varying(2000),
    site_name character varying(200),
    site_short_name character varying(100),
    canonical_url character varying(1000),
    lang character varying(20),
    theme_color character varying(32),
    meta_sources jsonb,
    fetch_layer character varying(20),
    http_status integer,
    anti_crawler boolean DEFAULT false NOT NULL,
    fetched_at timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: page_ping_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_ping_log (
    id character varying(40) NOT NULL,
    page_id character varying(40) NOT NULL,
    url_host character varying(200) NOT NULL,
    alive boolean,
    triggered_parse boolean DEFAULT false NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    outcome character varying(16) NOT NULL,
    sweep_id character varying(64)
);


--
-- Name: scrape_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scrape_snapshot (
    id character varying(64) NOT NULL,
    page_id character varying(64) NOT NULL,
    url character varying(1000) NOT NULL,
    ok boolean NOT NULL,
    request jsonb,
    response jsonb,
    error_msg character varying(1000),
    duration_ms integer DEFAULT 0 NOT NULL,
    fetched_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: scrapper_call_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scrapper_call_log (
    id character varying(40) NOT NULL,
    url character varying(1000) NOT NULL,
    url_host character varying(200) NOT NULL,
    success boolean NOT NULL,
    http_status integer,
    source character varying(20),
    cached boolean,
    duration_ms bigint NOT NULL,
    error_msg character varying(500),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    layer_used character varying(20),
    error_code character varying(64),
    target_status integer
);


--
-- Name: site; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.site (
    id character varying(40) NOT NULL,
    host character varying(200) NOT NULL,
    scheme character varying(10) NOT NULL,
    link_type character varying(20) DEFAULT 'DOMAIN'::character varying NOT NULL,
    brand_name character varying(200),
    short_name character varying(100),
    nsfw boolean DEFAULT false NOT NULL,
    nsfw_reason character varying(500),
    is_alive boolean DEFAULT true NOT NULL,
    last_check_at timestamp without time zone,
    next_check_at timestamp without time zone,
    consecutive_fail smallint DEFAULT 0 NOT NULL,
    verify_flag boolean DEFAULT false NOT NULL,
    locked_fields character varying(200),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: site_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.site_asset (
    id character varying(64) NOT NULL,
    page_id character varying(64),
    role character varying(20) NOT NULL,
    extractor character varying(40) NOT NULL,
    quality character varying(20) DEFAULT 'DEGRADED'::character varying NOT NULL,
    origin_url character varying(1000) NOT NULL,
    resolved_url character varying(1000) NOT NULL,
    storage_url character varying(1000),
    width integer,
    height integer,
    byte_size bigint,
    mime character varying(100),
    is_vector boolean DEFAULT false NOT NULL,
    content_hash character varying(80),
    is_primary boolean DEFAULT false NOT NULL,
    error_msg character varying(500),
    fetched_at timestamp without time zone DEFAULT now() NOT NULL,
    owner_type character varying(10) NOT NULL,
    owner_id character varying(64) NOT NULL,
    file_id character varying(40)
);


--
-- Name: site_display_pref; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.site_display_pref (
    page_id character varying(64),
    display_mode character varying(20) NOT NULL,
    icon_padding integer DEFAULT 25 NOT NULL,
    icon_bg_color character varying(32),
    pinned_asset_id character varying(64),
    updated_by character varying(64),
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    id character varying(40) NOT NULL,
    site_id character varying(40) NOT NULL
);


--
-- Name: sweep_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sweep_log (
    id character varying(64) NOT NULL,
    task_label character varying(64) NOT NULL,
    candidates integer DEFAULT 0 NOT NULL,
    backlog bigint DEFAULT 0 NOT NULL,
    batch_size integer,
    probed integer DEFAULT 0 NOT NULL,
    short_circuited integer DEFAULT 0 NOT NULL,
    short_circuited_dead integer,
    alive_count integer DEFAULT 0 NOT NULL,
    dead_count integer DEFAULT 0 NOT NULL,
    unknown_count integer DEFAULT 0 NOT NULL,
    triggered_parse integer DEFAULT 0 NOT NULL,
    deferred_parse integer DEFAULT 0 NOT NULL,
    breaker_reason character varying(500),
    duration_ms bigint DEFAULT 0 NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: system_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_config (
    id character varying(64) NOT NULL,
    config_key character varying(128) NOT NULL,
    config_value text,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: user_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_info (
    id character varying(40) NOT NULL,
    nick_name character varying(200) NOT NULL,
    device_id character varying(200) NOT NULL,
    email character varying(200),
    password character varying(200),
    avatar_file_id character varying(40),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    disabled boolean DEFAULT false NOT NULL,
    verified boolean DEFAULT false NOT NULL,
    google_id character varying(100),
    google_email character varying(200),
    github_id character varying(100),
    github_login character varying(100)
);


--
-- Name: user_layout_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_layout_node (
    id character varying(40) NOT NULL,
    parent_id character varying(40),
    type character varying(30) DEFAULT 'BOOKMARK'::character varying NOT NULL,
    uid character varying(40) NOT NULL,
    name character varying(200),
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: user_preference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preference (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    background_config_id character varying(40),
    bookmark_open_mode character varying(30) DEFAULT 'NEW_TAB'::character varying NOT NULL,
    minimal_mode boolean DEFAULT false NOT NULL,
    bookmark_gap character varying(30) DEFAULT 'DEFAULT'::character varying NOT NULL,
    bookmark_image_size character varying(30) DEFAULT 'MEDIUM'::character varying NOT NULL,
    show_title boolean DEFAULT true NOT NULL,
    show_desktop_add_entry boolean DEFAULT true NOT NULL,
    page_mode character varying(30) DEFAULT 'VERTICAL_SCROLL'::character varying NOT NULL,
    node_sort_map_json json,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: user_share; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_share (
    id character varying(64) NOT NULL,
    uid character varying(40) NOT NULL,
    note character varying(500),
    expire_time timestamp without time zone,
    status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    reject_reason text
);


--
-- Name: user_share_bookmark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_share_bookmark (
    id character varying(64) NOT NULL,
    share_id character varying(64) NOT NULL,
    bookmark_id character varying(64) NOT NULL,
    sort integer DEFAULT 0 NOT NULL
);


--
-- Name: access_token access_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_token
    ADD CONSTRAINT access_token_pkey PRIMARY KEY (id);


--
-- Name: admin_grid_config admin_grid_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_grid_config
    ADD CONSTRAINT admin_grid_config_pkey PRIMARY KEY (id);


--
-- Name: ai_call_log ai_call_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_call_log
    ADD CONSTRAINT ai_call_log_pkey PRIMARY KEY (id);


--
-- Name: background_config background_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_config
    ADD CONSTRAINT background_config_pkey PRIMARY KEY (id);


--
-- Name: background_gradient background_gradient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_gradient
    ADD CONSTRAINT background_gradient_pkey PRIMARY KEY (id);


--
-- Name: background_image background_image_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_image
    ADD CONSTRAINT background_image_pkey PRIMARY KEY (id);


--
-- Name: bookmark bookmark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark
    ADD CONSTRAINT bookmark_pkey PRIMARY KEY (id);


--
-- Name: category category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.category
    ADD CONSTRAINT category_pkey PRIMARY KEY (id);


--
-- Name: config_change_log config_change_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_change_log
    ADD CONSTRAINT config_change_log_pkey PRIMARY KEY (id);


--
-- Name: layout_node_function layout_node_function_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.layout_node_function
    ADD CONSTRAINT layout_node_function_pkey PRIMARY KEY (id);


--
-- Name: oss_object oss_object_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oss_object
    ADD CONSTRAINT oss_object_pkey PRIMARY KEY (id);


--
-- Name: page_category page_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_category
    ADD CONSTRAINT page_category_pkey PRIMARY KEY (id);


--
-- Name: page_meta page_meta_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_meta
    ADD CONSTRAINT page_meta_pkey PRIMARY KEY (page_id);


--
-- Name: page_ping_log page_ping_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_ping_log
    ADD CONSTRAINT page_ping_log_pkey PRIMARY KEY (id);


--
-- Name: page page_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page
    ADD CONSTRAINT page_pkey PRIMARY KEY (id);


--
-- Name: scrape_snapshot scrape_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scrape_snapshot
    ADD CONSTRAINT scrape_snapshot_pkey PRIMARY KEY (id);


--
-- Name: scrapper_call_log scrapper_call_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scrapper_call_log
    ADD CONSTRAINT scrapper_call_log_pkey PRIMARY KEY (id);


--
-- Name: site_asset site_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.site_asset
    ADD CONSTRAINT site_asset_pkey PRIMARY KEY (id);


--
-- Name: site_display_pref site_display_pref_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.site_display_pref
    ADD CONSTRAINT site_display_pref_pkey PRIMARY KEY (id);


--
-- Name: site site_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.site
    ADD CONSTRAINT site_pkey PRIMARY KEY (id);


--
-- Name: sweep_log sweep_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sweep_log
    ADD CONSTRAINT sweep_log_pkey PRIMARY KEY (id);


--
-- Name: system_config system_config_config_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_config
    ADD CONSTRAINT system_config_config_key_key UNIQUE (config_key);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (id);


--
-- Name: category uk_category_slug; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.category
    ADD CONSTRAINT uk_category_slug UNIQUE (slug);


--
-- Name: page_category uk_page_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_category
    ADD CONSTRAINT uk_page_category UNIQUE (page_id, category_id);


--
-- Name: admin_grid_config uq_admin_grid_config_admin_grid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_grid_config
    ADD CONSTRAINT uq_admin_grid_config_admin_grid UNIQUE (admin_id, grid_id);


--
-- Name: user_info user_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_info
    ADD CONSTRAINT user_info_pkey PRIMARY KEY (id);


--
-- Name: user_layout_node user_layout_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_layout_node
    ADD CONSTRAINT user_layout_node_pkey PRIMARY KEY (id);


--
-- Name: user_preference user_preference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preference
    ADD CONSTRAINT user_preference_pkey PRIMARY KEY (id);


--
-- Name: user_share_bookmark user_share_bookmark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_share_bookmark
    ADD CONSTRAINT user_share_bookmark_pkey PRIMARY KEY (id);


--
-- Name: user_share user_share_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_share
    ADD CONSTRAINT user_share_pkey PRIMARY KEY (id);


--
-- Name: idx_access_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_access_token_hash ON public.access_token USING btree (token_hash);


--
-- Name: idx_access_token_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_access_token_uid ON public.access_token USING btree (uid);


--
-- Name: idx_ai_call_log_create_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_call_log_create_time ON public.ai_call_log USING btree (create_time DESC);


--
-- Name: idx_ai_call_log_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_call_log_scene ON public.ai_call_log USING btree (scene, create_time DESC);


--
-- Name: idx_background_gradient_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_background_gradient_uid ON public.background_gradient USING btree (uid);


--
-- Name: idx_background_image_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_background_image_file ON public.background_image USING btree (file_id);


--
-- Name: idx_background_image_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_background_image_uid ON public.background_image USING btree (uid);


--
-- Name: idx_bookmark_layout_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmark_layout_node ON public.bookmark USING btree (layout_node_id);


--
-- Name: idx_bookmark_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmark_page ON public.bookmark USING btree (page_id) WHERE (deleted = false);


--
-- Name: idx_bookmark_uid_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmark_uid_live ON public.bookmark USING btree (uid, page_id) WHERE (deleted = false);


--
-- Name: idx_config_change_log_key_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_config_change_log_key_time ON public.config_change_log USING btree (config_key, create_time DESC);


--
-- Name: idx_lnf_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lnf_uid ON public.layout_node_function USING btree (uid);


--
-- Name: idx_oss_object_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oss_object_hash ON public.oss_object USING btree (content_hash) WHERE (content_hash IS NOT NULL);


--
-- Name: idx_oss_object_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_oss_object_key ON public.oss_object USING btree (object_key);


--
-- Name: idx_oss_object_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oss_object_state ON public.oss_object USING btree (state, last_ref_at);


--
-- Name: idx_page_category_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_category_page ON public.page_category USING btree (page_id);


--
-- Name: idx_page_due_check; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_due_check ON public.page USING btree (parse_status, COALESCE(next_check_at, '1970-01-01 00:00:00'::timestamp without time zone));


--
-- Name: idx_page_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_pending ON public.page USING btree (COALESCE(update_time, create_time)) WHERE ((parse_status)::text = 'PENDING'::text);


--
-- Name: idx_page_site; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_site ON public.page USING btree (site_id);


--
-- Name: idx_ping_log_create_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ping_log_create_time ON public.page_ping_log USING btree (create_time);


--
-- Name: idx_ping_log_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ping_log_page ON public.page_ping_log USING btree (page_id, create_time DESC);


--
-- Name: idx_ping_log_sweep; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ping_log_sweep ON public.page_ping_log USING btree (sweep_id, create_time) WHERE (sweep_id IS NOT NULL);


--
-- Name: idx_scrape_snapshot_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scrape_snapshot_page ON public.scrape_snapshot USING btree (page_id, fetched_at DESC);


--
-- Name: idx_scrape_snapshot_response; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scrape_snapshot_response ON public.scrape_snapshot USING gin (response jsonb_path_ops);


--
-- Name: idx_scrapper_call_log_create_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scrapper_call_log_create_time ON public.scrapper_call_log USING btree (create_time);


--
-- Name: idx_scrapper_call_log_url_host; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scrapper_call_log_url_host ON public.scrapper_call_log USING btree (url_host);


--
-- Name: idx_site_asset_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_site_asset_file ON public.site_asset USING btree (file_id);


--
-- Name: idx_site_asset_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_site_asset_hash ON public.site_asset USING btree (owner_type, owner_id, content_hash);


--
-- Name: idx_site_asset_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_site_asset_role ON public.site_asset USING btree (owner_type, owner_id, role, is_primary);


--
-- Name: idx_site_asset_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_site_asset_unique ON public.site_asset USING btree (owner_type, owner_id, extractor, resolved_url);


--
-- Name: idx_site_next_check; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_site_next_check ON public.site USING btree (next_check_at);


--
-- Name: idx_sweep_log_breaker; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sweep_log_breaker ON public.sweep_log USING btree (create_time DESC) WHERE (breaker_reason IS NOT NULL);


--
-- Name: idx_sweep_log_create_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sweep_log_create_time ON public.sweep_log USING btree (create_time DESC);


--
-- Name: idx_uln_loading; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_uln_loading ON public.user_layout_node USING btree (created_at) WHERE ((type)::text = 'BOOKMARK_LOADING'::text);


--
-- Name: idx_uln_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_uln_parent ON public.user_layout_node USING btree (parent_id) WHERE (parent_id IS NOT NULL);


--
-- Name: idx_uln_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_uln_uid ON public.user_layout_node USING btree (uid);


--
-- Name: idx_user_info_avatar_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_info_avatar_file ON public.user_info USING btree (avatar_file_id) WHERE (avatar_file_id IS NOT NULL);


--
-- Name: idx_user_info_device; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_info_device ON public.user_info USING btree (device_id) WHERE ((device_id IS NOT NULL) AND (deleted = false));


--
-- Name: idx_user_share_bookmark_share_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_share_bookmark_share_id ON public.user_share_bookmark USING btree (share_id);


--
-- Name: idx_user_share_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_share_uid ON public.user_share USING btree (uid);


--
-- Name: uk_background_config_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_background_config_uid ON public.background_config USING btree (uid);


--
-- Name: uk_bookmark_uid_page; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_bookmark_uid_page ON public.bookmark USING btree (uid, page_id) WHERE ((deleted = false) AND (page_id IS NOT NULL) AND ((page_id)::text <> 'LOADING'::text));


--
-- Name: uk_page_canonical; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_page_canonical ON public.page USING btree (site_id, url_path, url_query, url_fragment);


--
-- Name: uk_site_display_pref_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_site_display_pref_owner ON public.site_display_pref USING btree (site_id, display_mode);


--
-- Name: uk_site_host; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_site_host ON public.site USING btree (host);


--
-- Name: uk_user_info_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_info_email ON public.user_info USING btree (email) WHERE ((email IS NOT NULL) AND (deleted = false));


--
-- Name: uk_user_info_github_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_info_github_id ON public.user_info USING btree (github_id) WHERE ((github_id IS NOT NULL) AND (deleted = false));


--
-- Name: uk_user_info_google_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_info_google_id ON public.user_info USING btree (google_id) WHERE ((google_id IS NOT NULL) AND (deleted = false));


--
-- Name: uk_user_preference_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_preference_uid ON public.user_preference USING btree (uid);


--
-- Name: background_config fk_background_config_gradient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_config
    ADD CONSTRAINT fk_background_config_gradient FOREIGN KEY (background_gradient_id) REFERENCES public.background_gradient(id);


--
-- Name: background_config fk_background_config_image; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_config
    ADD CONSTRAINT fk_background_config_image FOREIGN KEY (background_image_id) REFERENCES public.background_image(id);


--
-- PostgreSQL database dump complete
--


