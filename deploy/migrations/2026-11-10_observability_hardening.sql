-- 可观测性加固：把"文本日志"与"领域账本"这两套原本互不相连的日志体系焊在一起，
-- 并补上后台操作审计这张从来没有过的表。
--
-- ## 必须在部署新 API **之前**应用
--
-- 新代码给三张日志表各写一列 request_id，并往一张新表 admin_action_log 写审计。
-- 三处写入都包在 runCatching 里（日志写失败从不影响业务），所以先部署不会 500 ——
-- 代价是**整段窗口里这些日志一行都不落**（insert 整条失败，不是缺一列），
-- 而那正是这次改动要消灭的盲区。顺序反了只是白白丢一段证据，没有数据风险。
--
-- ## 三张表为什么加 request_id
--
-- 这套系统的日志实际是两层：文本日志（logback，每行带 requestId）与领域账本
-- （ai_call_log / scrapper_call_log / user_behavior_log …）。从前两层之间**没有任何共同键**：
-- 拿到一条失败的 scrapper_call_log 行，没办法找到同一次请求产生的那几十行文本日志，
-- 反过来也一样 —— 于是排查永远只能看一半现场。
--
-- 唯一的例外是 page_ping_log.sweep_id（巡检轮次 → 它产生的探测），它证明了这个关联键是有用的，
-- 只是从前只在那一处做了。
--
-- 列宽 16：requestId 是 uuid 前 8 位（见 PreRequestFilter），留一倍余量。
-- 可为空且无默认：定时任务、启动初始化这些非 HTTP 入口确实没有请求 id，
-- 而迁移之前的历史行也确实不属于任何一次已知请求 —— 空值在这里是事实，不是缺陷。

ALTER TABLE public.ai_call_log ADD COLUMN IF NOT EXISTS request_id character varying(16);
ALTER TABLE public.scrapper_call_log ADD COLUMN IF NOT EXISTS request_id character varying(16);
ALTER TABLE public.user_behavior_log ADD COLUMN IF NOT EXISTS request_id character varying(16);

-- 新增的清理任务按 create_time 删行，而 user_behavior_log 原有的两个索引都是
-- (uid, create_time) / (behavior_type, create_time) —— 复合索引的前导列不是 create_time，
-- 一个全局的时间谓词用不上它们。另外两张表已经各有一个 create_time 索引。
CREATE INDEX IF NOT EXISTS idx_user_behavior_log_create_time
    ON public.user_behavior_log USING btree (create_time);

-- ## admin_action_log：后台操作审计
--
-- 在此之前，整个后台只有一张审计表 config_change_log（system_config 的每次写入）。
-- 而管理员可以删页面、下架分享、改 NSFW 判定、跑孤儿清理、重算图标判定、调试抓取任意域名 ——
-- 这些动作不可逆或对用户可见，却没有任何一行记录说得出"谁、什么时候、对哪条记录做的"。
--
-- 写入由 AdminAuditAspect 统一完成，落的是带 @AdminAudit 注解的那批接口（当前 31 个）。
-- 失败的操作也记（success=false + error_msg）：「管理员试过但没成功」与「管理员没试过」
-- 是两件完全不同的事，只记成功的审计表分不出它们。
--
-- 无 site_/page_/user_ 前缀：一条审计是系统级事实，operator_id 是动作的发起者而非归属者，
-- 同 config_change_log / feedback（见根 CLAUDE.md 的表名前缀规则）。
CREATE TABLE IF NOT EXISTS public.admin_action_log (
    id character varying(40) NOT NULL,
    -- 动作中文名，取自 @AdminAudit("…")。刻意存中文而不是方法名：这张表是给人读的
    action character varying(64) NOT NULL,
    -- 控制器类名.方法名，排查时用来直接定位代码
    handler character varying(200) NOT NULL,
    http_method character varying(10),
    path character varying(500),
    -- 被操作对象的 id（第一个 @PathVariable）。只取路径变量，不去猜请求体里哪个字段是"目标"：
    -- 猜错会让这一列看起来可信却指向错误的记录，比留空更糟
    target_id character varying(64),
    -- 入参摘要(JSON，1000 字符截断)。password/token/secret 等字段名在写入前已打码
    params character varying(1000),
    success boolean NOT NULL,
    error_msg character varying(500),
    -- 后台登录失败时没有操作者，故可空；operator_name 是昵称快照，同 config_change_log
    operator_id character varying(64),
    operator_name character varying(200),
    duration_ms bigint DEFAULT 0 NOT NULL,
    request_id character varying(16),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT admin_action_log_pkey PRIMARY KEY (id)
);

-- 审计列表默认按时间倒序翻
CREATE INDEX IF NOT EXISTS idx_admin_action_log_create_time
    ON public.admin_action_log USING btree (create_time DESC);

-- 「这个管理员都做过什么」是审计第二常见的问法
CREATE INDEX IF NOT EXISTS idx_admin_action_log_operator
    ON public.admin_action_log USING btree (operator_id, create_time DESC);

-- 失败的操作是最该被先看到的那一批，部分索引只覆盖它们
CREATE INDEX IF NOT EXISTS idx_admin_action_log_failed
    ON public.admin_action_log USING btree (create_time DESC) WHERE (success = false);
