package top.tcyeee.bookmarkify.entity.enums

/**
 * 一次活性探测的结论。
 *
 * 存在 [UNKNOWN] 这一态是本类型的全部意义：探测链路是 API → scrapper →（代理）→ 目标站点，
 * 中间任何一段出问题，我方得到的都只是「没看到活着的迹象」，而不是「站点死了」。
 * 把两者合并成一个 Boolean 时，抓取服务没起、鉴权配错、并发超限被 load_shed 打回 503，
 * 都会被写成书签失联——一次我方故障就能按每小时数百条的速度把好端端的书签洗成失效。
 *
 * 判定归属：[ALIVE]/[DEAD] 是**目标站点**的事实，[UNKNOWN] 是**我方链路**的状态，
 * 只有前两者可以落到 `bookmark.parse_status` 上。
 */
enum class PingOutcome {
    /** 目标站点有响应（scrapper 侧判定 HTTP 状态码 < 500） */
    ALIVE,

    /** scrapper 明确判定目标站点打不开：连接失败、超时、5xx，或该 URL 根本不合法 */
    DEAD,

    /** 我方探测链路自身有问题，这一轮对该站点没有结论——不可据此改动书签状态 */
    UNKNOWN,
}
