package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.IScrapperCallLogService
import top.tcyeee.bookmarkify.server.asset.SiteAssetResolver

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
}
