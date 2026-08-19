-- 系统书签集：管理后台「AI 批量导出为集合」发布流程的存储落点。
-- 必须在部署新 API 之前应用——`GET/POST /admin/bookmark/collections/system/**` 直接读写这两张表，
-- 先部署代码会让整个「系统书签集」页面 500。两张表此前完全不存在，控制器一直是占位实现。
--
-- 无 site_/page_/user_ 前缀：不随用户或域名切换，是系统级实体，与 system_config 同类。

CREATE TABLE IF NOT EXISTS public.system_collection (
    id character varying(64) NOT NULL,
    title character varying(80) NOT NULL,
    description character varying(500),
    status character varying(20) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    created_by character varying(40) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT system_collection_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.system_collection_page (
    id character varying(64) NOT NULL,
    collection_id character varying(64) NOT NULL,
    page_id character varying(64) NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    CONSTRAINT system_collection_page_pkey PRIMARY KEY (id)
);

-- 覆盖式发布/编辑走「先删该 collection 下全部行，再按新顺序插入」，
-- 唯一约束防的是同一批发布请求被并发/重复提交两次时同一页面被插成两行。
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_collection_page ON public.system_collection_page USING btree (collection_id, page_id);

CREATE INDEX IF NOT EXISTS idx_system_collection_page_collection_id ON public.system_collection_page USING btree (collection_id);
