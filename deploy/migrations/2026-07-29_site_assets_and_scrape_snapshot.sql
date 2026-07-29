-- 网站资产模型重构：把"图片"从扁平列改成一行一图的 site_asset，并把抓取快照整体留档。
--
-- 动机（旧模型的四个问题）：
--   1. ManifestIcon 用 sizes="og" 当类型标记 —— 把"类型"编码进了本该存尺寸的字段；
--   2. 社交图上传 OSS 后地址被丢弃（OssUtils 注释原文"仅上传 OSS，地址不落库"），
--      存了却取不回来；
--   3. bookmark_logo 一张表混了三种生命周期：抓取事实、文件元数据、人工偏好，
--      导致每次重抓都得小心翼翼避免冲掉管理员调好的内边距/背景色；
--   4. 图片只有"是什么"没有"哪来的"，无法判断某张 logo 其实只是 favicon 的降级。
--
-- 本次不 DROP bookmark_logo：它的读路径还散布在 web / admin 的对外 VO 里，
-- 切换读路径是独立的一步，切完再删。

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 抓取快照：scrapper 响应原样留档
-- ─────────────────────────────────────────────────────────────────────────────
-- 下面几张结构化表都是从这里投影出来的。留快照的意义在于：将来想启用某个当时没提列的
-- 字段（例如拿 themeColor 做卡片背景色），可以直接回填，不必重爬全站。
CREATE TABLE IF NOT EXISTS scrape_snapshot (
    id            varchar(64) PRIMARY KEY,
    bookmark_id   varchar(64) NOT NULL,
    url           varchar(1000) NOT NULL,
    ok            boolean NOT NULL,
    -- 实际生效的请求参数（scrapper 会回显），排障时不必猜"这次用了什么参数"
    request       jsonb NULL,
    -- scrapper 响应全文；失败时为 NULL，错误信息见 error_msg
    response      jsonb NULL,
    error_msg     varchar(1000) NULL,
    duration_ms   integer NOT NULL DEFAULT 0,
    fetched_at    timestamp NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scrape_snapshot_bookmark ON scrape_snapshot (bookmark_id, fetched_at DESC);
-- jsonb_path_ops 比默认 GIN 更小更快，代价是只支持 @> 包含查询 —— 这里够用
CREATE INDEX IF NOT EXISTS idx_scrape_snapshot_response ON scrape_snapshot USING gin (response jsonb_path_ops);

-- ─────────────────────────────────────────────────────────────────────────────
-- 页面文字元数据
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS site_page_meta (
    bookmark_id     varchar(64) PRIMARY KEY,
    title           varchar(500) NULL,
    description     varchar(2000) NULL,
    -- 站点名（og:site_name / manifest.name），区别于单页标题；列表模式用
    site_name       varchar(200) NULL,
    -- 站点短名（manifest.short_name），大图模式下方那行短文案的唯一标准来源。
    -- 此前 WebsiteParser 解析了 shortName 却只用 manifest.name 填 appName，短名被丢弃。
    site_short_name varchar(100) NULL,
    canonical_url   varchar(1000) NULL,
    lang            varchar(20) NULL,
    theme_color     varchar(32) NULL,
    -- 各字段的出处，形如 {"title":{"extractor":"OG","rawKey":"og:title"}, ...}。
    -- 刻意逐字段记录：title 来自 OG 而 description 回落到 meta[name] 是常态，
    -- 旧的单一 source 字段会把二者压扁成一个值。
    meta_sources    jsonb NULL,
    -- 实际走的抓取层：HTTP | HEADLESS
    fetch_layer     varchar(20) NULL,
    http_status     integer NULL,
    -- 抓到了但疑似反爬/WAF 挑战页，内容不可靠
    anti_crawler    boolean NOT NULL DEFAULT false,
    fetched_at      timestamp NOT NULL DEFAULT now(),
    update_time     timestamp NOT NULL DEFAULT now()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 图片资产：一行一图
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS site_asset (
    id            varchar(64) PRIMARY KEY,
    bookmark_id   varchar(64) NOT NULL,

    -- 用途，由 API 侧策略从 extractor 推导：FAVICON | LOGO | SOCIAL | SCREENSHOT
    role          varchar(20) NOT NULL,
    -- 出处，scrapper 报告的事实：LINK_ICON / APPLE_TOUCH_ICON / MANIFEST_ICON / ...
    extractor     varchar(40) NOT NULL,
    -- 质量分级，同样由 extractor 推导：TRUSTED（语义明确）| DEGRADED（借用其它用途的图）
    quality       varchar(20) NOT NULL DEFAULT 'DEGRADED',

    origin_url    varchar(1000) NOT NULL,
    resolved_url  varchar(1000) NOT NULL,
    -- 落对象存储后的永久地址；社交图的地址从此有地方放（旧实现上传后直接丢弃）
    storage_url   varchar(1000) NULL,

    width         integer NULL,
    height        integer NULL,
    byte_size     bigint NULL,
    mime          varchar(100) NULL,
    is_vector     boolean NOT NULL DEFAULT false,

    -- 图片字节的 sha256。用途：跨 extractor 去重，以及判定"该站的 apple-touch-icon
    -- 和 favicon 其实是同一张图" —— 也就意味着它没有独立 LOGO，大图场景应走首字母
    -- 色块而不是把 32px 的 favicon 拉伸到 72px。
    content_hash  varchar(80) NULL,

    -- 同 role 内的首选项，由分辨率/质量自动选出；人工指定见 site_display_pref
    is_primary    boolean NOT NULL DEFAULT false,
    error_msg     varchar(500) NULL,
    fetched_at    timestamp NOT NULL DEFAULT now()
);

-- 同一书签下，同一出处 + 同一地址只留一条
CREATE UNIQUE INDEX IF NOT EXISTS idx_site_asset_unique
    ON site_asset (bookmark_id, extractor, resolved_url);
CREATE INDEX IF NOT EXISTS idx_site_asset_role ON site_asset (bookmark_id, role, is_primary);
-- 支撑"这张图是不是被多个 extractor 共用"的统计与降级判定
CREATE INDEX IF NOT EXISTS idx_site_asset_hash ON site_asset (bookmark_id, content_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- 展示偏好：按（书签 × 展示模式）分行
-- ─────────────────────────────────────────────────────────────────────────────
-- 同一个书签有两种展示方式：TILE（大图 + 短名）与 LIST（小图 + 全名）。内边距与背景色
-- 在 72px 大图上是核心视觉参数，在 16px 小图上几乎不可见甚至有害，因此必须分模式存 ——
-- 管理员为大图调好的背景色不该连带影响列表行。
--
-- 这张表只由人工写入。重抓流程只写 site_page_meta 和 site_asset，永不触碰这里，
-- 这条边界正是旧 bookmark_logo 把"抓取事实"和"人工偏好"混在一张表所缺失的。
CREATE TABLE IF NOT EXISTS site_display_pref (
    bookmark_id     varchar(64) NOT NULL,
    display_mode    varchar(20) NOT NULL,          -- TILE | LIST
    icon_padding    integer NOT NULL DEFAULT 25,
    icon_bg_color   varchar(32) NULL,
    -- 人工钉死用哪张图，覆盖 site_asset.is_primary 的自动选择
    pinned_asset_id varchar(64) NULL,
    updated_by      varchar(64) NULL,
    update_time     timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (bookmark_id, display_mode)
);

COMMIT;
