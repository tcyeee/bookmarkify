# Scrapper 代理接入设计（容器化 Clash on app_network）

- 日期：2026-06-17
- 状态：已批准（待实现）
- 范围：bookmarkify-scrapper 出站抓取流量经代理；代理须随每次部署持续生效

## 1. 背景与问题

bookmarkify-scrapper 抓取部分墙外站点（YouTube/Twitter 等）需要代理。约束：

- **"部署后代理不能掉"**：scrapper 由 GitHub Actions 部署——SSH 到服务器写入
  `bookmarkify-scrapper/deploy/compose.prod.yml`（仓库内单一真相源）后 `docker compose up -d`。
  因此代理配置必须写进该 compose 文件，手动注入运行中容器的方式会在重新部署时丢失。
- **容器到宿主机代理不通**：scrapper 容器在 `app_network` 桥接网（172.19.0.0/16，网关 172.19.0.1，
  容器 IP 172.19.0.6）。宿主机 Clash 仅监听 `127.0.0.1:7890`，容器内 `127.0.0.1` 指向容器自身，够不到。
- **两条抓取链路对代理支持不一致**：
  - Layer 1（reqwest，`main.rs`）已支持 `PROXY_URL` 环境变量。
  - Layer 2（headless Chrome via spider-rs，`headless.rs`）**未接代理**，Chrome 不读环境变量，
    需通过 spider 的 `Website::with_proxies` 传 `--proxy-server` 启动参数。
- **订阅自动更新会覆盖 config.yaml**：`/opt/clash/update-sub.sh`（每日 systemd timer）整体覆盖
  `/opt/clash/config.yaml`，任何写进该文件的网络设置次日会被冲掉。

## 2. 选定方案：容器化 Clash 挂到 app_network（方案 A）

用容器化 Clash 替代宿主机 systemd Clash，加入 `app_network`，scrapper 经容器 DNS 名 `clash:7890` 访问。
相较"保留 systemd Clash 并暴露给 docker 桥"（方案 B），A 的容器间网络不依赖宿主机防火墙/监听地址，
也不怕订阅覆盖，对"代理不能掉"最稳，且零公网暴露。代价是把刚搭的 systemd 迁为容器（一次性）。

## 3. 架构与数据流

```
scrapper(app_network 172.19.0.x) ──reqwest / Chrome(--proxy-server)──► clash:7890
宿主机 ubuntu ──► 127.0.0.1:7890（容器发布口）──► clash 容器
clash 容器 ──► trojan 节点 ──► 墙外；国内 IP 经 GEOIP,CN,DIRECT 直连
```

## 4. 组件改动

### 4.1 Clash 容器（宿主机自管，CI 不触碰）
- 新建 `/opt/clash/docker-compose.yml`，服务名 `clash`，`networks: [app_network]`（external，复用现有）。
- 镜像：优先官方 `metacubex/mihomo`（pin 版本）；若服务器拉取失败，回退为基于现有
  `/opt/clash/clash`（Mihomo Meta v1.19.24, linux amd64）+ `ca-certificates` 的薄镜像。
- 配置：bind-mount `/opt/clash` → 复用现有 `config.yaml` 与 `.config/mihomo/Country.mmdb`。
  容器内启动等价于 `clash -f /opt/clash/config.yaml -d /opt/clash/.config/mihomo`。
- 端口：仅发布 `127.0.0.1:7890:7890` 与 `127.0.0.1:7891:7891`（宿主机自用，**不暴露公网**）。
- `restart: unless-stopped` → 开机自启 + 崩溃自愈。

### 4.2 下线 systemd Clash
- `systemctl disable --now clash.service`，腾出 `127.0.0.1:7890` 供容器发布，避免端口冲突。
- 保留 unit 文件（备份），便于回滚。

### 4.3 订阅自动更新脚本（复用，微调）
- `/opt/clash/update-sub.sh`：重启步骤由 `systemctl restart clash` 改为 `docker restart clash`。
- 其余不变：UA=`mihomo/1.19.24` 拉取、内容校验、`/opt/clash/clash -t` 语法校验、GEOIP,CN 幂等注入、
  备份轮转、`127.0.0.1:7890` 健康检查与失败回滚。
- `clash-update.timer` 不变（每日 04:30）。

### 4.4 headless.rs 代码改动（核心）
- 读取 `PROXY_URL`（非空）；构建 `Website` 时调用 `website.with_proxies(Some(vec![url]))`。
- spider 内部（`features/chrome.rs`）据此拼出 `--proxy-server=<url>` 传给 Chrome 启动参数，
  Layer 2 渲染流量即走代理。Layer 1 reqwest 代理已在 `main.rs` 实现，无需改动。
- 行为：`PROXY_URL` 未设 → 维持现状（直连）；非空 → reqwest 与 Chrome 双双走代理。

### 4.5 compose.prod.yml（仓库，CI 部署真相源 —— "代理不掉"的根本）
- scrapper `environment` 增加 `PROXY_URL: http://clash:7890`。
- 容器间经 app_network 内嵌 DNS 解析 `clash`，无需 `extra_hosts`。
- 写入仓库后每次 CI 部署自动带上，重新部署不丢失。

### 4.6 文档微调
- `bookmarkify-scrapper/docker-compose.yml`（dev）与 `bookmarkify-scrapper/CLAUDE.md`
  注明 headless 现亦遵循 `PROXY_URL`。

## 5. 验证

1. `docker exec bookmarkify-scraper` 经 `http://clash:7890` curl YouTube → 期望 200。
2. `POST /scrape`（国外 JS 站 + `headless=true`）→ 返回截图与元数据 = Chrome 代理生效。
3. 国内站抓取仍直连（命中 GEOIP,CN,DIRECT），不绕境外。
4. `docker restart clash` 后 `update-sub.sh` 手动跑一次 → 订阅更新 + 健康检查通过。
5. 重启服务器 → clash 与 scrapper 容器均自动拉起，代理仍生效。

## 6. 风险与边界

- **启动顺序**：两个 compose 各自 `unless-stopped`，开机都会被 Docker 拉起；scrapper 启动瞬间若 clash 尚未就绪，
  单次抓取失败可由上层重试，非持久故障。可选加 healthcheck。
- **SsrfSafeResolver**：走 HTTP 代理时由代理做 DNS，自定义 resolver 对被代理请求基本旁路——
  正是期望行为（让代理解析墙外域名）。SSRF 防护对直连路径仍有效。
- **OSS 上传**：oss-rust-sdk 自建 client，不受 `PROXY_URL` 影响；OSS 走阿里云本就应直连，无需代理。
- **镜像拉取**：官方镜像拉取若受限，用本地二进制薄镜像兜底（已有二进制，无外网依赖）。

## 7. 回滚

- 容器化失败：`systemctl enable --now clash.service` 恢复 systemd Clash；移除 `/opt/clash/docker-compose.yml`。
- 代码改动失败：revert headless.rs 与 compose.prod.yml 改动并重新部署。
- config 损坏：`/opt/clash/config.yaml.bak.*` 时间戳备份恢复。
