package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OSS 对象治理（对账 / 回收）的开关，见根目录 `FILE-SYSTEM-REFACTOR.md`。
 *
 * 与 `bookmarkify.aliyun.oss.*`（连接凭据）刻意分开：那些是"怎么连上桶"，这些是"拿桶里的
 * 东西怎么办"，改动频率和影响面完全不同。
 */
@ConfigurationProperties(prefix = "bookmarkify.oss")
data class OssGovernanceConfig(

    /**
     * 对账时是否真的删除孤儿对象。
     *
     * **默认关闭，这是有意的。** 对账任务只要跑起来就有价值（它把"桶里到底有什么"这个盲区补上了），
     * 而删除是不可逆的线上动作 —— 一旦引用方收集逻辑漏了一处，被删的就是用户头像。
     * 正确的上线顺序是：先只报不删跑上几轮，人工核对孤儿清单确实都是垃圾，再打开这个开关。
     */
    var reclaimOrphans: Boolean = false,

    /**
     * 孤儿对象的观察期（天）。只有"入账已超过这么久、且这么久没被引用过"的对象才会被回收。
     *
     * 这个宽限期挡的是**时序竞争**：scrapper 先把对象 PUT 进桶，API 落行是随后的事，
     * 中间那个窗口里对象确实无人引用 —— 没有宽限期的话，一次撞上对账的正常抓取就会被
     * 当场清理掉刚传上去的图。
     */
    var orphanGraceDays: Long = 30,

    /** 单个前缀最多扫多少个对象的安全阀，防止桶失控时把整个列表读进堆里 */
    var reconcileMaxKeys: Int = 200_000,
)
