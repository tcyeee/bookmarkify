package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.common.BackgroundConfig
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.uploadAvatar
import top.tcyeee.bookmarkify.utils.uploadBackground

/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl(
    private val projectConfig: ProjectConfig,
    private val userBackgroundLinkService: UserBackgroundLinkServiceImpl,
    private val imageBackgroundService: ImageBackgroundServiceImpl,
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun getByDeviceId(deviceId: String): UserEntity? {
        return ktQuery().eq(UserEntity::deviceId, deviceId).one()
    }

    override fun createUserByDeviceId(deviceId: String): UserEntity {
        val userEntity = UserEntity(deviceId)
        this.save(userEntity)
        return userEntity
    }

    override fun updateAvatar(uid: String, file: MultipartFile): String {

        // 保存文件
        val fileName = uploadAvatar(file, uid, projectConfig.imgPath)

        // 更新用户头像路径
        ktUpdate().eq(UserEntity::id, uid).set(UserEntity::avatarPath, fileName).update()

        // 返回相对路径
        return fileName
    }

    override fun uploadBackground(uid: String, file: MultipartFile): String {
        // 保存文件
        val fileName = uploadBackground(file, uid, projectConfig.imgPath)

        // 添加背景图片信息
//        imageBackgroundService.add()

        // 修改用户背景类型/ID
        userBackgroundLinkService.update()


        // 更新用户背景路径（兼容旧字段 backgroundPath）
        ktUpdate()
            .eq(UserEntity::id, uid)
            .set(UserEntity::backgroundPath, fileName)
            .update()

        // 返回相对路径
        return fileName
    }

    override fun userInfo(): UserInfoShow {
        val userEntity = getById(BaseUtils.uid()) ?: throw CommonException(ErrorType.E202)
        return UserInfoShow(userEntity, StpUtil.getTokenValue())
    }

    override fun updateInfo(params: UserInfoUptateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate()
            .eq(UserEntity::id, BaseUtils.uid())
            .set(UserEntity::nickName, params.nickName)
            .update()
    }

    override fun updateUsername(username: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, username).update()
    }

    override fun changePhone(phone: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::phone, phone).update()
    }

    override fun checkPhone(code: Int): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, code).update()
    }

    override fun changeMail(mail: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, mail).update()
    }

    override fun updateBackgroundConfig(config: BackgroundConfig): Boolean {
        val json = JSONUtil.toJsonStr(config)
        return ktUpdate()
            .eq(UserEntity::id, BaseUtils.uid())
            .set(UserEntity::backgroundConfigJson, json)
            .update()
    }

    override fun del(params: UserDelParams): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).eq(UserEntity::password, BaseUtils.pwd(params.password))
            .set(UserEntity::deleted, true).update()
    }
}