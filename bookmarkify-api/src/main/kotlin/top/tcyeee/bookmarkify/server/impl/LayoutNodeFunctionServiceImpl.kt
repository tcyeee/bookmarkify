package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.LayoutNodeFunctionEntity
import top.tcyeee.bookmarkify.mapper.LayoutNodeFunctionMapper
import top.tcyeee.bookmarkify.server.ILayoutNodeFunctionService

@Service
class LayoutNodeFunctionServiceImpl : ILayoutNodeFunctionService,
    ServiceImpl<LayoutNodeFunctionMapper, LayoutNodeFunctionEntity>() {
    override fun findByUid(uid: String): List<LayoutNodeFunctionEntity> =
        ktQuery().eq(LayoutNodeFunctionEntity::uid, uid).list()
}

