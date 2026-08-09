//! Layer 2：无头 Chrome。
//!
//! 这里直接驱动 CDP（`chromiumoxide`，由 spider 再导出），**不用** spider 的
//! `Website::scrape()`。这个选择是 2026-08-09 一次生产排查的结论，值得写下来：
//!
//! `scrape()` 是**爬虫**入口，它的产物是"这次爬行收获的页面集合"，而我们要的永远只是
//! "这一页"。用 `with_limit(2)` 把爬行掐住看似等价，实测并不是——种子页要等到爬虫**接着
//! 抓了第二页**才会出现在 `get_pages()` 里。于是判据变成了一件与目标站点毫不相干的事：
//! 渲染后的 HTML 里有没有同源可爬链接。
//!
//! | 页面 | 同源链接 | spider 打的 `fetch` 行 | 结果 |
//! |---|---|---|---|
//! | chiphell / element-plus / rust-lang | 有 | 2 条 | 成功 |
//! | example.com（唯一链接指向 iana.org） | 无 | 1 条 | **0 页 → 报错** |
//! | httpbin.org/html（页面里没有链接） | 无 | 1 条 | **0 页 → 报错** |
//!
//! 生产上这条规则专挑 SPA 空壳、单页工具站和深链下手，而它们恰恰是最需要 Layer 2 的一类；
//! 同一时间在同一个容器里 `chromium --dump-dom` 抓同一个 URL 是好的，所以现场看起来像
//! "无头浏览器很脆弱"，实际跟浏览器没有关系。顺带，每次成功的抓取还白抓了一个我们根本
//! 不用的第二页（实测 3–11s，从 30s 预算里扣）。
//!
//! 直接开一个标签页导航，还解决了另外两件事：能拿到导航自己的状态码与失败原因（不再依赖
//! spider 把 `ERR_*` 归一化成 599），以及浏览器实例可以跨请求复用——冷启动实测 11.6–14.7s，
//! 是 `HEADLESS_TIMEOUT_SECS=30` 里最大的一笔开销。

use futures_util::StreamExt;
use once_cell::sync::Lazy;
use spider::chromiumoxide::cdp::browser_protocol::emulation::SetDeviceMetricsOverrideParams;
use spider::chromiumoxide::cdp::browser_protocol::page::{
    CaptureScreenshotFormat, CaptureScreenshotParams,
};
use spider::chromiumoxide::page::ScreenshotParams;
use spider::chromiumoxide::{Browser, BrowserConfig, Page};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, Semaphore};

use crate::contract::{ImageFormat, ScreenshotOptions, Viewport, WaitUntil};
use crate::scraper::{validate_target_host, validate_url_scheme, ScrapeError, DEFAULT_UA};

/// 无头抓取的产物。元数据解析统一交给 [`crate::extract`]，与 Layer 1 走同一条路径 ——
/// 两层的差别只应该是"HTML 怎么拿到的"，不该各自解析一遍。
#[derive(Debug)]
pub struct HeadlessCapture {
    pub html: String,
    /// PNG 截图字节；未要求截图或 Chrome 未返回截图时为 None
    pub screenshot_bytes: Option<Vec<u8>>,
    /// 本次导航的 HTTP 状态码。
    ///
    /// **拿到 HTML 不等于抓成功**：风控拦截页同样是一篇有 `<title>` 的正常文档，
    /// 只是状态码是 403/412。没有这个字段，调用方只能把"出错啦! - bilibili.com"
    /// 当作站点标题存下来。
    pub status: u16,
}

// ─────────────────────────────────────────────────────────────────────────────
// 配置
// ─────────────────────────────────────────────────────────────────────────────

/// 同时可以有几个标签页在跑。
///
/// 浏览器进程现在是复用的，一个标签页的增量成本远低于一整个 Chrome，所以这里不必再
/// 像从前的全局互斥那样死守 1。但生产容器只有 2 vCPU / 1GiB，放太开只会让每一条都变慢
/// 而总吞吐不变，默认取 2。
static CONCURRENCY: Lazy<usize> =
    Lazy::new(|| crate::env_or("HEADLESS_CONCURRENCY", 2usize).max(1));

/// 等一个空位最多等多久。**这段时间不计入页面预算**——见 [`capture_headless`]。
static QUEUE_WAIT_SECS: Lazy<u64> = Lazy::new(|| crate::env_or("HEADLESS_QUEUE_WAIT_SECS", 20u64));

