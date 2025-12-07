package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.ImageBackgroundEntity
import top.tcyeee.bookmarkify.mapper.ImageBackgroundMapper
import top.tcyeee.bookmarkify.server.IImageBackgroundService

/**
 * 用户图片背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class ImageBackgroundServiceImpl : IImageBackgroundService,
    ServiceImpl<ImageBackgroundMapper, ImageBackgroundEntity>()
