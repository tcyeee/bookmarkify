# Bookmarkify Scraper — M0 Demo Design

**Date:** 2026-04-18  
**Milestone:** M0 — 验证可行性  
**Scope:** 用 spider-rs 验证花瓣 + 知乎可被抓取和截图，结果保存到本地

---

## 目标

验证 spider-rs（基于 CDP + stealth）能否：

1. 绕过花瓣、知乎的反爬机制，成功渲染页面
2. 提取 title / description / og:image 元数据
3. 截图并保存到本地 `screenshots/` 目录

M0 的结论直接决定 M2（Module B）是否沿用 spider-rs 路线。

---

## 项目结构

```
bookmarkify-scrapper/
├── Cargo.toml              # workspace root
├── Cargo.lock
├── .gitignore
├── crates/
│   └── scraper-demo/       # M0 验证 binary
│       ├── Cargo.toml
│       └── src/
│           └── main.rs
└── screenshots/            # 截图输出目录（gitignored）
```

后续 M1/M2 在 `crates/` 下新增 `module-a` 和 `module-b`，不改动此结构。

---

## 依赖

```toml
# crates/scraper-demo/Cargo.toml
[dependencies]
spider = { version = "2", features = ["chrome", "chrome_stealth"] }
tokio = { version = "1", features = ["full"] }
serde_json = "1"
```

---

## 执行逻辑

```
目标 URL 列表：
  - https://huaban.com/
  - https://www.zhihu.com/

for url in targets:
  1. 初始化 spider Website，启用 chrome_stealth
  2. 抓取单页（不递归爬取）
  3. 从渲染后的 HTML 提取：
       - <title>
       - <meta name="description">
       - <meta property="og:image">
       - <meta property="og:description">（fallback）
  4. 截图 → 保存 screenshots/{domain}.png
  5. 打印 JSON 到 stdout：
     {
       "url": "...",
       "title": "...",
       "description": "...",
       "og_image": "...",
       "screenshot": "screenshots/huaban.png",
       "anti_crawler_bypassed": true
     }
```

---

## 成功标准

| 检查项 | 期望结果 |
|--------|----------|
| 花瓣页面 title | 非空、非反爬拦截页标题 |
| 知乎页面 title | 非空、非反爬拦截页标题 |
| 截图文件 | 两个 PNG 文件存在且大小 > 10KB |
| og:image | 至少一个站点返回非空值 |

---

## 风险与边界

- Chrome 二进制需在运行环境可用；本地 Mac 开发直接使用系统 Chrome
- `--no-sandbox` 问题留到云服务器部署阶段（M4）再处理
- M0 不处理并发、超时重试、内存泄漏，这些在 M2 阶段解决
- 截图不上传 OSS，仅本地验证

---

## 后续里程碑衔接

- **M0 通过** → 继续 M1（axum 框架 + Layer 1 轻量解析）
- **M0 失败（spider-rs 无法绕过反爬）** → 评估切换到 headless_chrome 或 chromiumoxide
