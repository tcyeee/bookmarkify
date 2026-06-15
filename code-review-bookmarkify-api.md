# bookmarkify-api 代码审查报告

**审查范围：** `bookmarkify-api/`（相对于 main 分支的最新差异）  
**审查日期：** 2026-05-27  
**审查方法：** 三角度独立扫描 + 单票验证（偏召回）

---

## 总览

本次审查对 `bookmarkify-api` 最新提交与 main 分支的差异进行了全量扫描，共发现 **10 项问题**，其中：

| 等级 | 数量 | 问题 |
|------|------|------|
| 高危（已确认） | 2 | SSRF 重定向绕过、登录限流可完全绕过 |
| 中危（已确认） | 2 | 删除文件夹孤儿节点、Redis 异常误触发限流 |
| 中危（可信） | 4 | DNS 重绑定 TOCTOU、copy() 外键悬空、evict 竞态、checkAll 消息洪泛 |
| 低危（可信） | 2 | WebSocket token URL 解码缺失、moveNode TOCTOU |

---

## 高危问题

### F-01 · SSRF：HTTP 重定向绕过私网地址校验

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/utils/OssUtils.kt:167`  
**状态：** 已确认

**问题描述**

`restoreImg()` 在调用 `openConnection()` 前会通过 `InetAddress.getByName(host)` 解析 IP，并拦截回环/链路本地/RFC1918 私网地址。但 Java 的 `HttpURLConnection` 默认**自动跟随 301/302 重定向**，且代码中既未调用 `instanceFollowRedirects = false`，也未调用静态的 `HttpURLConnection.setFollowRedirects(false)`。

攻击者只需在自己控制的公网服务器上返回：

```
HTTP/1.1 301 Moved Permanently
Location: http://169.254.169.254/latest/meta-data/
```

第一次 DNS 解析（校验阶段）命中公网 IP，通过所有检查；`openConnection()` 发起真实连接后跟随重定向，最终请求落到云主机元数据接口或内网服务，SSRF 防护被完全绕过。

**攻击路径**

```
攻击者提交恶意 URL
  → OssUtils.restoreImg()
  → InetAddress 解析公网 IP → 通过私网校验
  → parsedUrl.openConnection() 发起 HTTP 请求
  → 服务器返回 301 → Location: http://127.0.0.1:7001/actuator/env
  → HttpURLConnection 自动跟随
  → 获取 Spring 环境变量（含数据库密码、OSS 密钥等）
```

**修复建议**

```kotlin
val connection = runCatching { parsedUrl.openConnection() }
    .getOrElse { throw CommonException(ErrorType.E223, it.message) }
    .apply {
        connectTimeout = 5000
        readTimeout = 5000
        // 禁止自动跟随重定向，防止跳转到私网地址
        (this as? HttpURLConnection)?.instanceFollowRedirects = false
    }
// 如需支持重定向，需手动读取 Location 并重新走 SSRF 校验逻辑
```

---

### F-02 · 登录限流可被 X-Forwarded-For 完全绕过

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/config/throttle/ThrottleAspect.kt:33`  
**状态：** 已确认

**问题描述**

本次提交为 `/auth/login` 添加了 `@Throttle` 注解，意图防止暴力破解。`ThrottleAspect` 在用户未登录时（`BaseUtils.uid()` 抛出异常）会回退到客户端 IP 作为限流 key：

```kotlin
val subject = runCatching { BaseUtils.uid() }.getOrElse { "ip:${clientIp()}" }
```

而 `clientIp()` 的实现**无条件信任** `X-Forwarded-For` 头的第一个值：

```kotlin
return req.getHeader("X-Forwarded-For")
    ?.split(",")?.firstOrNull { it.isNotBlank() }?.trim()
    ?: req.remoteAddr ?: "unknown"
```

攻击者只需在每次请求时轮换 `X-Forwarded-For`：

```
X-Forwarded-For: 1.2.3.1  → key: throttle:ip:1.2.3.1:LoginController.login → SETNX 首次，成功
X-Forwarded-For: 1.2.3.2  → key: throttle:ip:1.2.3.2:LoginController.login → SETNX 首次，成功
X-Forwarded-For: 1.2.3.3  → ...
```

每次请求都产生一个新 key，SETNX 永远返回 true，限流彻底失效，可无限次尝试密码。

**修复建议**

对未认证请求，应使用**网络层真实 IP**（即 `remoteAddr` 或仅信任已知可信代理追加的最后一段 XFF）：

