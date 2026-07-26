#!/usr/bin/env node
/**
 * Remove Nitro mock server from a create-vben-admin generated project.
 *
 * Usage:
 *   pnpm run remove-mock
 *   node scripts/remove-mock.mjs
 */
import { existsSync } from 'node:fs';
import { readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const root = process.cwd();
const args = process.argv.slice(2);

function printHelp() {
  console.log(`remove-mock.mjs — remove apps/backend-mock from this project

Usage:
  pnpm run remove-mock
  node scripts/remove-mock.mjs

What it does:
  - Delete apps/backend-mock/
  - Set VITE_NITRO_MOCK=false in .env.development

Then run: pnpm install && pnpm dev
`);
}

async function patchMockEnv(enabled) {
  const envPath = join(root, '.env.development');
  if (!existsSync(envPath)) {
    console.warn('Skip env patch: .env.development not found');
    return;
  }

  let content = await readFile(envPath, 'utf8');
  const value = enabled ? 'true' : 'false';

  if (/^VITE_NITRO_MOCK=.*$/m.test(content)) {
    content = content.replace(/^VITE_NITRO_MOCK=.*$/m, `VITE_NITRO_MOCK=${value}`);
  } else {
    content = `${content.trimEnd()}\nVITE_NITRO_MOCK=${value}\n`;
  }

  await writeFile(envPath, content, 'utf8');
  console.log(`Updated VITE_NITRO_MOCK=${value} in .env.development`);
}

async function removeMock() {
  const mockDir = join(root, 'apps/backend-mock');
  if (existsSync(mockDir)) {
    await rm(mockDir, { recursive: true, force: true });
    console.log('Removed apps/backend-mock/');
  } else {
    console.log('apps/backend-mock/ not present — skipped');
  }

  await patchMockEnv(false);
  console.log('Next: pnpm install && pnpm dev');
}

async function main() {
  if (args.includes('--help') || args.includes('-h')) {
    printHelp();
    return;
  }

  await removeMock();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
