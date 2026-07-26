# vben-fresh

> 由 [create-vben-admin](https://github.com/379949990/create-vben-admin) 从 [vue-vben-admin](https://github.com/vbenjs/vue-vben-admin) 提取生成的独立前端工程。

[English README](./README.en.md)

## 概览

| 项                | 值                                      |
| ----------------- | --------------------------------------- |
| UI 模板           | `web-ele`（Element Plus） |
| upstream ref      | `v5.5.9`                               |
| create-vben-admin | `1.0.1`            |

本仓库采用 **扁平布局**：业务代码在仓库根目录；构建所需的 upstream workspace 包保留在 `packages/` 与 `internal/` 中，一般无需修改。

## 快速开始

```bash
pnpm install   # 若生成时已完成可跳过
pnpm dev
```

开发服务器端口由根目录 `.env.development` 中的 `VITE_PORT` 控制（当前为 **5777**）。请以终端实际输出为准。

## 常用命令

| 命令             | 说明                |
| ---------------- | ------------------- |
| `pnpm dev`       | 开发模式            |
| `pnpm build`     | 生产构建            |
| `pnpm preview`   | 预览构建产物        |
| `pnpm typecheck` | TypeScript 类型检查 |

| `pnpm run remove-mock` | 移除 Mock 服务 |

## Mock 服务（已包含）

本仓库 **已包含** `apps/backend-mock`（Nitro Mock）。`pnpm dev` 时会随 Vite 插件启动 Mock API：

- Mock 基址：**http://localhost:5320/api**
- 开关：根目录 `.env.development` → `VITE_NITRO_MOCK=true`

### 移除 Mock 服务

本仓库附带 [`scripts/remove-mock.mjs`](./scripts/remove-mock.mjs)。不再需要 Mock 时：

1. 运行 `pnpm run remove-mock`（或 `node scripts/remove-mock.mjs`）
2. 执行 `pnpm install` 后 `pnpm dev`，对接真实后端（修改 `VITE_GLOB_API_URL` 等）

也可手动：在 `.env.development` 设置 `VITE_NITRO_MOCK=false` 并删除 `apps/backend-mock/`。

若不再需要该脚本，删除 `scripts/remove-mock.mjs` 并从 `package.json` 移除 `remove-mock` 命令即可。

## API 参考（OpenAPI）

已从 upstream `backend-mock` 路由生成 **OpenAPI 3.0** 清单（仅作接口参考，不含 handler 实现）：

- 文件：[`docs/mock-api.openapi.json`](./docs/mock-api.openapi.json)
- 默认 Mock 基址：**http://localhost:5320/api**

导入 Apifox：项目设置 → 导入 → OpenAPI → 选择上述 JSON 文件。

## 其他说明

- 未选中的其他 `apps/web-*` 模板不会出现在本仓库。
- 需要更新 vben 基线时，可使用 create-vben-admin 指定新的 upstream ref 重新生成，或手动合并 upstream 变更。
- 若需进一步裁剪 upstream 能力，可参考 [Vben 官方项目精简说明](https://doc.vben.pro/guide/introduction/thin.html)（与 Mock 移除无关）。

## 链接

- [Vben Admin 文档](https://doc.vben.pro/)
- [vue-vben-admin](https://github.com/vbenjs/vue-vben-admin)
- [create-vben-admin](https://github.com/379949990/create-vben-admin)

## License

MIT（应用代码遵循 upstream 与 create-vben-admin 生成说明；详见各文件头注释。）
