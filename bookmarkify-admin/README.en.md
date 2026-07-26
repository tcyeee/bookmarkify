# vben-fresh

> Standalone frontend scaffold extracted from [vue-vben-admin](https://github.com/vbenjs/vue-vben-admin) via [create-vben-admin](https://github.com/379949990/create-vben-admin).

[中文 README](./README.md)

## Overview

| Field             | Value                                  |
| ----------------- | -------------------------------------- |
| UI template       | `web-ele` (Element Plus) |
| upstream ref      | `v5.5.9`                              |
| create-vben-admin | `1.0.1`           |

This repo uses a **flat layout**: application code lives at the repository root; required upstream workspace packages are kept under `packages/` and `internal/` and usually do not need edits.

## Quick start

```bash
pnpm install   # skip if already done during create-vben-admin
pnpm dev
```

The dev server port is controlled by `VITE_PORT` in `.env.development` (currently **5777**). Always follow the URL printed in your terminal.

## Scripts

| Command          | Description              |
| ---------------- | ------------------------ |
| `pnpm dev`       | Development server       |
| `pnpm build`     | Production build         |
| `pnpm preview`   | Preview production build |
| `pnpm typecheck` | TypeScript check         |

| `pnpm run remove-mock` | Remove mock server |

## Mock server (included)

This repo **includes** `apps/backend-mock` (Nitro Mock). Running `pnpm dev` starts the mock API via the Vite plugin:

- Mock base URL: **http://localhost:5320/api**
- Toggle: `.env.development` → `VITE_NITRO_MOCK=true`

### Remove mock server

This repo includes [`scripts/remove-mock.mjs`](./scripts/remove-mock.mjs). When you no longer need mock:

1. Run `pnpm run remove-mock` (or `node scripts/remove-mock.mjs`)
2. Run `pnpm install`, then `pnpm dev`, and point `VITE_GLOB_API_URL` to your real API

Or manually: set `VITE_NITRO_MOCK=false` and delete `apps/backend-mock/`.

If you no longer need the helper, delete `scripts/remove-mock.mjs` and remove the `remove-mock` script from `package.json`.

## API reference (OpenAPI)

An **OpenAPI 3.0** route inventory was generated from upstream `backend-mock` (reference only, no handler code):

- File: [`docs/mock-api.openapi.json`](./docs/mock-api.openapi.json)
- Default mock base URL: **http://localhost:5320/api**

Import into Apifox: Project settings → Import → OpenAPI → select the JSON file.

## Notes

- Other `apps/web-*` templates from upstream are not copied.
- To refresh the vben baseline, re-run create-vben-admin with a newer upstream ref or merge upstream changes manually.
- For further upstream slimming, see the [official Vben thin guide](https://doc.vben.pro/guide/introduction/thin.html) (unrelated to mock removal).

## Links

- [Vben Admin docs](https://doc.vben.pro/)
- [vue-vben-admin](https://github.com/vbenjs/vue-vben-admin)
- [create-vben-admin](https://github.com/379949990/create-vben-admin)

## License

MIT (application code follows upstream and create-vben-admin generation notes; see file headers where applicable.)
