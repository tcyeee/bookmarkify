-- 引入站点(site)层：把「域名级事实」从 bookmark 里拆出去。详见根目录 SITE_LAYERING_DESIGN.md。
--
-- 背景：bookmark 一张表同时扮演「站点」和「页面」两个角色。同一个域名下有 1000 个页面，
-- 就有 1000 份 favicon 抓取+OSS 上传、1000 次 DeepSeek NSFW 判定、1000 次域名 ping，
-- 管理员调图标内边距也要调 1000 次 —— 而这些值换个页面根本不会变。
--
-- 本迁移只**加**东西，不改也不删任何现有列：执行完之后旧版 API 照常工作，
-- site 表暂时无人读写。site_id 刻意允许为 NULL，因为按项目惯例迁移先于部署执行，
-- 这期间在跑的还是旧代码，它 INSERT bookmark 时不会带 site_id。收紧成 NOT NULL 放在
-- 全量重抓之后的清理批次里做。

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 站点表：一个域名一行
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS site (
    id               varchar(40)  PRIMARY KEY,
    -- 含端口，与 bookmark.url_host 同源（java.net.URL 的 authority）
    host             varchar(200) NOT NULL,
    scheme           varchar(10)  NOT NULL,
    link_type        varchar(20)  NOT NULL DEFAULT 'DOMAIN',

    -- 品牌名/短名的权威值只由**首页抓取**写入；深链页面也会返回 og:site_name，
    -- 但那是二等来源，只在本列为 NULL 时回填，避免某个视频页里写歪的 site_name 带跑整站
    brand_name       varchar(200),
    short_name       varchar(100),
    nsfw             boolean      NOT NULL DEFAULT false,
    nsfw_reason      varchar(50),

    -- 域名级活性。与 bookmark.page_alive 分开：深链腐烂速度远高于域名（视频被删、仓库归档），
    -- 但域名死了就没必要逐页去查
    is_alive         boolean      NOT NULL DEFAULT true,
    last_check_at    timestamp,
    next_check_at    timestamp,
    consecutive_fail smallint     NOT NULL DEFAULT 0,

    verify_flag      boolean      NOT NULL DEFAULT false,
    locked_fields    varchar(200),
    create_time      timestamp    NOT NULL DEFAULT now(),
    update_time      timestamp    NOT NULL DEFAULT now()
);

COMMENT ON COLUMN site.host          IS '域名(含端口)，与 bookmark.url_host 同源';
COMMENT ON COLUMN site.brand_name    IS '站点全名(og:site_name / manifest.name)，仅由首页抓取写入';
COMMENT ON COLUMN site.short_name    IS '站点短名(manifest.short_name)，磁贴文案的唯一标准来源';
COMMENT ON COLUMN site.is_alive      IS '域名级活性；为 false 时该站点下所有页面直接判不可达，不逐页探测';
COMMENT ON COLUMN site.verify_flag   IS '人工认证：品牌名与图标已核对，抓取不再覆盖';
COMMENT ON COLUMN site.locked_fields IS '管理员手工锁定、不允许自动抓取覆盖的字段(逗号分隔)';

-- 按 host 单行读写（getOrCreateByHost 依赖它捕获并发插入冲突后回查），唯一约束兼作查询索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_host ON site (host);
-- 站点巡检的候选查询：WHERE next_check_at <= now() ORDER BY next_check_at
CREATE INDEX IF NOT EXISTS idx_site_next_check ON site (next_check_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- 回填：每个 host 一行
-- ─────────────────────────────────────────────────────────────────────────────
-- 取值来源刻意分两种：
--   * 标量字段(短名/NSFW/人工认证) 取该 host 下**根路径那一行**，没有根路径行则取最早创建的一行。
--     根路径是站点首页，它抓到的 app_name 才是站点级的；深链行的 app_name 可能是页面标题。
--   * is_alive 取该 host 下**任意一页活着**（bool_or）。判定「域名死了」的标准应该是所有页面都
--     打不开，用单行的 is_activity 会因为随便一个失效深链就把整站判死。
--
-- app_name → short_name：app_name 一直存的是 manifest 短名（successInit 里 wrapper.name），
-- 语义对应 short_name。brand_name 留空，等首页抓取填。
WITH picked AS (
    SELECT DISTINCT ON (url_host)
           url_host, url_scheme, app_name, nsfw, nsfw_reason, verify_flag, create_time
      FROM bookmark
     ORDER BY url_host, (url_path = '/') DESC, create_time
), alive AS (
    SELECT url_host, bool_or(is_activity) AS any_alive
      FROM bookmark
     GROUP BY url_host
)
INSERT INTO site (id, host, scheme, link_type, short_name, nsfw, nsfw_reason, is_alive, verify_flag, create_time)
SELECT gen_random_uuid()::text,
       p.url_host,
       p.url_scheme,
       -- link_type 是 WebsiteParser.classifyLinkType 的 SQL 近似：IPv6 字面量(如 [::1]:8080)
       -- 在这里会落到 OTHER 而 Kotlin 判 LOCAL。这类书签本就不参与抓取，且下一次写入时由
       -- Kotlin 覆盖成权威值，不值得为它在 SQL 里复刻一套 IPv6 解析
       CASE
           WHEN split_part(p.url_host, ':', 1) IN ('localhost', '127.0.0.1') THEN 'LOCAL'
           WHEN split_part(p.url_host, ':', 1) ~ '^\d{1,3}(\.\d{1,3}){3}$'   THEN 'IP'
           WHEN position('.' in split_part(p.url_host, ':', 1)) > 0          THEN 'DOMAIN'
           ELSE 'OTHER'
       END,
       p.app_name,
       p.nsfw,
       p.nsfw_reason,
       a.any_alive,
       p.verify_flag,
       p.create_time
  FROM picked p
  JOIN alive a ON a.url_host = p.url_host
    ON CONFLICT (host) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- bookmark 挂到 site 上
-- ─────────────────────────────────────────────────────────────────────────────
-- url_host 刻意**保留**为冗余列：搜索、后台列表、findListByHost 都按它过滤，
-- 为一次 host 过滤去 join site 不值得。它由 site 单向同步，业务代码只读。
ALTER TABLE bookmark ADD COLUMN IF NOT EXISTS site_id varchar(40);
COMMENT ON COLUMN bookmark.site_id IS '所属站点ID；url_host 是它的冗余副本，只读';

UPDATE bookmark b
   SET site_id = s.id
  FROM site s
 WHERE s.host = b.url_host
   AND b.site_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookmark_site ON bookmark (site_id);

COMMIT;

-- 验证：
-- 1) 每个 host 恰好一行 site，且没有漏挂的 bookmark
-- SELECT (SELECT count(DISTINCT url_host) FROM bookmark) AS hosts, (SELECT count(*) FROM site) AS sites,
--        (SELECT count(*) FROM bookmark WHERE site_id IS NULL) AS orphan_bookmarks;
-- 2) 抽查短名是否来自首页那一行
-- SELECT s.host, s.short_name, b.url_path, b.app_name
--   FROM site s JOIN bookmark b ON b.site_id = s.id
--  WHERE s.short_name IS NOT NULL ORDER BY s.host LIMIT 20;
-- 3) link_type 分布（OTHER 偏多说明有 IPv6/异常 host 需要人工看一眼）
-- SELECT link_type, count(*) FROM site GROUP BY link_type;
