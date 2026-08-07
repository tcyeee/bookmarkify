-- 系统配置变更审计：`system_config` 每被改写一次，落一行。
--
-- **应在部署新 API 之前应用。** 写入包在 runCatching 里（表不存在只打一条 warn，配置照常保存），
-- 所以晚了不会把系统跑挂——但这张表正是"谁在什么时候把哪个参数调成了什么"唯一的现场，
-- 晚一天就少一天证据，而配置变更本身是低频事件，补不回来。
--
-- ## 为什么单独建一张表，而不是给 system_config 加几列
--
-- `system_config` 是**当前值**，一个 key 一行、原地覆盖。审计要的是**历史**，一次修改一行。
-- 塞进同一张表只能存"上一次的值"，而真正会被问到的问题是"这半年里它被谁改过几次"。
--
-- ## 为什么存整份 JSON 而不是字段级 diff
--
-- 配置类的字段会随版本增删，而 diff 是按当时的字段结构算出来的——存 diff 等于把一份
-- 半年后可能已经不存在的结构固化进历史行里。存原文则永远可读，差异是读的时候再算的事。
--
-- 不设保留期：一年也就几十行，与 page_ping_log（每次探测一行、90 天清理）不是一个量级。
--
-- 幂等：全部 IF NOT EXISTS，可重复执行。

CREATE TABLE IF NOT EXISTS public.config_change_log (
    id            varchar(64) NOT NULL,
    -- system_config.config_key，一组配置一个 key
    config_key    varchar(128) NOT NULL,
    -- 改动前的整份 JSON 原文；NULL 表示此前库里没有这一行（该组配置的首次写入）
    old_value     text,
    -- 改动后的整份 JSON 原文
    new_value     text NOT NULL,
    -- 操作管理员的 user_info.id。刻意不加外键：审计行不该因为账号被删而消失或阻塞删除
    operator_id   varchar(64),
    -- 操作当时的管理员昵称快照。名字会改，审计要记的是"当时是谁"
    operator_name varchar(200),
    create_time   timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT config_change_log_pkey PRIMARY KEY (id)
);

-- 唯一的查询形态是"某个 key 的变更史，按时间倒序"
CREATE INDEX IF NOT EXISTS idx_config_change_log_key_time
    ON public.config_change_log (config_key, create_time DESC);
