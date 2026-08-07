package top.tcyeee.bookmarkify.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.ConfigChangeLogEntity
import top.tcyeee.bookmarkify.mapper.ConfigChangeLogMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.ISystemConfigService
import top.tcyeee.bookmarkify.utils.StpKit
import java.util.concurrent.atomic.AtomicReference

/**
 * 一组配置在 `system_config` 里的存取门面：key → 一份 JSON → 一个类型化的配置对象。
 *
 * 每组配置的差异只有三点——key、兜底值、不变量校验。此前这三点各自被抄进一个独立的 service，
 * 于是「解析失败退默认值」「写前校验」「别在循环里读」这些规矩要在每一份拷贝里各维护一遍。
 * 配置组数是这套东西唯一会线性增长的维度，所以把相同的那部分收在这里，新增一组配置
 * 只剩下 data class + [fallback] + [validate]。
 *
 * ## 缓存
 *
 * [get] 走进程内的 [AtomicReference]，[update] 成功后就地覆盖。加它不是为了那点查询开销，
 * 而是为了取消「批量巡检必须把配置读一次再手工往下传」这条口头约定——约定不会编译报错，
 * 违反它的表现只是巡检悄悄多打了几百次 `system_config`，没有任何症状。
 *
 * **前提是单实例部署**：另一个实例改了配置，本实例的缓存不会失效。多实例上线时这里要换成
 * Redis 失效广播，届时改动只在这一个类里。
 *
 * ## 为什么读路径也要校验
 *
 * 配置有跨字段不变量（如「判失活次数必须小于归档阈值」），而 `system_config` 是一张
 * 可以被 psql 直接改的表。只在写路径校验的话，手工改坏的值会安静地生效——
 * 落到业务上是某个状态永远不出现这类查无可查的现象，而不是一个报错。
 *
 * ## 审计
 *
 * [update] 是全系统改写 `system_config` 的唯一入口，所以变更留痕挂在这里就够了——
 * 每组配置各写一遍的话，漏掉的那一组恰恰不会有任何症状。详见 [ConfigChangeLogEntity]。
 */
abstract class JsonConfigAccessor<T : Any>(
    private val configKey: String,
    private val type: Class<T>,
    private val systemConfigService: ISystemConfigService,
    private val objectMapper: ObjectMapper,
    private val configChangeLogMapper: ConfigChangeLogMapper,
    private val userMapper: UserMapper,
) {
    private val cached = AtomicReference<T>()

    /** 当前生效的配置。表里没有、读坏了、或存着的值违反不变量时返回 [fallback]。 */
    fun get(): T = cached.get() ?: load()

    /** 写入并使缓存生效；校验不通过时抛出 [validate] 抛的异常，不写库、不动缓存。 */
    fun update(value: T): T {
        validate(value)
        // 旧值取库里那份原文，不是 [cached]：缓存里可能是兜底值（库中的值读坏或违反约束时），
        // 而审计要记的是「这一行原本是什么」，不是「进程当时在用什么」
        val previous = runCatching { systemConfigService.getValue(configKey) }.getOrNull()
        val json = objectMapper.writeValueAsString(value)
        systemConfigService.setValue(configKey, json)
        cached.set(value)
        recordChange(previous, json)
        return value
    }

    /**
     * 落一行变更审计。
     *
     * 失败只记 warn：这是留痕，写不进去不该反过来让一次已经生效的配置保存报错——
     * 与 `sweep_log` / `ai_call_log` 的处理一致，也让审计表可以晚于 API 上线。
     */
    private fun recordChange(previous: String?, json: String) = runCatching {
        // 会话取不到就记 null（例如将来从定时任务或初始化代码里改配置），不因此让保存失败
        val operatorId = runCatching { StpKit.ADMIN.loginIdAsString }.getOrNull()
        configChangeLogMapper.insert(
            ConfigChangeLogEntity(
                configKey = configKey,
                oldValue = previous,
                newValue = json,
                operatorId = operatorId,
                // 昵称存快照而不是读时联表：名字会改、账号会删，审计要记的是「当时是谁」
                operatorName = operatorId?.let { id -> runCatching { userMapper.selectById(id)?.nickName }.getOrNull() },
            )
        )
    }.onFailure { log.warn("[JsonConfigAccessor] 配置变更留痕失败(忽略): key=$configKey, err=${it.message}") }.let { }

    @Synchronized
    private fun load(): T {
        // 双检：并发首读时后来者直接用先到者的结果，避免同一份配置被解析多次
        cached.get()?.let { return it }
        return read().also { cached.set(it) }
    }

    private fun read(): T {
        val raw = systemConfigService.getValue(configKey) ?: return fallback()
        val parsed = runCatching { objectMapper.readValue(raw, type) }.getOrElse {
            // 配置读坏不该让整个管理页 500，退回兜底值即可，管理员重新保存一次就能修正
            log.warn("[JsonConfigAccessor] 解析配置失败, 使用兜底值: key=$configKey, raw=$raw, err=${it.message}")
            return fallback()
        }
        return runCatching { validate(parsed); parsed }.getOrElse {
            log.warn("[JsonConfigAccessor] 库中配置违反约束, 使用兜底值: key=$configKey, raw=$raw, err=${it.message}")
            fallback()
        }
    }

    /** 表里没有这一行、或存着的值不可用时的配置。通常就是 data class 的全默认实例。 */
    protected abstract fun fallback(): T

    /** 校验不变量，不通过时抛异常。写路径把它抛给调用方，读路径捕获后退回 [fallback]。 */
    protected abstract fun validate(value: T)
}
