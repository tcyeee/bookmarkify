import { onScopeDispose, toRaw, watch } from "vue";

/**
 * 条件筛选自动搜索：筛选表单一变就重新查询，页面不再需要「搜索」按钮。
 *
 * 「搜索」按钮存在的意义是让用户攒一批改动后一次提交，而这些列表页的筛选项都是单值控件 ——
 * 选完一个下拉就已经是一个完整的意图，还要再点一下按钮纯属多余，而且「改了条件忘了点搜索、
 * 对着一张旧表下结论」是后台里最容易犯的错。
 *
 * 抖动是必需的：输入框按键盘节奏逐字触发，会打出一串必然被后一次覆盖的请求。300ms 对下拉/开关
 * 这类一次性选择也统一生效 —— 分类型给不同延迟需要逐页标注哪一项是输入框，而 300ms 的下拉延迟
 * 本来就在感知阈值附近，不值得为它引入一套猜测控件类型的逻辑。
 *
 * @param form 筛选表单（reactive 对象，字段都是标量或字符串数组）
 * @param run  真正的查询动作，通常是 `() => gridApi.reload()`
 * @param options.initial 「重置」还原到的条件。默认取 setup 时的表单值 —— 只有初始值本身来自
 *   URL 参数（从别处下钻进来时带的状态）的页面需要显式给，否则「重置」会把那个状态又填回去
 * @param options.delay 抖动毫秒数
 */
export function useAutoSearch<T extends object>(
  form: T,
  run: () => void,
  options: { delay?: number; initial?: T } = {},
) {
  const { delay = 300, initial = snapshot(form) } = options;

  let timer: ReturnType<typeof setTimeout> | undefined;

  watch(
    // JSON 序列化既是变更判据也是深度依赖收集：它会读到日期区间那类数组的内部，
    // 不必额外开 deep（deep + reactive 对象拿到的 oldValue 与 newValue 是同一个引用，用不上）
    () => JSON.stringify(form),
    () => {
      clearTimeout(timer);
      timer = setTimeout(run, delay);
    },
  );

  // 组件已经销毁还去 reload 一张不存在的表格，只会换来一句没人看得懂的报错
  onScopeDispose(() => clearTimeout(timer));

  return {
    /** 还原到初始筛选条件。就地改同一个对象，v-model 的绑定才不会断 */
    reset() {
      Object.assign(form, snapshot(initial));
    },
  };
}

/**
 * 表单快照。
 *
 * 不用 `JSON.parse(JSON.stringify())`：序列化会把值为 `undefined` 的键整个丢掉，
 * 而「不筛」在这些表单里正是 `undefined` —— 快照里缺了那个键，`Object.assign` 就清不掉
 * 用户后来选上的值，重置会变成一个只对部分字段生效的按钮。
 */
function snapshot<T extends object>(form: T): T {
  const raw = toRaw(form);
  const out = {} as T;
  for (const key of Object.keys(raw) as (keyof T)[]) {
    const value = raw[key];
    // 日期区间是数组：直接搬引用会让重置后的表单与快照共用同一个数组，改一次快照就脏了
    out[key] = (Array.isArray(value) ? [...value] : value) as T[keyof T];
  }
  return out;
}
