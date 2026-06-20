-- 移除访客登录 + 移除短信登录 的数据库变更
-- 日期: 2026-06-20
-- schema: bookmarkify
-- 说明: 当前无存量用户，无需数据迁移；直接执行即可。

-- 1) 短信记录表整表删除（短信功能已彻底移除）
DROP TABLE IF EXISTS bookmarkify.sms_record;

-- 备注:
-- * sys_user.device_id / sys_user.phone 两列保留（置为无用 nullable 列）:
--   - device_id 仍由后端随机填充以满足非空约束，不再承担"指纹登录"语义;
--   - phone 不再有任何写入路径，恒为 null;
--   保留以避免牵连 admin 端 UserAdminVO 与其前端展示。如需彻底清列，另行执行:
--   -- ALTER TABLE bookmarkify.sys_user DROP COLUMN phone;
--   -- ALTER TABLE bookmarkify.sys_user ALTER COLUMN device_id DROP NOT NULL;  -- 若想保留列但放开约束