```kotlin
private fun clientIp(): String {
    val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        ?: return "unknown"
    val req = attrs.request
    // 仅在明确配置了可信代理时才读取 XFF；否则直接使用 remoteAddr
    return req.remoteAddr ?: "unknown"
}
```

如果部署在 Nginx 反向代理后面，应在 Nginx 侧设置 `set_real_ip_from` 并使用 `$realip_remote_addr`，或在 Spring Boot 中配置 `server.forward-headers-strategy=native` 并限定可信代理 IP 段。

---

## 中危问题（已确认）

### F-03 · 删除文件夹节点时子节点成为孤儿，下次加载重新出现在根目录

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/server/impl/UserLayoutNodeServiceImpl.kt:166`  
**状态：** 已确认

**问题描述**

`deleteByIds()` 按 uid 过滤后批量删除布局节点，但**未检查被删节点是否为 `BOOKMARK_DIR`**，也不级联删除其子节点：

```kotlin
ownedIds.forEach { id ->
    removeById(id)                                    // 删除文件夹本身
    bookmarkUserLinkService.deleteOneByNodeId(id, uid) // 删除关联书签链接
    // ❌ 未查询并删除 parentId = id 的子节点
}
```

而 `layout()` → `nodeStructure()` 中存在如下逻辑：

```kotlin
val parent = allNodes[node.parentId] ?: root   // 找不到父节点则挂到 root
```

**结果：** 用户删除一个含有书签的文件夹后，文件夹消失，但其中的书签节点仍留在数据库，`parentId` 指向已删除的文件夹 ID。下次调用 `layout()` 时，这些孤儿节点被静默提升到根目录，"已删除"的书签重新出现在桌面，且无法通过正常流程再次删除（因为它们的 `bookmarkUserLink` 记录已被清除，显示为残缺节点）。

**修复建议**

在 `deleteByIds()` 中增加递归/级联删除：

```kotlin
ownedIds.forEach { id ->
    // 若为文件夹，先递归删除子节点
    val children = ktQuery()
        .eq(UserLayoutNodeEntity::parentId, id)
        .eq(UserLayoutNodeEntity::uid, uid)
        .list().map { it.id }
    if (children.isNotEmpty()) {
        deleteByIds(children, uid)  // 递归处理子节点
    }
    removeById(id)
    bookmarkUserLinkService.deleteOneByNodeId(id, uid)
}
```

---

### F-04 · Redis 连接异常时 setIfAbsent 返回 null，被误判为已触发限流

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/config/throttle/ThrottleAspect.kt:41`  
**状态：** 已确认

**问题描述**

Spring Data Redis 的 `setIfAbsent()` 返回类型为 `Boolean?`（可空）。当 Redis 连接异常时，该方法可能返回 `null` 而非抛出异常。代码检查如下：

```kotlin
val success = stringRedisTemplate.opsForValue()
    .setIfAbsent(key, "1", throttle.seconds.toLong(), TimeUnit.SECONDS)

if (success == true) {
    // 正常放行
} else {
    // ❌ success == null（Redis 异常）也走到这里
    val expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS) // 返回 -2
    val msg = if (expire > 0) "请等待 ${expire}s 后重试" else "请求过于频繁，请稍后再试"
    throw CommonException(ErrorType.E107, msg)
}
```

Redis 故障期间，所有被 `@Throttle` 装饰的接口（包括登录、书签添加等）都会返回 `E107` 错误，用户无法正常使用功能，可用性受损。

**修复建议**

```kotlin
val success = runCatching {
    stringRedisTemplate.opsForValue()
        .setIfAbsent(key, "1", throttle.seconds.toLong(), TimeUnit.SECONDS)
}.getOrNull()

when (success) {
    true -> { /* 放行 */ }
    false -> {
        val expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)
        val msg = if (expire > 0) "请等待 ${expire}s 后重试" else "请求过于频繁，请稍后再试"
        throw CommonException(ErrorType.E107, msg)
    }
    null -> {
        // Redis 异常：降级放行，避免误伤正常用户
        log.warn("[Throttle] Redis unavailable for key={}, skipping throttle check", key)
    }
}
```

---

## 中危问题（可信）

