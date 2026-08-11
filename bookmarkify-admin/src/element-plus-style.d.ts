/**
 * element-plus 按需引入时那条 `.../style/css` 子路径的模块声明。
 *
 * ## 这一个文件消掉了 129 条 typecheck 报错里的 122 条
 *
 * `src/adapter/component/index.ts` 与 `src/adapter/element.ts` 用的是 element-plus 官方的
 * 按需引入写法：组件本体和它的样式分两个 import，后者是**纯副作用**的（它只往页面里塞 CSS，
 * 没有任何导出）。element-plus 没有为这些 `style/css` 子路径提供 `.d.ts`，于是每写一个组件
 * 就换来一条 TS7016 "Could not find a declaration file"。
 *
 * 这不是代码有问题，是缺一份声明 —— 而这正是 `.d.ts` 存在的意义。
 *
 * ## 为什么值得专门修
 *
 * 在此之前 `pnpm typecheck` 恒定报 129 条错，于是它**不能**作为门禁：既进不了 CI，人也不会去看。
 * 而一个永远红的检查等于没有检查 —— admin 因此成了三个服务里唯一没有类型门禁的那个
 * （web 的 typecheck 是 0 error 且卡在 CI 的构建之前）。真正的 bug 混在 122 条噪音里，
 * 没有任何人会发现。
 *
 * 类型给成 `unknown` 而不是 `any`：这些模块确实没有任何可用的导出，写成 `any` 会让
 * `import x from '.../style/css'` 之后对 `x` 的任意访问都合法，而那必然是个错误。
 */
declare module 'element-plus/es/components/*/style/css' {
  const styles: unknown;
  export default styles;
}
