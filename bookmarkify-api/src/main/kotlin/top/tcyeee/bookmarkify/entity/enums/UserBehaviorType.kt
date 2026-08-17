package top.tcyeee.bookmarkify.entity.enums

/**
 * 用户行为审计的行为类型。
 *
 * 与 [AiCallScene] / scrapper_call_log 的 layer/source 是反方向的同一种反射：那两处记的是
 * 我方对第三方的调用，这里记的是用户对本系统的操作，供运营/客服回答"这个人到底有没有做过
 * 某件事"。落库见 [top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity]。
 */
enum class UserBehaviorType {
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
