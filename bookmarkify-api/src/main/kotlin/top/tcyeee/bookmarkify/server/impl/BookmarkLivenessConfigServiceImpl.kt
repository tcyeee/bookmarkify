package top.tcyeee.bookmarkify.server.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.mapper.ConfigChangeLogMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.ISystemConfigService
import top.tcyeee.bookmarkify.server.config.JsonConfigAccessor
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy

/**
 * key 名是历史遗留：值里如今还装着归档阈值和内容重抓间隔，早已不只是 "frequency"。
 * 改名要配一条数据迁移，收益仅是好看，暂不改。
 */
private const val CONFIG_KEY = "bookmark_liveness_check_frequency"

@Service
class BookmarkLivenessConfigServiceImpl(
    systemConfigService: ISystemConfigService,
    // Hutool 的 JSONUtil.toBean 走 JavaBean 反射(无参构造 + setter)，
    // 而这里的配置值是 Kotlin 全 val 的 data class，两者都没有，必须用带 kotlin module 的 Jackson。
    objectMapper: ObjectMapper,
    configChangeLogMapper: ConfigChangeLogMapper,
    userMapper: UserMapper,
) : IBookmarkLivenessConfigService,
    JsonConfigAccessor<BookmarkLivenessConfigValue>(
        CONFIG_KEY,
        BookmarkLivenessConfigValue::class.java,
        systemConfigService,
        objectMapper,
        configChangeLogMapper,
        userMapper,
    ) {

    override fun getConfig(): BookmarkLivenessConfigValue = get()

    override fun updateConfig(value: BookmarkLivenessConfigValue): BookmarkLivenessConfigValue = update(value)

    override fun fallback() = BookmarkLivenessConfigValue()

    /**
     * 单字段的下限之外，真正重要的是三条**跨字段**关系——它们是逐项控件在前端表达不了的那部分，
     * 违反了也不会报错，只会让某个状态永远不出现。每条的后果写在对应分支上。
     */
    override fun validate(value: BookmarkLivenessConfigValue) {
        if (value.activeCheckIntervalHours < 1 || value.abnormalCheckIntervalHours < 1) {
            throw CommonException(ErrorType.E102, "检测频率必须大于等于 1 小时")
        }
        // 异常书签理应比已激活书签查得更勤：前端只挡了各自 >=1 的下限，
        // 两者的大小关系必须在这里做唯一的服务端校验，否则管理员可能配出「异常书签反而查得更少」的语义倒挂。
        if (value.abnormalCheckIntervalHours > value.activeCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "异常书签重试间隔不能低于已激活书签检测频率")
        }
        // 倍数取 1 表示不退避（固定间隔），是个合法配置；0 和负数没有语义。
        // 上不封顶交给「最长间隔」去兜，那才是真正决定曲线终点的那个数。
        if (value.abnormalBackoffMultiplier < 1) {
            throw CommonException(ErrorType.E102, "重试叠加倍数必须大于等于 1")
        }
        // 上限小于基数的话，第一次重试就已经越过上限，整条曲线塌成固定间隔——
        // 策略侧对此有兜底（取基数），但那是防御，不是可以配出来的合法状态。
        if (value.abnormalMaxIntervalHours < value.abnormalCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "最长重试间隔不能短于初次重试间隔")
        }
        // 到达即彻底停止巡检，且没有任何定时任务能把它捞回来（唯一的出口是新用户添加该网址），
        // 所以下限卡在 MIN_MAX_RETRY_FAILURES：配成 1 意味着第一次探测失败就永久放弃，
        // 而单次失败连判失活都不够格（见 deadConfirmFailures），更不该够格判死刑
        if (value.maxRetryFailures < LivenessPolicy.MIN_MAX_RETRY_FAILURES) {
            throw CommonException(
                ErrorType.E102,
                "失活网站最大重试次数必须大于等于 ${LivenessPolicy.MIN_MAX_RETRY_FAILURES}"
            )
        }
        // 1 表示「探一次不通就判失活」，是退回旧行为，允许。
        // 上界必须小于归档阈值：配得比它还大，记录会在判失活之前先被归档，
        // 于是「失活」这个状态永远不会出现，而失活书签重试巡检正是按它选候选的
        if (value.deadConfirmFailures < 1 || value.deadConfirmFailures >= value.maxRetryFailures) {
            throw CommonException(
                ErrorType.E102,
                "判定失活的连续失败次数必须在 1 ~ ${value.maxRetryFailures - 1} 之间(需小于最大重试次数)"
            )
        }
        // 重新抓取一次的代价远高于一次 HEAD 探测（可能拉起无头浏览器、下载图片、传 OSS），
        // 配得比活性探测还勤就失去了分档的意义，且会成倍放大抓取压力。
        if (value.contentRefreshIntervalDays < 1) {
            throw CommonException(ErrorType.E102, "内容重新抓取间隔必须大于等于 1 天")
        }
        if (value.contentRefreshIntervalDays * 24 < value.activeCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "内容重新抓取间隔不能短于已激活书签检测频率")
        }
    }
}
