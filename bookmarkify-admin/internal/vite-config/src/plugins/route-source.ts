import type { Plugin } from 'vite';

import { basename } from 'node:path';

interface RouteSourceOptions {
  /**
   * 需要处理的文件，默认匹配 src/router/routes 下的所有 ts
   */
  include?: RegExp;
  /**
   * 注入路径的前缀，默认取 vite root 的目录名（bookmarkify-admin）
   * 这样复制出来的路径可以直接在 monorepo 根目录定位
   */
  prefix?: string;
}

/**
 * 把路由的 `component: () => import('#/views/xxx/index.vue')`
 * 对应的源码路径写进同一个路由对象的 `meta.source`。
 *
 * 生产构建后动态 import 会被替换成 chunk 文件名，运行时拿不到原始路径，
 * 所以在构建期固化下来，供面包屑的「复制页面路径」按钮使用。
 */
function viteRouteSourcePlugin(options: RouteSourceOptions = {}): Plugin {
  const include = options.include ?? /[/\\]src[/\\]router[/\\]routes[/\\].*\.ts$/;
  let prefix = options.prefix ?? '';

  return {
    // 不设置 enforce，让 transform 跑在 vite:esbuild 之后，拿到的是已经去掉
    // TS 类型标注的 JS，才能被 this.parse（acorn/rollup）解析
    name: 'bookmarkify:route-source',

    configResolved(config) {
      if (options.prefix === undefined) {
        prefix = basename(config.root);
      }
    },

    transform(code, id) {
      const file = id.split('?')[0] ?? '';
      if (!include.test(file) || !code.includes('/views/')) {
        return null;
      }

      const ast = this.parse(code);
      const edits: Array<{ pos: number; text: string }> = [];

      walk(ast, (node) => {
        if (node.type !== 'ObjectExpression') return;

        const componentProp = findProp(node, 'component');
        if (!componentProp) return;

        const source = extractViewPath(componentProp.value, prefix);
        if (!source) return;

        const metaProp = findProp(node, 'meta');
        if (metaProp) {
          // meta 已存在且是对象字面量：插到第一个属性前面
          if (metaProp.value.type !== 'ObjectExpression') return;
          edits.push({
            pos: metaProp.value.start + 1,
            text: ` source: ${JSON.stringify(source)},`,
          });
        } else {
          // 没有 meta：紧跟在 component 后面补一个
          edits.push({
            pos: componentProp.end,
            text: `, meta: { source: ${JSON.stringify(source)} }`,
          });
        }
      });

      if (edits.length === 0) return null;

      // 从后往前插入，避免前面的插入影响后面的偏移量
      edits.sort((a, b) => b.pos - a.pos);
      let result = code;
      for (const { pos, text } of edits) {
        result = result.slice(0, pos) + text + result.slice(pos);
      }

      return { code: result, map: null };
    },
  };
}

/**
 * 从 `() => import('#/views/a/b.vue')` 里取出 `<prefix>/src/views/a/b.vue`。
 * 依赖链上的插件可能已经把 `#/` 别名重写成 `/src/`，两种形式都要兼容。
 */
function extractViewPath(node: any, prefix: string): string | undefined {
  let specifier: string | undefined;

  walk(node, (n) => {
    if (specifier) return;
    if (n.type === 'ImportExpression' && typeof n.source?.value === 'string') {
      specifier = n.source.value;
    }
  });

  if (!specifier) return undefined;

  const clean = specifier.split('?')[0] ?? '';
  const index = clean.indexOf('views/');
  if (index === -1) return undefined;

  const relative = `src/${clean.slice(index)}`;
  return prefix ? `${prefix}/${relative}` : relative;
}

function findProp(node: any, name: string): any {
  return node.properties?.find(
    (prop: any) =>
      prop.type === 'Property' &&
      !prop.computed &&
      (prop.key?.name === name || prop.key?.value === name),
  );
}

function walk(node: any, visit: (node: any) => void): void {
  if (!node || typeof node !== 'object') return;

  if (Array.isArray(node)) {
    for (const child of node) walk(child, visit);
    return;
  }

  if (typeof node.type !== 'string') return;
  visit(node);

  for (const key of Object.keys(node)) {
    if (key === 'type' || key === 'start' || key === 'end' || key === 'loc') {
      continue;
    }
    walk(node[key], visit);
  }
}

export { viteRouteSourcePlugin };
