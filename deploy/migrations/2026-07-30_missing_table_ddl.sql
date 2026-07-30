-- 补齐仓库里从未入库的建表语句。
--
-- 项目的迁移是手工执行的（没有 Flyway），于是有几张表只存在于生产库里：它们当初是直接在库上
-- 建的，DDL 从来没进过版本库。后果是新环境（本地/dev/灾备重建）照文档搭不起来，
-- 而缺哪几张表只能靠启动后报错去发现。
--
-- 这些表在既有环境上都已存在，CREATE TABLE IF NOT EXISTS 是空操作；列类型以生产库现状为准，
-- 本文件不试图对齐或修改已存在的表结构。bookmark_ping_log 的建表语句在
-- 2026-07-30_ping_outcome.sql 里，那边还要接着改它的列，就不重复列在这里。

BEGIN;

-- 通用系统配置(key-value)，每种配置一行，value 为 JSON 字符串。
-- 目前存着书签巡检配置(bookmark_liveness_check_frequency)。
CREATE TABLE IF NOT EXISTS system_config (
    id           varchar(40)  PRIMARY KEY,
    config_key   varchar(100) NOT NULL,
    config_value text,
    update_time  timestamp    NOT NULL DEFAULT now(),
    create_time  timestamp    NOT NULL DEFAULT now()
);
-- 按 key 单行读写，唯一约束同时兼作查询索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_config_key ON system_config (config_key);

-- 管理员表格自定义列配置（宽度/显隐/排序），按 管理员ID + 表格标识 隔离
CREATE TABLE IF NOT EXISTS admin_grid_config (
    id          varchar(40)  PRIMARY KEY,
    admin_id    varchar(40)  NOT NULL,
    grid_id     varchar(100) NOT NULL,
    config_json text,
    update_time timestamp    NOT NULL DEFAULT now(),
    create_time timestamp    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_grid_config_owner ON admin_grid_config (admin_id, grid_id);

COMMIT;

-- 验证：
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema = 'bookmarkify' AND table_name IN ('system_config','admin_grid_config','bookmark_ping_log');
