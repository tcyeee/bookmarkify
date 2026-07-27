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

## 其他说明

- Mock 服务（`apps/backend-mock`，Nitro Mock）已移除，`pnpm dev` 直接对接真实后端，见根目录 `.env.development` 的 `VITE_GLOB_API_URL`。

- 未选中的其他 `apps/web-*` 模板不会出现在本仓库。
- 需要更新 vben 基线时，可使用 create-vben-admin 指定新的 upstream ref 重新生成，或手动合并 upstream 变更。
- 若需进一步裁剪 upstream 能力，可参考 [Vben 官方项目精简说明](https://doc.vben.pro/guide/introduction/thin.html)（与 Mock 移除无关）。

## 链接

- [Vben Admin 文档](https://doc.vben.pro/)
- [vue-vben-admin](https://github.com/vbenjs/vue-vben-admin)
- [create-vben-admin](https://github.com/379949990/create-vben-admin)

## License

MIT（应用代码遵循 upstream 与 create-vben-admin 生成说明；详见各文件头注释。）
