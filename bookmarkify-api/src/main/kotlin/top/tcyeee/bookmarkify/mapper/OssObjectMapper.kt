package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity

/**
 * OSS 对象账本 Mapper
 */
@Mapper
interface OssObjectMapper : BaseMapper<OssObjectEntity> {

    /**
     * 按 `object_key` 幂等入账：已存在则原样跳过，返回受影响行数 0。
     *
     * **为什么必须是 `ON CONFLICT` 而不是"先查再插"。** 抓取跑在线程池上，同一个 key 完全可能
     * 被两次并发抓取同时写入；"先查再插"的窄窗口一旦撞上唯一索引，在 PostgreSQL 里整个事务会
     * 直接进入 aborted 状态 —— 后续语句无论怎么 `runCatching` 都会跟着失败。
     * `SiteAssetWriter.fillMissingAssets` 的注释里详细记过这个坑，这里不重蹈。
     *
     * `DO NOTHING` 而不是 `DO UPDATE`：当前 key 是源 URL 哈希，重抓时同一个 key 底下的字节
     * **可能已经变了**，理论上该更新 size/hash。但 `DO UPDATE` 会去动 `content_hash`，而那一列
     * 将来要收紧成唯一索引，在这里埋一条会冲突的更新路径得不偿失。事实的纠偏交给 P2 的对账任务，
     * 它本来就要遍历全桶。
     */
    @Insert(
        """
        INSERT INTO oss_object
            (id, object_key, content_hash, addressing, source, size, mime,
             width, height, is_vector, environment, create_time, state)
        VALUES
            (#{id}, #{objectKey}, #{contentHash}, #{addressing}, #{source}, #{size}, #{mime},
             #{width}, #{height}, #{isVector}, #{environment}, #{createTime}, #{state})
        ON CONFLICT (object_key) DO NOTHING
        """
    )
    // 单参数且不加 @Param：MyBatis 直接按属性名解析 #{objectKey} 这类占位符，不需要 et. 前缀
    fun insertIgnore(entity: OssObjectEntity): Int
}