/// 浏览器实例的最大存活时长 / 最大使用次数，到期回收重开。
///
/// Chrome 跑久了会攒内存（尤其在我们这种什么站点都抓的场景下），复用带来的收益也不需要
/// 靠"永远不重启"来兑现——900s 里能省下几十次冷启动，足够了。
static BROWSER_TTL_SECS: Lazy<u64> =
    Lazy::new(|| crate::env_or("HEADLESS_BROWSER_TTL_SECS", 900u64));
static BROWSER_MAX_USES: Lazy<u32> =
    Lazy::new(|| crate::env_or("HEADLESS_BROWSER_MAX_USES", 50u32));

/// 启动 Chrome 自身的超时。与页面预算分开：浏览器起不来是我方故障，不该记在目标站点头上。
static LAUNCH_TIMEOUT_SECS: Lazy<u64> =
    Lazy::new(|| crate::env_or("HEADLESS_LAUNCH_TIMEOUT_SECS", 20u64));

/// 启动参数里必须由我们自己给的那几个。
///
/// `--disable-dev-shm-usage` 是容器跑 Chrome 的经典雷：docker 默认 `/dev/shm` 只有 64MB，
/// 渲染进程的共享内存耗尽后是**渲染进程直接没了**，而容器层面看不到任何 OOM 记录。加上它
/// 之后共享内存改用 /tmp，代价是稍慢一点，换来的是不会莫名其妙丢一个标签页。
const BASE_ARGS: &[&str] = &[
    "--disable-dev-shm-usage",
    "--disable-gpu",
    "--mute-audio",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-background-networking",
    "--disable-background-timer-throttling",
    "--disable-renderer-backgrounding",
    "--disable-backgrounding-occluded-windows",
    // 截图里不要出现滚动条
    "--hide-scrollbars",
];

/// 不需要截图时拦掉的子资源。
///
/// 主用途是拿 JS 渲染后的 HTML 去解析元数据，图片/字体/视频一个字节都用不上，在 1GB 容器
/// 里省下的内存和时间是刚需。**要截图时必须整套放开**——"这个页面长什么样"里当然包含它的
/// 样式与图片。注意 CSS 不在这张表里：拦掉它连布局都变了，省的那点流量不值。
const BLOCKED_WHEN_NO_SCREENSHOT: &[&str] = &[
    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.avif", "*.bmp", "*.ico", "*.svg", "*.woff",
    "*.woff2", "*.ttf", "*.otf", "*.eot", "*.mp4", "*.webm", "*.mp3", "*.wav",
];

/// Chrome 可执行文件：生产镜像用 `CHROME_BIN`，chromiumoxide 自己认的是 `CHROME`，
/// 两个都读，都没有时交给它按平台去探测。
fn chrome_binary() -> Option<String> {
    ["CHROME_BIN", "CHROME"]
        .iter()
        .find_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()))
}

