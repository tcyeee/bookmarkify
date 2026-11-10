package top.tcyeee.bookmarkify.entity.enums

/**
 * 用户行为审计的行为类型。
 *
 * 与 [AiCallScene] / scrapper_call_log 的 layer/source 是反方向的同一种反射：那两处记的是
 * 我方对第三方的调用，这里记的是用户对本系统的操作，供运营/客服回答"这个人到底有没有做过
 * 某件事"。落库见 [top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity]。
 */
enum class UserBehaviorType {
    /**
     * 登录成功（五条路径共用一个出口 `UserServiceImpl.startSession`），detail 记登录方式
     * （ACCOUNT / EMAIL_CODE / GOOGLE / GITHUB / QUICK）。
     *
     * 补上它之前，这张表里全是"增"类业务动作，偏偏没有登录 —— 而"这个人最后一次登录是什么时候"
     * 是客服与安全问题里最常被问到的那一个，`user_info` 也没有 `last_login_at` 可查。
     * 登录**失败**不在这里：失败时往往连 uid 都没有（账号不存在），而本表的 uid 列非空；
     * 它记在文本日志里（`[登录失败]`，邮箱打码）。
     */
    LOGIN,

    /** 主动退出登录(/auth/logout)。与 [LOGIN] 成对，否则看不出一个会话实际存续了多久 */
    LOGOUT,

    /** 通过 URL 新增一条书签(/bookmark/addOne) */
    ADD_BOOKMARK,

    /** 创建并发布一个书签分享/书签集(/share/create) */
    PUBLISH_SHARE,

    /** 从文件批量导入书签(/bookmark/upload) */
    IMPORT_BOOKMARK,

    /** 生成一个新的插件访问令牌(/user/access-token/create) */
    CREATE_ACCESS_TOKEN,

    /** 插件持令牌查询网站信息(/extension/site-info)，是 X-Extension-Token 唯一的取数接口 */
    QUERY_BY_TOKEN,
}
