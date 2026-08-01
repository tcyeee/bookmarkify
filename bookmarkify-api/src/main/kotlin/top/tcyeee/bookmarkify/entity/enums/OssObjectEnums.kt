package top.tcyeee.bookmarkify.entity.enums

/**
 * object key 是怎么算出来的。
 *
 * 这一列决定的是**这个对象能不能参与去重**，所以它记的是事实（key 的推导方式）而不是意图。
 */
enum class OssAddressing {
    /** `sha256(字节)`。同字节必然同 key，可去重，且对象一旦写入就不再变化 */
    CONTENT,

    /**
     * `sha256(源URL)`。同一 URL 永远落同一个 key，重抓时 PUT 直接覆盖。
     *
     * 有一个容易被忽略的好性质：**自我覆盖，存储量有上界**。截图刻意长期保留这种寻址 ——
     * 它每次抓都不一样，改成 [CONTENT] 等于无上界增长，而去重收益恰好是零。
     */
    SOURCE_URL,

    /** 随机 UUID。用户上传走这条：同一个人传两次同一张图，今天就是两个对象 */
    RANDOM,

    /** 账本建立之前写入的存量对象，推导方式不可考 */
    LEGACY,
}

/**
 * 谁把这个对象写进桶的。
 *
 * 与 [OssAddressing] 分开是因为二者会交叉：scrapper 写的资产是 [OssAddressing.SOURCE_URL]，
 * 但 P5 之后会变成 [OssAddressing.CONTENT]，而截图两种情况下都留在 [OssAddressing.SOURCE_URL]。
 */
enum class OssObjectSource {
    /** 用户主动上传：头像、背景图。**真数据，删了就没了** */
    USER_UPLOAD,

    /** scrapper 抓回来的站点资产。**可再生**，重抓一次即可 */
    SCRAPPER,

    /** 人工预置，例如系统默认背景图 */
    SYSTEM,
}

/**
 * 对账任务给出的判断。
 *
 * 只有对账任务写这一列 —— 业务代码写入时一律 [ACTIVE]，"这东西还有没有人要"不是写入方
 * 能回答的问题。
 */
enum class OssObjectState {
    /** 桶里在，且还有人引用 */
    ACTIVE,

    /** 桶里在，但扫遍所有引用方表都没人指向它 */
    ORPHAN,

    /** 已从桶里删除，行留着做审计 */
    DELETED,
}