### F-05 · SSRF：DNS 重绑定 TOCTOU

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/utils/OssUtils.kt:152`  
**状态：** 可信

**问题描述**

`restoreImg()` 的 SSRF 防护存在经典的 TOCTOU（检查时间 vs 使用时间）竞态。DNS 解析在校验阶段发生一次，连接阶段由 JVM 再次独立解析：

```
时刻 T1：InetAddress.getByName("evil.example.com") → 1.2.3.4（公网 IP）→ 通过校验
时刻 T2：攻击者将 DNS TTL 设为 0，将 A 记录改为 127.0.0.1
时刻 T3：parsedUrl.openConnection() → JVM 重新解析 → 127.0.0.1 → 连接内网
```

攻击成功需要在毫秒级窗口内完成 DNS 切换，实施难度较高，但在 Java 不缓存短 TTL DNS 记录的情况下是可行的。

**修复建议**

解析后将 IP 直接替换进 URL（即连接到已验证的 IP，而非主机名），或使用 IP 白名单 + 域名黑名单双重策略：

```kotlin
// 将 URL 中的主机名替换为已验证的 IP，避免二次解析
val safeUrl = URL(parsedUrl.protocol, addr.hostAddress, parsedUrl.port, parsedUrl.file)
val connection = safeUrl.openConnection()
    .apply {
        connectTimeout = 5000
        readTimeout = 5000
        (this as? HttpURLConnection)?.instanceFollowRedirects = false
        // 同时设置 Host header，以满足虚拟主机要求
        setRequestProperty("Host", host)
    }
```

---

### F-06 · copy() 保留源用户的 layoutNodeId，导致目标用户书签不可见

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkUserLinkServiceImpl.kt:25`  
**状态：** 可信（当前为死代码，无调用方）

**问题描述**

`copy()` 使用 Kotlin 数据类的 `copy()` 只覆盖了 `id` 和 `uid`，其余字段原样复制：

```kotlin
val copies = source.map {
    it.copy(id = cn.hutool.core.util.IdUtil.fastUUID(), uid = targetUid)
    // ❌ layoutNodeId 仍指向 sourceUid 的布局节点
}
```

`layout()` 通过以下方式关联书签：

```kotlin
val bookmarkMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.layoutNodeId!! }
// 再以布局节点自身 id 查找：bookmarkMap[node.id]
```

若 `layoutNodeId` 指向的是源用户的节点 ID，目标用户的布局节点中不存在该 ID，`bookmarkMap[it.id]` 返回 null，书签在桌面上永久不可见。

虽然当前 `IBookmarkUserLinkService.copy()` 无调用方，但该接口保留在代码中，一旦接入匿名会话合并逻辑即触发此 bug。

**修复建议**

在 `copy()` 时同步复制布局节点，或在调用方确保先完成 `UserLayoutNode` 的复制，再调用此方法（并传入节点 ID 映射表来修正 `layoutNodeId`）。

---

### F-07 · evictStaleEntries 与 isThrottled 存在竞态，可允许额外请求绕过窗口限制

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/config/filter/PreRequestFilter.kt:71`  
**状态：** 可信

**问题描述**

`evictStaleEntries()` 和 `isThrottled()` 对同一个 `ConcurrentLinkedDeque` 使用 `synchronized` 互斥，但竞态窗口存在于锁释放之后：

```
线程 A（evict）：synchronized(dequeD) → deque 已空 → 释放锁 → 调用 iter.remove()
线程 B（isThrottled）：computeIfAbsent → 获得旧 dequeD 引用 → 等待锁
线程 C（isThrottled）：computeIfAbsent → A 已 remove，创建新 dequeD2 → 插入 WINDOW_MAP
```

此时 B 和 C 持有不同的 deque 实例，各自进行独立的 `size >= MAX_REQUESTS_PER_WINDOW` 检查，实际并发限制加倍。在每 60 秒一次的清理周期中，每个 token 最多可额外漏过 1 个请求，影响较小但存在。

**修复建议**

将 `iter.remove()` 移入 `synchronized(deque)` 块内，在释放锁之前完成删除：

```kotlin
synchronized(deque) {
    while (true) {
        val head = deque.peekFirst() ?: break
        if (now - head > WINDOW_MILLIS) deque.pollFirst() else break
    }
    if (deque.isEmpty()) {
        iter.remove()  // 在锁内删除，消除竞态窗口
    }
}
```

---

### F-08 · checkAll() 修复后首次运行可能触发 Kafka 消息洪泛

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt:102`  
**状态：** 可信（运维风险）

**问题描述**

原代码 `.lt(BookmarkEntity::verifyFlag, false)` 在 PostgreSQL 中生成 `verify_flag < false`，对布尔列语义无效，实际上 `checkAll()` 是一个**无操作的定时任务**，从未真正重新抓取任何书签。

本次修复为 `.eq(BookmarkEntity::verifyFlag, false)`，语义正确。但如果生产数据库中积累了大量 `verifyFlag = false` 且 `updateTime` 超过 1 天的书签，**第一次部署后的定时触发**会将它们全部推入 Kafka 的 `BOOKMARKIFY` topic，可能导致：

