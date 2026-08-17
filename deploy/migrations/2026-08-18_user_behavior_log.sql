-- ─────────────────────────────────────────────────────────────────────────────
-- 2026-08-18 · 新增用户行为审计日志(user_behavior_log)
--
-- 记录用户对系统的关键操作(新增书签 / 发布分享 / 导入书签 / 生成插件令牌 / 插件持令牌查询)，
-- 供运营/客服回答"这个人到底有没有做过某件事"。与 ai_call_log / scrapper_call_log 是同一类
-- 反射，方向相反：那两张记的是我方对第三方的调用，这张记的是用户对我方的操作。
--
-- 写入侧(UserBehaviorLogServiceImpl.record)整段包在 runCatching 里，同 ai_call_log /
-- config_change_log 的约定：表不存在只会打一行 warn，不影响被记录的业务操作本身，可以按任意
-- 顺序应用。仍建议部署前迁移，避免上线初期这段窗口的用户行为丢失取证价值。
--
-- 幂等，可重复执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.user_behavior_log (
    id character varying(40) NOT NULL,
    uid character varying(40) NOT NULL,
    nick_name_snapshot character varying(200),
    behavior_type character varying(32) NOT NULL,
    detail character varying(500),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_behavior_log_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_user_behavior_log_uid_time ON public.user_behavior_log USING btree (uid, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_user_behavior_log_type_time ON public.user_behavior_log USING btree (behavior_type, create_time DESC);
