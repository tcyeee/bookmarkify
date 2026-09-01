-- 「更多相似书签」：用户在某条书签上点开 /bookmark/similar 时展示的相似站点列表。
--
-- 两个来源合并显示：
--   1. 本地库 —— 与本页共享 category 的其它站点（page_category），零外部成本；
--   2. DeepSeek 推荐 —— inferSimilarSites 给出域名/名称/理由，再走**和加书签完全相同的
--      抓取链路**收录进库（图标落 OSS、page_meta、AI 归类、截图），幻觉域名抓不到正文即丢弃。
--
-- 本表只缓存第 2 类的结果，**站点级**（换个用户 / 换同域另一页都不变，故 site_ 前缀，属主是 site_id）。
-- 冷计算按 site 触发一次，结果写在这里，TTL 由 create_time 控制（SimilarBookmarkServiceImpl
-- 里的 CACHE_TTL_DAYS，默认 60 天）；到期后整组删除重算。
--
-- 必须在部署新 API **之前**应用：SimilarBookmarkServiceImpl 直接读写本表，且公开接口
-- POST /bookmark/similar 无 runCatching 兜底，先部署代码会让该接口 500。
--
-- 无 resolved_page_id 列：读取时按 domain 现查 site → 首页 page，省掉一列归属维护
-- （OrphanCleanupService 只需按 site_id 级联）。

CREATE TABLE IF NOT EXISTS public.site_similar_item (
    id character varying(40) NOT NULL,
    -- 属主：这些相似站是「谁的」。冷计算按 site 一次性写入。
    site_id character varying(40) NOT NULL,
    -- DeepSeek 返回的顺序（越小越靠前）
    rank integer DEFAULT 0 NOT NULL,
    name character varying(200) NOT NULL,
    -- 主域名，不带协议前缀。与 site.host 同口径（WebsiteParser 归一化）。
    domain character varying(200) NOT NULL,
    -- 一句话相似理由，直接展示给用户
    reason character varying(500),
    -- PENDING=还在后台抓；INGESTED=已抓到正文并入库(EXISTS 也归此类)；SKIPPED=抓不到正文(幻觉/失效域名)
    status character varying(20) DEFAULT 'PENDING' NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone,
    CONSTRAINT site_similar_item_pkey PRIMARY KEY (id)
);

-- 同一个 site 下同一个 domain 只保留一条；冷计算重算时先按 site_id 整组删再插。
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_similar_item ON public.site_similar_item USING btree (site_id, domain);
