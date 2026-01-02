package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BacGradientVO
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity

/**
 * 用户渐变背景 Service
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
interface IBackgroundGradientService : IService<BackgroundGradientEntity> {
    fun defaultGradientBackgrounds(): Array<BacGradientVO>
    fun userGradientBackgrounds(uid: String): Array<BacGradientVO>
    fun deleteUserGradient(uid: String, id: String): Boolean
    fun updateUserGradient(uid: String, params: GradientConfigParams): Boolean
}
