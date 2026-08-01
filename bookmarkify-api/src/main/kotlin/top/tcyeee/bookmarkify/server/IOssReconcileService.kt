package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.OssReconcileReport

/**
 * OSS 对账：把**桶里的事实**与**账本和引用方**对齐。
 *
 * 这是整套文件治理方案里不可替代的一环，原因只有一句：
 *
 * > 账本与桶之间没有事务。
 *
 * scrapper 先 PUT 对象、再由 API 落行，中间任何一步失败（抓取事务回滚、超时、唯一索引冲突）
 * 都会在桶里留下一个库里无人知晓的对象。**只建账本不做对账，等于把问题换个地方放** ——
 * 纯数据库的手段永远看不见这类孤儿。
 *
 * 对账做四件事，顺序不可换：
 * 1. 遍历桶 → 补记账本里缺的行（`LEGACY`），刷新 `last_seen_at`
 * 2. 扫描全部引用方表 → 刷新 `last_ref_at`
 * 3. 桶里没有、账本里有的 → 标 `DELETED`
 * 4. 桶里有、但没人引用的 → 标 `ORPHAN`
 *
 * **当前阶段只标记，不删除任何东西。**
 */
interface IOssReconcileService {

    /** 跑一轮对账，返回本轮统计。失败不抛异常，返回带 `errorMsg` 的报告 */
    fun reconcile(): OssReconcileReport
}
