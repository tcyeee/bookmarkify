package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.mapper.BackgroundGradientMapper
import top.tcyeee.bookmarkify.server.IBackgroundGradientService

/**
 * 用户渐变背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundGradientServiceImpl : IBackgroundGradientService,
    ServiceImpl<BackgroundGradientMapper, BackgroundGradientEntity>()
