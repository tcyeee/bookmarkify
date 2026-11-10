package top.tcyeee.bookmarkify.config.audit

/**
 * 标在后台接口上：**这次调用要留一条审计记录**（写入 `admin_action_log`）。
 *
 * ## 为什么后台需要审计，而且不能靠文本日志
 *
 * 在此之前，整个后台只有一张审计表：`config_change_log`（`system_config` 的每次写入）。
 * 而管理员可以删页面、封禁分享、改 NSFW 判定、跑孤儿清理、重算图标判定 —— 这些动作
 * **不可逆或对用户可见**，却没有任何一行记录说得出"是谁、什么时候、对哪条记录做的"。
 *
 * ## 为什么是"标注即审计"而不是"默认全审计"
 *
 * 后台的读接口大量用 POST（`/all`、`/preview`、`/stats`、`/sweeps`…），按 HTTP 方法自动判定
 * 会把这张表变成后台的访问日志 —— 一天几百行翻页记录，淹掉真正的那几条删除。审计表要回答的是
 * 「谁动了什么」，不是「谁看了什么」。
 *
 * 漏标的代价也不是零记录：这次改动同时给每个请求补了一行完成日志，里面带
 * `uid=admin:<id>`（见 `PreRequestFilter.logCompletion`）。也就是说"谁调了哪个后台接口"
 * 在文本日志里必定有痕迹，审计表只是在此之上把**有后果的那些**做成可查询、带目标与结果的记录。
 * 两层一起看：文本日志保覆盖，审计表保可查。
 *
 * @property value 动作的中文名，直接显示在后台审计列表里。写"删除页面"而不是"delete"——
 *   这张表是给人看的，方法名在排查时反而要再翻一次代码才知道它干了什么。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AdminAudit(val value: String)
