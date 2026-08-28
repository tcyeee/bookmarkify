-- 网站反馈组件：一个**无需登录**的公开反馈收集接口 + 后台收件箱。
--
-- 面向的调用方是外部 Agent / 集成脚本：它们带着「所属产品 + 可选邮箱 + 反馈正文」POST 到
-- /feedback/submit，后台在「第三方管理 › 网站反馈组件」里读、标记已读、批量删除。
--
-- 必须在部署新 API **之前**应用：控制器直接读写这两张表，先部署代码会让
--   - 公开接口 POST /feedback/submit 500
--   - 后台「网站反馈组件」整页 500
-- 两张表此前完全不存在。
--
-- 无 site_/page_/user_ 前缀：反馈条目不随登录用户或域名切换，是系统级实体，与
-- system_collection / config_change_log 同类（email 只是正文里的一个联系方式字段，
-- 不代表这行归属某个注册用户）。

CREATE TABLE IF NOT EXISTS public.feedback (
    id character varying(40) NOT NULL,
    -- 所属产品；取值由 feedback_target 维护，管理员可增删。不做外键——目标被删掉后
    -- 历史反馈仍要能原样读出来。
    target character varying(64) NOT NULL,
    -- 提交者留的联系邮箱，选填
    email character varying(200),
    content character varying(5000) NOT NULL,
    -- 默认未读；点开详情或批量操作后置 true
    is_read boolean DEFAULT false NOT NULL,
    -- 排障用的现场，非业务字段
    source_ip character varying(64),
    user_agent character varying(500),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    read_time timestamp without time zone,
    CONSTRAINT feedback_pkey PRIMARY KEY (id)
);

-- 收件箱默认按「未读优先 + 时间倒序」翻，索引对齐这个口径
CREATE INDEX IF NOT EXISTS idx_feedback_unread_time ON public.feedback USING btree (is_read, create_time DESC);

CREATE TABLE IF NOT EXISTS public.feedback_target (
    id character varying(40) NOT NULL,
    name character varying(64) NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT feedback_target_pkey PRIMARY KEY (id)
);

-- 同名目标只能有一条；新增时按名字判重
CREATE UNIQUE INDEX IF NOT EXISTS uk_feedback_target_name ON public.feedback_target USING btree (name);

-- 默认三个产品。AppInit 在表为空时也会补种同一批，这里显式写一份是为了生产环境
-- 应用迁移后立刻可用，不必等一次重启。
INSERT INTO public.feedback_target (id, name, sort)
VALUES
    (md5('feedback_target:Bookmarkify'), 'Bookmarkify', 0),
    (md5('feedback_target:Vialite'),     'Vialite',     1),
    (md5('feedback_target:AgentTool'),   'AgentTool',   2)
ON CONFLICT (name) DO NOTHING;
