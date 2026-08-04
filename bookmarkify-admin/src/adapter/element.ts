import type { Component } from 'vue';

import { defineAsyncComponent } from 'vue';

// ElMessage 是命令式调用的函数，没有组件那层异步加载可挂，样式只能在这里静态引一次。
// 详见文件末尾 ElMessage 的导出注释。
import 'element-plus/es/components/message/style/css';

/**
 * 按需加载的 element-plus 组件。
 *
 * 这个应用没有全量注册 element-plus（`bootstrap.ts` 只装了 `v-loading` 指令），组件靠各页面
 * 自己 `defineAsyncComponent` 拉。问题是那段样板每个组件 6 行，一个页面十来个组件就是 90 行，
 * 而它在 `src/views/` 下被逐字复制了十几份 —— 加一个组件要在十几个文件里各写一遍，
 * 漏掉 style 那一半时还只在生产构建里表现为"组件没样式"。集中到这里，各页面 import 即可。
 *
 * **组件代码与样式必须一起 await**：只 await 组件会让首帧渲染出无样式的裸 DOM。
 */
function mod<M extends Record<string, unknown>>(
  load: () => Promise<M>,
  style: () => Promise<unknown>,
) {
  // 同一模块的多个组件（ElForm/ElFormItem、ElSelect/ElOption）共用一次加载
  const both = () => Promise.all([load(), style()]).then(([m]) => m);
  return <K extends keyof M>(name: K) =>
    defineAsyncComponent(() => both().then((m) => m[name] as Component));
}

const alertMod = mod(
  () => import('element-plus/es/components/alert/index'),
  () => import('element-plus/es/components/alert/style/css'),
);
const buttonMod = mod(
  () => import('element-plus/es/components/button/index'),
  () => import('element-plus/es/components/button/style/css'),
);
const cardMod = mod(
  () => import('element-plus/es/components/card/index'),
  () => import('element-plus/es/components/card/style/css'),
);
const datePickerMod = mod(
  () => import('element-plus/es/components/date-picker/index'),
  () => import('element-plus/es/components/date-picker/style/css'),
);
const dialogMod = mod(
  () => import('element-plus/es/components/dialog/index'),
  () => import('element-plus/es/components/dialog/style/css'),
);
const emptyMod = mod(
  () => import('element-plus/es/components/empty/index'),
  () => import('element-plus/es/components/empty/style/css'),
);
const formMod = mod(
  () => import('element-plus/es/components/form/index'),
  () => import('element-plus/es/components/form/style/css'),
);
const inputMod = mod(
  () => import('element-plus/es/components/input/index'),
  () => import('element-plus/es/components/input/style/css'),
);
const inputNumberMod = mod(
  () => import('element-plus/es/components/input-number/index'),
  () => import('element-plus/es/components/input-number/style/css'),
);
const linkMod = mod(
  () => import('element-plus/es/components/link/index'),
  () => import('element-plus/es/components/link/style/css'),
);
const paginationMod = mod(
  () => import('element-plus/es/components/pagination/index'),
  () => import('element-plus/es/components/pagination/style/css'),
);
const selectMod = mod(
  () => import('element-plus/es/components/select/index'),
  () => import('element-plus/es/components/select/style/css'),
);
const switchMod = mod(
  () => import('element-plus/es/components/switch/index'),
  () => import('element-plus/es/components/switch/style/css'),
);
const tagMod = mod(
  () => import('element-plus/es/components/tag/index'),
  () => import('element-plus/es/components/tag/style/css'),
);
const tooltipMod = mod(
  () => import('element-plus/es/components/tooltip/index'),
  () => import('element-plus/es/components/tooltip/style/css'),
);

export const ElAlert = alertMod('ElAlert');
export const ElButton = buttonMod('ElButton');
export const ElCard = cardMod('ElCard');
export const ElDatePicker = datePickerMod('ElDatePicker');
export const ElDialog = dialogMod('ElDialog');
export const ElEmpty = emptyMod('ElEmpty');
export const ElForm = formMod('ElForm');
export const ElFormItem = formMod('ElFormItem');
export const ElInput = inputMod('ElInput');
export const ElInputNumber = inputNumberMod('ElInputNumber');
export const ElLink = linkMod('ElLink');
export const ElOption = selectMod('ElOption');
export const ElPagination = paginationMod('ElPagination');
export const ElSelect = selectMod('ElSelect');
export const ElSwitch = switchMod('ElSwitch');
export const ElTag = tagMod('ElTag');
export const ElTooltip = tooltipMod('ElTooltip');

/**
 * 命令式提示。是个函数不是组件，没法走上面的异步组件那条路，只能静态导入。
 *
 * 但**必须走 `es/components/...` 子路径**：`import { ElMessage } from 'element-plus'` 命中的是
 * 根 barrel，把整个库拉进调用方所在的 chunk，上面那些按需加载当场白做。样式在文件顶部
 * 一并显式导入 —— 根 barrel 的自动样式注入在子路径导入下不生效。
 */
export { ElMessage } from 'element-plus/es/components/message/index';
