# M0 验证结果

**日期:** 2026-04-18  
**结论:** PARTIAL PASS

---

## 输出

```json
[
  {
    "anti_crawler_bypassed": true,
    "description": "花瓣网,设计师寻找灵感的平台!这里汇聚海量高清创意图片与设计素材,通过智能采集工具助你高效整理灵感.无论是保存优质素材,规划设计方案,还是分享个人创意收藏,花瓣网都能满足设计师需求,让灵感采集与创作落地无缝衔接。",
    "og_image": "https://st0.dancf.com/static/02/202306090204-51f4.png",
    "screenshot": null,
    "title": "花瓣网 - 灵感之源，花瓣之间（创意图片大全、设计灵感图库、高清图片素材）",
    "url": "https://huaban.com/"
  },
  {
    "error": "no page",
    "url": "https://www.zhihu.com/"
  }
]
```

## 截图文件大小

```
（screenshots/ 目录为空 — screenshot_bytes 未返回数据）
```

---

## 逐项对照

| 检查项 | 期望 | 实际 | 状态 |
|--------|------|------|------|
| 花瓣 title | 非空、非拦截页 | "花瓣网 - 灵感之源…" | ✅ PASS |
| 花瓣 description | 非空 | 返回完整描述 | ✅ PASS |
| 花瓣 og:image | 非空 | https://st0.dancf.com/… | ✅ PASS |
| 知乎 title | 非空、非拦截页 | 页面未返回（get_pages 为空） | ❌ FAIL |
| 花瓣截图文件 | > 10KB | screenshot_bytes 为空 | ❌ FAIL |
| 知乎截图文件 | > 10KB | 页面未返回，无截图 | ❌ FAIL |

---

## 发现与分析

### ✅ 花瓣（huaban.com）— 元数据绕过成功

spider-rs + Chrome stealth 成功绕过花瓣反爬，返回真实页面元数据（title/description/og_image 均正确）。`anti_crawler_bypassed = true`。

### ❌ 知乎（zhihu.com）— 不稳定

知乎结果不稳定：
- Task 2 运行时返回"安全验证 - 知乎"（部分绕过，得到 description 但标题为验证页）
- Task 4 运行时 `get_pages()` 直接返回空（完全未拿到页面）

知乎的反爬力度显著强于花瓣，spider-rs stealth 当前配置无法可靠绕过。

### ❌ 截图 — `screenshot_bytes` 未返回数据

`ScreenShotConfig` + `with_screenshot()` 配置后，`page.screenshot_bytes` 字段始终为 `None`。可能原因：
1. spider-rs v2 的截图 API 需要不同的配置参数
2. `save: false` 时 bytes 可能不填充到 page 结构
3. Chrome 进程在 stealth 模式下截图需要额外权限设置

---

## 结论

| 能力 | 结论 |
|------|------|
| 花瓣元数据提取 | ✅ spider-rs 可用 |
| 知乎元数据提取 | ❌ 当前配置不可靠，需要更强 stealth 策略 |
| 截图功能 | ❌ spider-rs screenshot_bytes API 未生效，需进一步调试 |

---

## 下一步建议

1. **截图问题**：检查 spider-rs v2 截图文档，尝试 `save: true` 模式或直接使用 CDP `Page.captureScreenshot`
2. **知乎绕过**：评估增加 `with_wait_for_idle(true)`、随机化 User-Agent、增加等待时间等策略
3. **M1 决策**：花瓣绕过成功，Module A（轻量 reqwest）可以处理花瓣。知乎需要 Module B headless 策略加强
4. **是否继续 spider-rs**：花瓣证明 spider-rs 基本可用，知乎和截图问题建议在 M2 专项解决，不阻塞 M1