- Scraper 服务（`bookmarkify-scrapper`）被大量并发任务压垮
- Kafka 消费者组 lag 急剧增大
- 正常新书签的解析被严重延迟

**建议**

上线前执行以下查询评估存量：

```sql
SELECT COUNT(*) FROM bookmarkify.bookmark
WHERE verify_flag = false
  AND update_time < NOW() - INTERVAL '1 day'
  AND deleted = 0;
```

如数量超过数百条，建议分批处理或临时调低定时任务频率，待消化完积压后恢复。

---

## 低危问题（可信）

### F-09 · WebSocket 握手 token 缺少 URL 解码，含特殊字符时鉴权失败

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/config/websocket/AuthHandshakeInterceptor.kt:22`  
**状态：** 可信

**问题描述**

新代码使用 `UriComponentsBuilder.fromUri(request.uri).build()` 解析 query 参数：

```kotlin
val token = UriComponentsBuilder.fromUri(request.uri).build()
    .queryParams.getFirst("token")
    ?.takeIf { it.isNotBlank() }
    ?: throw CommonException(ErrorType.E201)
```

`.build()` 不触发解码，若 token 中含有 `+`（Base64 中常见）或 `=` 等字符被 URL 编码为 `%2B` / `%3D`，`getFirst("token")` 返回的是编码后的字符串。`StpKit.USER.getLoginIdByToken("%2B...")` 查不到会话，正常用户被拒绝建立 WebSocket 连接。

**修复建议**

```kotlin
val token = UriComponentsBuilder.fromUri(request.uri).build(true)  // build(true) = 已编码，触发解码
    .queryParams.getFirst("token")
    ?.takeIf { it.isNotBlank() }
    ?: throw CommonException(ErrorType.E201)
```

---

### F-10 · moveNode 目标文件夹归属校验与实际移动之间存在 TOCTOU

**文件：** `src/main/kotlin/top/tcyeee/bookmarkify/server/impl/UserLayoutNodeServiceImpl.kt:83`  
**状态：** 可信（需高并发才能触发）

**问题描述**

`moveNode()` 先校验目标文件夹是否属于当前用户，再执行移动。两步之间没有行级锁：

```kotlin
// 步骤 1：校验
ktQuery().eq(id, params.dirNodeId).eq(uid, uid).eq(type, BOOKMARK_DIR).one()
    ?: throw CommonException(ErrorType.E102)

// 步骤 2：（间隔若干行）
ktUpdate().eq(id, params.nodeId).set(parentId, params.dirNodeId).update()
```

若另一请求在步骤 1 通过后、步骤 2 执行前删除了该文件夹，节点会被移入一个已不存在的 `parentId`，产生孤儿节点——与 F-03 相同的后果，但触发路径不同。

**修复建议**

将整个 `moveNode()` 的数据库操作置于 `SELECT ... FOR UPDATE` 或使用乐观锁版本号；或在步骤 2 的 `ktUpdate()` 上增加 `eq(UserLayoutNodeEntity::uid, uid)` 校验，确保移动后在同一事务内可验证父节点仍然存在。

---

## 附：本次修改中的亮点（正向反馈）

以下改动质量较高，值得保留：

| 改动 | 说明 |
|------|------|
| `SessionManager` 使用 `ConcurrentWebSocketSessionDecorator` | 解决了并发 send 导致的 `TEXT_PARTIAL_WRITING` 异常 |
| `SessionManager.remove()` 的 ID 对比逻辑 | 正确处理了"新连接进来后误删"的竞态，逻辑严谨 |
| `deleteOneByNodeId` / `updateOne` 加入 `uid` 参数 | 修复了原来无 uid 过滤导致的越权删改漏洞 |
| `BaseUtils.uid()` 改抛 `CommonException` | 替代了裸 `NullPointerException`，错误码更可观测 |
| `LoginController` 加 `@Throttle` | 方向正确，但见 F-02 |
| `KafkaMessageListener` 日志改为结构化参数 | 避免了字符串拼接的性能开销，且将异常对象作为最后参数传入，MDC 可正确记录堆栈 |
| `checkAll()` 从 `.lt` 改为 `.eq` | 修复了一个长期静默的无操作 bug，但需注意 F-08 的运维风险 |
| 移除 `changePhone` / `checkPhone` / `changeMail` | 删除了绕过验证码直接改库的危险接口（`checkPhone` 甚至将整数验证码写入 email 字段） |

---

*报告由 Claude Code 自动生成，建议人工复核高危项后再合并。*
