import { defineConfig, viteRouteSourcePlugin } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';
import { loadEnv } from 'vite';

export default defineConfig(async ({ mode }) => {
  // 后端落点：默认本地 API 进程，设 VITE_PROXY_TARGET 可整体切到线上（REST 和 WS 一起切）。
  // 写在 gitignore 的 .env 里即可，见 .env.example。
  const env = loadEnv(mode, process.cwd());
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8001';
  const isRemote = !/127\.0\.0\.1|localhost/.test(target);

  return {
    application: {},
    vite: {
      plugins: [
        ElementPlus({
          format: 'esm',
        }),
        viteRouteSourcePlugin(),
      ],
      server: {
        proxy: {
          // 管理端 WebSocket（一键收录进度）：转发到 API 的 /ws，realm=ADMIN
          // 本地和线上 nginx 都是原样透传 /ws，无需 rewrite
          '/ws': {
            changeOrigin: true,
            target,
            ws: true,
          },
          '/api/admin': {
            changeOrigin: true,
            // 直连本地 API 进程时要自己剥掉 /api 前缀；指向线上时由 nginx
            // 的 `proxy_pass http://bookmarkify-api:7001/` 负责剥，这里必须原样保留，
            // 否则 /admin/** 会落到 nginx 的 SPA 兜底上，拿回一坨 index.html
            rewrite: isRemote ? undefined : (path) => path.replace(/^\/api/, ''),
            target,
            ws: true,
          },
        },
      },
    },
  };
});
