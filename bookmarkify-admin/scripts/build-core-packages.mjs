#!/usr/bin/env node
/**
 * Build the @vben-core/* workspace packages that ship a real "build" script
 * (unbuild / vite library build) before building the app itself.
 *
 * Running these via `pnpm -r run build` is unreliable in this pruned,
 * turbo-less scaffold (the recursive/parallel runner silently produces no
 * dist output for these packages even though it reports success), so build
 * them one at a time via `pnpm --filter <name> run build` instead.
 */
import { execFileSync } from 'node:child_process';

const packages = [
  '@vben-core/typings',
  '@vben-core/shared',
  '@vben-core/icons',
  '@vben-core/design',
  '@vben-core/composables',
  '@vben-core/layout-ui',
  '@vben-core/popup-ui',
  '@vben-core/tabs-ui',
  '@vben-core/form-ui',
  '@vben-core/menu-ui',
];

for (const name of packages) {
  console.log(`\n> building ${name}`);
  execFileSync('pnpm', ['--filter', name, 'run', 'build'], { stdio: 'inherit' });
}
