package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.entity.ScrapperErrorCodeCountVO
import top.tcyeee.bookmarkify.entity.ScrapperFailedHostParams
import top.tcyeee.bookmarkify.entity.ScrapperFailedHostVO
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.IScrapperCallLogService
import top.tcyeee.bookmarkify.server.asset.SiteAssetResolver
import java.time.LocalDateTime

/**
 * scrapper 调用日志 Service 实现
 */
@Service
class ScrapperCallLogServiceImpl(
    private val siteAssetResolver: SiteAssetResolver,
) : IScrapperCallLogService, ServiceImpl<ScrapperCallLogMapper, ScrapperCallLogEntity>() {

    override fun adminListAll(params: ScrapperCallLogSearchParams): IPage<ScrapperCallLogVO> {
        val page = baseMapper.selectPage(params.toPage(), params.toWrapper())
            .convert { ScrapperCallLogVO(it) }

        // 图标按**本页**的域名批量补，一次查询覆盖整页 —— 逐行去查就是 N+1，而这一页默认 50 行。
        // 补不到的行保持 null，前端落本地兜底图；这里绝不能退回 `https://<host>/favicon.ico`
        val faviconByHost = siteAssetResolver.siteFaviconByHost(page.records.map { it.urlHost })
        page.records.forEach { it.faviconUrl = faviconByHost[it.urlHost] }
        return page
    }

    override fun failedHostRanking(params: ScrapperFailedHostParams): List<ScrapperFailedHostVO> {
        val safe = params.sanitized()
        val from = LocalDateTime.now().minusDays(safe.days.toLong())

        val rows = baseMapper.failedHostRanking(
            from = from,
            minFailures = safe.minFailures,
            sortField = safe.sortField,
            limit = safe.limit,
        )
        if (rows.isEmpty()) return emptyList()

        // 两项补充信息都按**本页返回的域名**批量查，一次覆盖整榜。逐行去查就是 N+1，
        // 而榜单默认 50 行、上限 500 行
        val hosts = rows.map { it.urlHost }
        val breakdownByHost = baseMapper.errorCodeBreakdown(from, hosts)
            .groupBy { it.urlHost }
        val faviconByHost = siteAssetResolver.siteFaviconByHost(hosts)

        return rows.map { row ->
            ScrapperFailedHostVO(
                urlHost = row.urlHost,
                totalCalls = row.totalCalls,
                failedCalls = row.failedCalls,
                failedUrls = row.failedUrls,
                failedDurationMs = row.failedDurationMs,
                totalDurationMs = row.totalDurationMs,
                lastFailedAt = row.lastFailedAt,
                lastSuccessAt = row.lastSuccessAt,
                lastFailedUrl = row.lastFailedUrl,
                lastErrorMsg = row.lastErrorMsg,
                lastErrorCode = row.lastErrorCode,
                lastTargetStatus = row.lastTargetStatus,
                lastLayerUsed = row.lastLayerUsed,
                errorBreakdown = breakdownByHost[row.urlHost].orEmpty()
                    .map { ScrapperErrorCodeCountVO(errorCode = it.errorCode, count = it.count) },
                // 补不到的保持 null，前端落本地兜底图。这个页面上失效域名的密度是全后台最高的，
                // 绝不能退回按 host 拼 favicon.ico —— 那等于让管理员的浏览器挨个去连一批连我方
                // 抓取服务都拒掉的站点。同 ScrapperCallLogVO.faviconUrl
                faviconUrl = faviconByHost[row.urlHost],
            )
        }
    }
}
