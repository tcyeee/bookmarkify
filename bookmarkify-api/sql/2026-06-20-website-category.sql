-- 网站自动分类标签：分类字典表 + 网站↔分类 关联表
-- 日期: 2026-06-20
-- schema: bookmarkify
-- 说明: 全新表，无存量数据；可重复执行（IF NOT EXISTS / ON CONFLICT DO NOTHING）。

-- 1) 分类字典表（受控词表，预先 seed）
CREATE TABLE IF NOT EXISTS bookmarkify.website_category (
    id            varchar(64)  PRIMARY KEY,
    slug          varchar(64)  NOT NULL,
    name          varchar(64)  NOT NULL,
    description   varchar(500),
    color         varchar(16),
    sort          int          NOT NULL DEFAULT 0,
    deleted       boolean      NOT NULL DEFAULT false,
    create_time   timestamp    NOT NULL DEFAULT now(),
    last_modified timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uk_website_category_slug UNIQUE (slug)
);

-- 2) 网站↔分类 关联表
CREATE TABLE IF NOT EXISTS bookmarkify.bookmark_category (
    id          varchar(64) PRIMARY KEY,
    bookmark_id varchar(64) NOT NULL,
    category_id varchar(64) NOT NULL,
    source      varchar(32) NOT NULL DEFAULT 'DEEPSEEK',
    create_time timestamp   NOT NULL DEFAULT now(),
    deleted     boolean     NOT NULL DEFAULT false,
    CONSTRAINT uk_bookmark_category UNIQUE (bookmark_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_bookmark_category_bookmark ON bookmarkify.bookmark_category (bookmark_id);

-- 3) seed 固定词表（id 形如 cat_<slug>，deterministic，便于重复执行）
INSERT INTO bookmarkify.website_category (id, slug, name, sort) VALUES
    ('cat_dev',      'dev',      '开发',     10),
    ('cat_design',   'design',   '设计',     20),
    ('cat_ai',       'ai',       'AI',       30),
    ('cat_tool',     'tool',     '效率工具', 40),
    ('cat_social',   'social',   '社交',     50),
    ('cat_video',    'video',    '影视',     60),
    ('cat_music',    'music',    '音乐',     70),
    ('cat_shopping', 'shopping', '购物',     80),
    ('cat_news',     'news',     '新闻资讯', 90),
    ('cat_study',    'study',    '学习教育', 100),
    ('cat_finance',  'finance',  '金融',     110),
    ('cat_game',     'game',     '游戏',     120),
    ('cat_read',     'read',     '阅读',     130),
    ('cat_job',      'job',      '求职招聘', 140),
    ('cat_gov',      'gov',      '政务',     150),
    ('cat_other',    'other',    '其他',     160)
ON CONFLICT (slug) DO NOTHING;
