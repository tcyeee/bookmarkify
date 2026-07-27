-- 新增 access_token 表：用户可在设置页自行生成/撤销的浏览器插件访问令牌。
-- 与 Sa-Token 的登录会话（satoken）完全独立，仅供 /extension/** 下的插件专用接口使用，
-- 泄露后影响面严格限定在"查询网站信息"这一项能力，不涉及账号完整会话。
-- 详见根目录 ACCESS_TOKEN_DESIGN.md。

BEGIN;

CREATE TABLE IF NOT EXISTS access_token (
    id             varchar(64) PRIMARY KEY,
    uid            varchar(64) NOT NULL,
    name           varchar(100) NOT NULL,
    token_hash     varchar(64) NOT NULL,
    token_prefix   varchar(32) NOT NULL,
    last_used_at   timestamp NULL,
    create_time    timestamp NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_access_token_hash ON access_token (token_hash);
CREATE INDEX IF NOT EXISTS idx_access_token_uid ON access_token (uid);

COMMIT;