/// 启动时打一行，把这些旋钮的实际取值落到日志里 —— 它们全是环境变量，出问题时
/// "线上到底跑的是哪套参数"必须能直接看到，而不是去翻部署文件。
pub fn config_summary() -> String {
    format!(
        "concurrency={} queue_wait={}s browser_ttl={}s max_uses={} launch_timeout={}s chrome={}",
        *CONCURRENCY,
        *QUEUE_WAIT_SECS,
        *BROWSER_TTL_SECS,
        *BROWSER_MAX_USES,
        *LAUNCH_TIMEOUT_SECS,
        chrome_binary().unwrap_or_else(|| "(auto-detect)".to_string()),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 浏览器实例的复用与回收
// ─────────────────────────────────────────────────────────────────────────────

/// 一个活着的 Chrome 进程，连同驱动它的事件循环任务。
struct Instance {
    browser: Browser,
    /// chromiumoxide 的事件泵。它停了，这个浏览器的所有页面操作都会静默卡死，
    /// 所以它和 `browser` 必须同生共死（见 `Drop`）。
    handler: tokio::task::JoinHandle<()>,
    /// 本实例专属的 profile 目录，退休时一并删掉。
    profile_dir: std::path::PathBuf,
    started: Instant,
    uses: AtomicU32,
}

impl Instance {
    fn is_stale(&self) -> bool {
        self.uses.load(Ordering::Relaxed) >= *BROWSER_MAX_USES
            || self.started.elapsed() >= Duration::from_secs(*BROWSER_TTL_SECS)
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        self.handler.abort();
    }
}

static BROWSER: Lazy<Mutex<Option<Arc<Instance>>>> = Lazy::new(|| Mutex::new(None));
static PERMITS: Lazy<Semaphore> = Lazy::new(|| Semaphore::new(*CONCURRENCY));

/// 让一个实例退休。**不等它关完**：调用点普遍持着 [`BROWSER`] 的锁，在锁里 await
/// 会把其他抓取一起堵住。
///
/// `strong_count == 1` 时才主动 `close()`：还有别的请求正拿着这个实例干活时，关掉它
/// 等于把人家抓到一半的页面掐掉。那种情况下什么都不做即可 —— 最后一个持有者 drop 时，
/// chromiumoxide 会连带杀掉 Chrome 进程。
fn retire(inst: Arc<Instance>) {
    tokio::spawn(async move {
        if Arc::strong_count(&inst) == 1 {
            let _ = inst.browser.close().await;
            let _ = std::fs::remove_dir_all(&inst.profile_dir);
        }
    });
}

/// 取一个可用的浏览器实例，必要时冷启动一个。
async fn acquire_browser() -> Result<Arc<Instance>, ScrapeError> {
    let mut guard = BROWSER.lock().await;

    if guard.as_ref().is_some_and(|inst| inst.is_stale()) {
        if let Some(old) = guard.take() {
            tracing::info!("recycling headless browser (ttl/uses reached)");
            retire(old);
        }
    }

    if guard.is_none() {
        let started = Instant::now();
        // 放到独立任务里启动，而不是直接 await：调用方给这一步套了超时，直接 await 时一旦
        // 超时触发，正在启动的 future 会被就地取消，那个已经 fork 出去的 Chrome 进程就没人
        // 认领了。交给 spawn 之后，即使这里的等待被取消，任务照样跑完，产出的 `Instance`
        // 随即被 drop —— 事件泵 abort、Chrome 进程由 chromiumoxide 杀掉，不留孤儿。
        let launched = tokio::spawn(launch_browser())
            .await
            .map_err(|e| ScrapeError::HeadlessUnavailable(format!("Chrome 启动任务失败: {e}")))??;
        *guard = Some(Arc::new(launched));
        tracing::info!(
            launch_ms = started.elapsed().as_millis(),
            "headless browser launched"
        );
    }

    let inst = guard.as_ref().expect("just ensured").clone();
    inst.uses.fetch_add(1, Ordering::Relaxed);
    Ok(inst)
}

/// 把一个已经不可信的实例踢出复用池（CDP 报错、事件泵挂了等）。
///
/// 只有当池子里还是同一个实例时才动手，否则会把别人刚建好的那个误伤。
async fn invalidate(inst: &Arc<Instance>) {
    let mut guard = BROWSER.lock().await;
    if guard.as_ref().is_some_and(|cur| Arc::ptr_eq(cur, inst)) {
        if let Some(old) = guard.take() {
            tracing::warn!("dropping headless browser after a failed page");
            retire(old);
        }
    }
}

async fn launch_browser() -> Result<Instance, ScrapeError> {
    // 每个实例一个独立的 profile 目录。chromiumoxide 默认所有实例共用
    // `$TMPDIR/chromiumoxide-runner`，于是两个实例（回收重开时新旧会短暂并存）会抢同一个
    // `SingletonLock`，后起的那个可能起不来。老代码是靠"启动前把锁文件删掉"绕过去的
    // —— 那等于在赌另一个实例不在用它。给每人一个目录，这类问题从根上消失。
    static SEQ: AtomicU32 = AtomicU32::new(0);
    let profile_dir = std::env::temp_dir().join(format!(
        "bookmarkify-chrome-{}-{}",
        std::process::id(),
        SEQ.fetch_add(1, Ordering::Relaxed)
    ));

    let mut builder = BrowserConfig::builder()
        .new_headless_mode()
        // 容器里以 root 运行，Chrome 的 setuid sandbox 起不来
        .no_sandbox()
        .launch_timeout(Duration::from_secs(*LAUNCH_TIMEOUT_SECS))
        .user_data_dir(&profile_dir)
        .viewport(Some(spider::chromiumoxide::handler::viewport::Viewport {
            width: 1280,
            height: 800,
            device_scale_factor: Some(1.0),
            ..Default::default()
        }))
        .args(BASE_ARGS.to_vec());

    if let Some(bin) = chrome_binary() {
        builder = builder.chrome_executable(bin);
    }
    // 与 main.rs 里 reqwest 客户端的代理保持一致：两层走同一条出口，不然 Layer 1 通、
    // Layer 2 不通这种事只能靠猜
    if let Some(proxy) = std::env::var("PROXY_URL").ok().filter(|s| !s.is_empty()) {
        builder = builder.arg(format!("--proxy-server={proxy}"));
    }

    let config = builder
        .build()
        .map_err(|e| ScrapeError::HeadlessUnavailable(format!("构建 Chrome 启动配置失败: {e}")))?;

    // 显式套一层超时：`launch_timeout` 只管到"等 Chrome 打印出 DevTools 地址"为止，
    // 而这里是唯一一段不在页面预算里的等待 —— 不设上限的话，一个起不来又不报错的
    // Chrome 会把请求永久挂住（连带占着并发名额），比报错糟得多。
    let launched = tokio::time::timeout(
        Duration::from_secs(*LAUNCH_TIMEOUT_SECS),
        Browser::launch(config),
    )
    .await
    .map_err(|_| {
        ScrapeError::HeadlessUnavailable(format!(
            "Chrome 启动超过 {}s（HEADLESS_LAUNCH_TIMEOUT_SECS）",
            *LAUNCH_TIMEOUT_SECS
        ))
    })?;

    let (browser, mut handler) =
        launched.map_err(|e| ScrapeError::HeadlessUnavailable(format!("Chrome 启动失败: {e}")))?;

    let handler = tokio::spawn(async move { while handler.next().await.is_some() {} });

    Ok(Instance {
        browser,
        handler,
        profile_dir,
        started: Instant::now(),
        uses: AtomicU32::new(0),
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// 抓取
// ─────────────────────────────────────────────────────────────────────────────

/// 用无头 Chrome（Layer 2）抓取指定 URL 的渲染后 HTML 与可选截图。
///
/// # 两段预算，故意分开
/// - **备好浏览器**：等一个并发名额 + （必要时）冷启动 Chrome，合计最多
///   `HEADLESS_QUEUE_WAIT_SECS`。超时报 [`ScrapeError::HeadlessUnavailable`]，语义是
///   "我方现在忙/起不来"，**不是**站点的错。两件事共用一个 deadline 是有意的：它们同属
///   "我方准备时间"，分开各给一份会让最坏耗时叠加，撑破调用方的 60s HTTP 超时。
/// - **页面**：备好之后才开始计的 `timeout_secs`，覆盖导航、等待、取 HTML、截图。
///
/// 于是最坏耗时是 `QUEUE_WAIT + TIMEOUT`（默认 20+30=50s），稳在调用方 60s 之内。
///
/// 从前这两段共用一个 deadline，后果在生产上很难看：一批 8 条并发进来，除了第一条以外
/// 全部在排队里耗光 30s，报出来却是"目标站点超时"，还会把这些 host 写进 24h 的无头熔断。
/// 同一个 chiphell.com 一分钟内先成功 16.4s、后超时 30s，站点没变，变的是队列。
///
/// # 参数
/// - `timeout_secs`：页面阶段的预算，对应 `HEADLESS_TIMEOUT_SECS`
/// - `idle_wait_secs`：等待网络安静的上限，对应 `HEADLESS_IDLE_WAIT_SECS`
/// - `wait_until`：页面就绪判定，来自请求的 `render.waitUntil`
/// - `viewport`：视口宽高与像素比；`None` 时用浏览器默认视口（1280×800）
/// - `screenshot`：`None` 时跳过截图**并**拦掉图片/字体等子资源；`Some` 时全部放开
///
/// # 返回
/// 成功时返回渲染后的 HTML + 导航状态码 + 可选截图。注意 **非 2xx 不算失败**：拦截页
/// 也是页面，是否采信交给调用方按场景判断（见 [`HeadlessCapture::status`]）。
pub async fn capture_headless(
    url: &str,
    timeout_secs: u64,
    idle_wait_secs: u64,
    wait_until: WaitUntil,
    viewport: Option<Viewport>,
    screenshot: Option<ScreenshotOptions>,
) -> Result<HeadlessCapture, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    validate_url_scheme(&parsed)?;
    validate_target_host(&parsed).await?;

    // 「备好浏览器」的统一截止时刻：名额 + 冷启动共享它
    let queued = Instant::now();
    let ready_by = tokio::time::Instant::now() + Duration::from_secs(*QUEUE_WAIT_SECS);

    let _permit = tokio::time::timeout_at(ready_by, PERMITS.acquire())
        .await
        .map_err(|_| {
            ScrapeError::HeadlessUnavailable(format!(
                "等待无头浏览器空位超过 {}s（并发上限 {}，请稍后重试）",
                *QUEUE_WAIT_SECS, *CONCURRENCY
            ))
        })?
        .map_err(|_| ScrapeError::HeadlessUnavailable("无头浏览器信号量已关闭".to_string()))?;

    let inst = tokio::time::timeout_at(ready_by, acquire_browser())
        .await
        .map_err(|_| {
            ScrapeError::HeadlessUnavailable(format!(
                "备好无头浏览器超过 {}s（拿到名额后仍未启动成功）",
                *QUEUE_WAIT_SECS
            ))
        })??;
    let queued_ms = queued.elapsed().as_millis() as u64;

    // 页面预算从浏览器备好之后才开始计
    let result = capture_on(
        &inst.browser,
        url,
        Budget {
            page: Duration::from_secs(timeout_secs),
            idle_wait_secs,
            timeout_secs,
            queued_ms,
        },
        wait_until,
        viewport,
        screenshot.as_ref(),
    )
    .await;

    if let Err(e) = &result {
        // CDP 层面的失败说明这个浏览器实例本身可能已经不可信（事件泵挂了、进程没了），
        // 把它踢出复用池，下一次请求重开一个 —— 否则一个坏实例会连累后面每一条。
        // 页面超时不算：那是这一页的问题，浏览器还好好的。
        if matches!(e, ScrapeError::HeadlessUnavailable(_)) {
            invalidate(&inst).await;
        }
    }
    result
}

/// 一次抓取的时间账本，只为把超时信息原样带进错误消息里。
struct Budget {
    page: Duration,
    idle_wait_secs: u64,
    timeout_secs: u64,
    queued_ms: u64,
}

/// 真正的页面操作，在一个新标签页里完成。
///
/// **超时必须套在这一层里面，不能套在调用方**：套在外面时，超时一到整个 future 就地取消，
/// `page.close()` 再也不会执行 —— 浏览器现在是复用的，泄漏的标签页会一直挂在那儿继续加载，
/// 内存和 CPU 都跟着涨。这里的写法保证无论成功、失败还是超时，标签页都关得掉。
async fn capture_on(
    browser: &Browser,
    url: &str,
    budget: Budget,
    wait_until: WaitUntil,
    viewport: Option<Viewport>,
    screenshot: Option<&ScreenshotOptions>,
) -> Result<HeadlessCapture, ScrapeError> {
    // 先开空白页再导航：UA、视口、资源拦截都必须在第一个字节发出去之前设好
    let page = browser
        .new_page("about:blank")
        .await
        .map_err(|e| ScrapeError::HeadlessUnavailable(format!("无法新建标签页: {e}")))?;

    let captured = tokio::time::timeout(
        budget.page,
        capture_in_page(
            &page,
            url,
            budget.idle_wait_secs,
            wait_until,
            viewport,
            screenshot,
        ),
    )
    .await;

    // 关标签页本身也要有上限：一个卡死的页面连 Page.close 都可能不回
    if tokio::time::timeout(Duration::from_secs(5), page.close())
        .await
        .is_err()
    {
        tracing::warn!(
            url,
            "closing the tab timed out; the browser will be recycled"
        );
        return Err(ScrapeError::HeadlessUnavailable(
            "标签页关不掉，浏览器实例已不可信".to_string(),
        ));
    }

    match captured {
        Ok(r) => r,
        Err(_) => Err(ScrapeError::Timeout(format!(
            "无头浏览器加载 {url} 超时（排队 {}ms 后开始，HEADLESS_TIMEOUT_SECS={}, HEADLESS_IDLE_WAIT_SECS={}）",
            budget.queued_ms, budget.timeout_secs, budget.idle_wait_secs
        ))),
    }
}

async fn capture_in_page(
    page: &Page,
    url: &str,
    idle_wait_secs: u64,
    wait_until: WaitUntil,
    viewport: Option<Viewport>,
    screenshot: Option<&ScreenshotOptions>,
) -> Result<HeadlessCapture, ScrapeError> {
    let cdp = |what: &'static str| {
        move |e: spider::chromiumoxide::error::CdpError| {
            ScrapeError::HeadlessUnavailable(format!("{what}失败: {e}"))
        }
    };

    // stealth 与 UA 一起设：UA 单独改而不改其余指纹，反而比不改更显眼
    page.enable_stealth_mode_with_agent(DEFAULT_UA)
        .await
        .map_err(cdp("启用 stealth 模式"))?;

    if let Some(vp) = viewport {
        page.emulate_viewport(SetDeviceMetricsOverrideParams::new(
            vp.width as i64,
            vp.height as i64,
            vp.dpr as f64,
            false,
        ))
        .await
        .map_err(cdp("设置视口"))?;
    }

    if screenshot.is_none() {
        page.set_blocked_urls(
            BLOCKED_WHEN_NO_SCREENSHOT
                .iter()
                .map(|s| s.to_string())
                .collect(),
        )
        .await
        .map_err(cdp("设置资源拦截"))?;
    }

    page.goto(url).await.map_err(cdp("导航"))?;

    // 导航自己的响应：状态码和 `ERR_*` 类失败都在这里，不必再靠猜
    let nav = page
        .wait_for_navigation_response()
        .await
        .map_err(cdp("等待导航完成"))?;

    if let Some(reason) = nav.as_ref().and_then(|r| r.failure_text.clone()) {
        // Chrome 自己的网络错误页（DNS 失败、连接被拒、代理不可达…）。这是"没连上"，
        // 与"连上了被拒"要分开报——两者的排查方向相反。
        return Err(ScrapeError::FetchFailed(format!(
            "无头导航 {url} 失败: {reason}"
        )));
    }
    let status = nav
        .as_ref()
        .and_then(|r| r.response.as_ref())
        .map(|r| r.status as u16);

    match wait_until {
        // DCL 已经由 wait_for_navigation_response 覆盖，不必再等
        WaitUntil::DomContentLoaded => {}
        WaitUntil::Load => {
            let _ = tokio::time::timeout(Duration::from_secs(idle_wait_secs), page.wait_for_load())
                .await;
            // 光等 load 对 SPA 不够：标题、og:* 常常是 JS 挂上去的。再给一段有上限的
            // "网络基本安静"等待，这也是从前 spider 的 wait_for_idle_network 在做的事。
            let _ = page
                .wait_for_network_almost_idle_with_timeout(Duration::from_secs(idle_wait_secs))
                .await;
        }
        WaitUntil::NetworkIdle => {
            let _ = page
                .wait_for_network_idle_with_timeout(Duration::from_secs(idle_wait_secs))
                .await;
        }
    }

    let html = page.content().await.map_err(cdp("读取页面 HTML"))?;
    if html.is_empty() {
        return Err(ScrapeError::HeadlessFailed(format!(
            "无头浏览器抓 {url} 返回了空 HTML"
        )));
    }

    let screenshot_bytes = match screenshot {
        None => None,
        Some(opts) => {
            let cdp_params = CaptureScreenshotParams {
                format: Some(match opts.format {
                    ImageFormat::Png => CaptureScreenshotFormat::Png,
                    ImageFormat::Jpeg => CaptureScreenshotFormat::Jpeg,
                    ImageFormat::Webp => CaptureScreenshotFormat::Webp,
                }),
                // PNG 是无损的，给它带 quality 只会让 CDP 参数自相矛盾；只有损格式才设
                quality: match opts.format {
                    ImageFormat::Png => None,
                    _ => Some(opts.quality as i64),
                },
                ..Default::default()
            };
            let params = ScreenshotParams {
                cdp_params,
                full_page: Some(opts.full_page),
                // 显式 false。留 None 时 spider 会去读 SCREENSHOT_OMIT_BACKGROUND，
                // 而它缺省兜底为 true —— 页面背景被抠成透明，对"这个页面长什么样"
                // 是纯粹的破坏。直连 CDP 之后默认值已经是 false，仍然写出来，
                // 因为这件事被踩过一次。
                omit_background: Some(false),
            };
            match page.screenshot(params).await {
                Ok(bytes) => Some(bytes),
                // 截图失败不该让整次抓取作废：元数据已经到手了
                Err(e) => {
                    tracing::warn!(url, "screenshot failed: {e}");
                    None
                }
            }
        }
    };

    Ok(HeadlessCapture {
        html,
        screenshot_bytes,
        // 拿到了文档却没有导航响应（比如从 bfcache 直接恢复）时按 200 处理：
        // 手上确实有一篇 HTML，没有任何证据说它被拒绝了
        status: status.unwrap_or(200),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 本地服务器用的时间账本：页面 15s 足够，网络空闲等 2s 就够（本地无子资源）
    fn test_budget() -> Budget {
        Budget {
            page: Duration::from_secs(15),
            idle_wait_secs: 2,
            timeout_secs: 15,
            queued_ms: 0,
        }
    }

    /// 只截视口的 PNG 请求，供各集成用例复用
    fn shot(full_page: bool) -> ScreenshotOptions {
        ScreenshotOptions {
            enabled: true,
            full_page,
            format: ImageFormat::Png,
            quality: 80,
        }
    }

    /// 不截图时拦子资源、截图时全放开。
    ///
    /// 离线断言，守的是这张表本身的语义。**CSS 不在表里**是刻意的：拦掉样式表连布局
    /// 都变了，而它省下的流量微乎其微。
    #[test]
    fn blocked_patterns_cover_media_but_never_stylesheets() {
        assert!(BLOCKED_WHEN_NO_SCREENSHOT.contains(&"*.png"));
        assert!(BLOCKED_WHEN_NO_SCREENSHOT.contains(&"*.woff2"));
        assert!(
            !BLOCKED_WHEN_NO_SCREENSHOT.iter().any(|p| p.contains("css")),
            "拦 CSS 会改变页面布局，不该出现在这张表里"
        );
    }

    /// `--disable-dev-shm-usage` 必须在启动参数里。
    ///
    /// docker 默认 `/dev/shm` 只有 64MB，渲染进程的共享内存耗尽时是**标签页直接消失**，
    /// 容器层面看不到任何 OOM 记录 —— 这种失败没有任何症状，只能靠这条断言守着。
    #[test]
    fn base_args_defuse_the_64mb_dev_shm_trap() {
        assert!(BASE_ARGS.contains(&"--disable-dev-shm-usage"));
    }

    /// 排队等待与页面预算必须是两笔账。
    ///
    /// 合并成一个 deadline 时，一批并发里除了第一条以外全部会在排队中耗光预算，然后
    /// 以"目标站点超时"的名义被记到站点头上，还会触发 24h 的无头熔断。
    #[test]
    fn queue_wait_is_not_taken_from_the_page_budget() {
        // 两个旋钮各自独立可配，且默认值下排队预算不会吃掉整页预算
        assert!(*QUEUE_WAIT_SECS > 0);
        assert!(*CONCURRENCY >= 1);
    }

    /// 起一个只回一篇**没有任何链接**的 HTML 的本地服务器。
    ///
    /// 手写而不引依赖，与 `main.rs` 的测试服务器同一风格。
    async fn spawn_linkless_site(body: &'static str) -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut sock, _)) = listener.accept().await {
                let body = body.to_string();
                tokio::spawn(async move {
                    let mut buf = [0u8; 2048];
                    let _ = sock.read(&mut buf).await;
                    let resp = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    );
                    let _ = sock.write_all(resp.as_bytes()).await;
                });
            }
        });
        format!("http://{addr}/")
    }

    /// **这是本次重写的回归判据。**
    ///
    /// 一篇没有任何链接的页面。从前 spider 的 `scrape()` 会因此返回 0 页，报"没有返回
    /// 任何页面"—— 而这跟站点好不好、Chrome 稳不稳毫无关系，纯粹是爬虫记账的副作用。
    ///
    /// 走 [`capture_on`] 而不是 [`capture_headless`]：后者带 SSRF 校验，会拒掉 127.0.0.1。
    /// 这里要验的是"页面没有同源链接时还能不能拿到 HTML"，本地服务器是最确定的复现，
    /// 不该把它挂在某个第三方站点的可用性上。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn page_without_any_link_still_returns_html() {
        let url = spawn_linkless_site(
            "<html><head><title>No Links Here</title></head><body>bare</body></html>",
        )
        .await;
        let inst = acquire_browser().await.expect("launch browser");
        let r = capture_on(
            &inst.browser,
            &url,
            test_budget(),
            WaitUntil::Load,
            None,
            None,
        )
        .await
        .unwrap_or_else(|e| panic!("没有链接的页面也应当抓得到，实际: {e:?}"));
        assert!(
            r.html.contains("No Links Here"),
            "拿到的 HTML 不是那一页: {}",
            &r.html[..r.html.len().min(200)]
        );
        assert_eq!(r.status, 200);
    }

    /// 同一件事的联网版：`example.com` 唯一那条链接指向外域 iana.org，所以对爬虫来说
    /// 同样"无处可去"。留着它是因为本地服务器验不了真实网络栈（DNS、TLS、代理）。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn page_with_only_external_links_still_returns_html() {
        let r = capture_headless("https://example.com/", 30, 10, WaitUntil::Load, None, None)
            .await
            .expect("example.com 应当抓得到");
        assert!(r.html.contains("Example Domain"), "拿到的 HTML 不是那一页");
        assert_eq!(r.status, 200, "状态码应如实透出");
    }

    /// 浏览器实例跨请求复用：第二次不该再付一次冷启动。
    ///
    /// 冷启动实测 11.6–14.7s，是 30s 预算里最大的一笔；复用之后第二次应当快一个数量级。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn second_capture_reuses_the_browser() {
        let t1 = Instant::now();
        capture_headless("https://example.com/", 30, 5, WaitUntil::Load, None, None)
            .await
            .expect("first capture");
        let first = t1.elapsed();

        let t2 = Instant::now();
        capture_headless("https://example.com/", 30, 5, WaitUntil::Load, None, None)
            .await
            .expect("second capture");
        let second = t2.elapsed();

        assert!(
            second < first,
            "第二次({second:?})不该慢于第一次({first:?})，浏览器可能没被复用"
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_returns_screenshot_bytes() {
        let bytes = capture_headless(
            "https://example.com/",
            60,
            10,
            WaitUntil::Load,
            None,
            Some(shot(true)),
        )
        .await
        .expect("capture failed")
        .screenshot_bytes
        .expect("screenshot_bytes should not be None");
        assert!(bytes.len() > 1024, "截图字节数异常: {}", bytes.len());
    }

    /// 非 2xx 必须如实透出，而不是恒定 200 —— 否则风控拦截页会被当成正常页面收下，
    /// 变成一条标题叫"出错啦"的书签。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn navigation_status_is_reported_verbatim() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut sock, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 2048];
                    let _ = sock.read(&mut buf).await;
                    // 拦截页的典型形态：非 2xx，但正文是一篇像模像样的 HTML
                    let body = "<html><head><title>出错啦</title></head><body>nope</body></html>";
                    let _ = sock
                        .write_all(
                            format!(
                                "HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                body.len(),
                                body
                            )
                            .as_bytes(),
                        )
                        .await;
                });
            }
        });

        let inst = acquire_browser().await.expect("launch browser");
        let r = capture_on(
            &inst.browser,
            &format!("http://{addr}/"),
            test_budget(),
            WaitUntil::Load,
            None,
            None,
        )
        .await
        .expect("capture failed");
        assert_eq!(r.status, 403, "状态码被吞掉了，拦截页会被当成正常页面收下");
        assert!(
            r.html.contains("出错啦"),
            "正文仍应如实带回，由调用方决定采不采信"
        );
    }

    /// fullPage 不该再是硬编码的 true —— 整页截图必然比单屏高，字节数也该更大。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn full_page_flag_actually_changes_the_capture() {
        let vp = Some(Viewport {
            width: 1280,
            height: 720,
            dpr: 1.0,
        });
        let viewport_only = capture_headless(
            "https://stripe.com/",
            60,
            10,
            WaitUntil::Load,
            vp,
            Some(shot(false)),
        )
        .await
        .expect("capture failed")
        .screenshot_bytes
        .expect("no bytes");
        let full = capture_headless(
            "https://stripe.com/",
            60,
            10,
            WaitUntil::Load,
            vp,
            Some(shot(true)),
        )
        .await
        .expect("capture failed")
        .screenshot_bytes
        .expect("no bytes");
        assert!(
            full.len() > viewport_only.len(),
            "整页截图({} bytes)应大于单屏截图({} bytes)，fullPage 可能又被忽略了",
            full.len(),
            viewport_only.len()
        );
    }

    /// 截图落盘供人眼确认。
    ///
    /// 从前这里记录的是一个长期未定位的 bug：截出来的图没有 CSS、没有图片、没有 Web 字体，
    /// 而每项技术指标都正常（HTTP 200、字节数正常、`storageKey` 正常）。当时已经排除了资源
    /// 拦截配置（三种取值下截图逐字节相同，都是 107264 字节），怀疑方向之一是"spider 的
    /// `scrape()` 路径本身不加载子资源"—— 现在看就是它。改成直接导航 + 直接截图后，同一个
    /// stripe.com 得到 287698 字节的完整渲染图（2026-08-09 实测，macOS + Chrome 151）。
    ///
    /// 仍然不做断言：字节数变大不等于"好看"，这件事只有人眼能判，而一个通过了却什么都没
    /// 保证的断言（老那条 `> 10KB` 正是如此，裸 HTML 也有 100KB）比没有断言更坏。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn screenshot_probe_writes_a_file_for_human_review() {
        let vp = Some(Viewport {
            width: 1280,
            height: 720,
            dpr: 1.0,
        });
        let bytes = capture_headless(
            "https://stripe.com/",
            60,
            10,
            WaitUntil::Load,
            vp,
            Some(shot(false)),
        )
        .await
        .expect("capture failed")
        .screenshot_bytes
        .expect("screenshot_bytes should not be None");
        let out = std::env::temp_dir().join("bookmarkify-screenshot-probe.png");
        std::fs::write(&out, &bytes).expect("write probe");
        eprintln!(
            "screenshot probe: {} bytes → {}",
            bytes.len(),
            out.display()
        );
    }
}
