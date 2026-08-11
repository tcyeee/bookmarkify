#!/usr/bin/env node
/**
 * `pnpm typecheck` 的外壳：跑 vue-tsc，**按错误所在的目录**决定退出码。
 *
 * ## 为什么需要这一层
 *
 * 这个仓库是 vue-vben-admin 被 create-vben-admin 剪枝后的产物：`src/` 是我们自己的业务代码，
 * 而 `packages/` 与 `internal/` 是**内嵌进来的框架源码**（777 个文件 / 6 万行，是业务代码的 4 倍）。
 * 它们是上游的代码，我们既没有写、通常也不该改；vue-tsc 却会顺着 `@vben/*` 的 workspace 链接
 * 一路把它们编进同一个 program，于是上游自带的类型问题全都算在我们头上。
 *
 * 这一层不加的话，结果是 `pnpm typecheck` **恒定失败**，于是：
 * - 它进不了 CI（web 的 typecheck 是卡在构建之前的，admin 没有对应的东西）；
 * - 人也不会去看 —— 一个永远红的检查等于没有检查；
 * - 我们自己写出来的类型错误会安静地混在里面，没有任何人会发现。
 *
 * 2026-08-11 之前它报 129 条。其中 122 条是 element-plus 按需引入的 `style/css` 子路径缺声明，
 * 那是个有正解的问题（见 `src/element-plus-style.d.ts`），补上之后只剩 7 条，**全部**落在
 * `packages/` 里。所以判据很干净：`src/` 一条都不许有，`packages/` / `internal/` 的照常打印
 * 但不阻断。
 *
 * ## 为什么不是简单地 exclude 掉
 *
 * tsconfig 的 `include` 本来就只有 `src/**`。这些文件是被**引用**进来的，不是被 include 进来的，
 * `exclude` 对它们不起作用 —— 除非把 `@vben/*` 的路径映射从源码改指到构建产物，
 * 那会牺牲改一行框架代码就能热更的开发体验，为了一个检查不值得。
 *
 * 上游那 7 条也不是被忽略：它们照常打印，且计数会打出来。数字变了就说明内嵌代码动过，
 * 那件事本身值得看一眼。
 */
import { spawn } from 'node:child_process';

/** 我们自己的代码。这里面一条错误都不允许。 */
const OWNED = /^src[/\\]/;

const child = spawn(
  'vue-tsc',
  ['--noEmit', '--skipLibCheck', '--pretty', 'false'],
  { shell: true },
);

let out = '';
const capture = (chunk) => {
  const text = chunk.toString();
  out += text;
  process.stdout.write(text);
};
child.stdout.on('data', capture);
child.stderr.on('data', capture);

child.on('close', (code) => {
  const errors = out
    .split('\n')
    .filter((line) => /error TS\d+/.test(line))
    .map((line) => line.trim());

  const owned = errors.filter((line) => OWNED.test(line));
  const vendored = errors.filter((line) => !OWNED.test(line));

  console.log('');
  console.log('─'.repeat(72));
  console.log(`自有代码 (src/)          : ${owned.length} 条`);
  console.log(`内嵌框架 (packages/ 等)  : ${vendored.length} 条（上游问题，不阻断）`);
  console.log('─'.repeat(72));

  if (owned.length > 0) {
    console.error('\n✗ src/ 下存在类型错误，这些必须修：\n');
    owned.forEach((line) => console.error(`  ${line}`));
    process.exit(1);
  }

  // vue-tsc 自身崩溃（而不是报类型错）时 code 非 0 且一条 error TS 都没有 —— 那种情况必须透传，
  // 否则一次工具故障会被这层外壳读成"检查通过"
  if (errors.length === 0 && code !== 0) {
    console.error(`\n✗ vue-tsc 异常退出 (code=${code})，没有产出可解析的错误列表`);
    process.exit(code ?? 1);
  }

  console.log('\n✓ src/ 类型检查通过');
  process.exit(0);
});
