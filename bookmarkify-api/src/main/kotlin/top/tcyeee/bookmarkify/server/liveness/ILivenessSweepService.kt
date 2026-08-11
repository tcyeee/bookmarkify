package top.tcyeee.bookmarkify.server.liveness

import top.tcyeee.bookmarkify.entity.SweepPreviewVO

/**
 * 定时活性巡检。
 *
 * 从 `BookmarkServiceImpl` 拆出（2026-08-11）：那个类同时被交互式加书签路径和三个定时任务写，
 * 而巡检这一块（~600 行）与其余部分之间没有共享状态，唯一的交叉点是调度列的写法 ——
 * 已经收口到 [PageScheduleWriter]。策略（[LivenessPolicy]）此前就是纯函数且测试完备，
 * 缺的一直只是把编排也搬出来。
 *
 * 契约细节（三态 ping、熔断、退避、归档、站点层短路、背压）见 `bookmarkify-api/CLAUDE.md`
 * › Liveness sweeps，以及 [LivenessSweepService] 各方法的注释。
 */
interface ILivenessSweepService {

    /** 定时扫描 UNREACHABLE 书签（含已认证）：ping 通后重新触发解析，结果写入 `page_ping_log`；异步执行，不占用调度线程 */
    fun retryUnreachableBookmarks()

    /** 定时扫描 SUCCESS 书签（含已认证）做活性复查，结果写入 `page_ping_log`；异步执行，不占用调度线程 */
    fun livenessCheckStaleBookmarks()

    /**
     * 手动触发一轮巡检之前的预览：这一轮会覆盖哪些书签、探几次、大概多久、会不会改判失联。
     *
     * 用的是与真正开跑**同一套**候选查询，所以数字对得上；但它只读不写，也不占巡检锁。
     *
     * @param taskLabel 只接受仍在运行的两个任务，其余（含已下线的 reviveArchivedBookmarks）抛 E102
     */
    fun sweepPreview(taskLabel: String): SweepPreviewVO

    /**
     * 巡检锁当前是否被占着。手动触发前用它给出「上一轮还在跑」的提示。
     *
     * **只用于展示**：与真正的 acquire 之间有竞态，互斥仍由巡检自己的 SETNX 保证。
     */
    fun isSweepRunning(taskLabel: String): Boolean
}
