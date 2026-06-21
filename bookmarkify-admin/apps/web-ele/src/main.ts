import { initPreferences, updatePreferences } from '@vben/preferences';
import { unmountGlobalLoading } from '@vben/utils';

import { overridesPreferences } from './preferences';

/**
 * 应用初始化完成之后再进行页面加载渲染
 */
async function initApplication() {
  // name用于指定项目唯一标识
  // 用于区分不同项目的偏好设置以及存储数据的key前缀以及其他一些需要隔离的数据
  const env = import.meta.env.PROD ? 'prod' : 'dev';
  const appVersion = import.meta.env.VITE_APP_VERSION;
  const namespace = `${import.meta.env.VITE_APP_NAMESPACE}-${appVersion}-${env}`;

  // app偏好设置初始化
  await initPreferences({
    namespace,
    overrides: overridesPreferences,
  });

  // defaultHomePath 属于构建期配置：偏好设置的合并优先级是 缓存 > overrides，
  // 旧版本残留在 localStorage 的 /workspace（对应路由已删除）会覆盖 overrides，
  // 导致根路由 / 和 404 页“返回首页”重定向到不存在的路由而停留在 404。
  // 这里在 bootstrap（其内部 import 路由表）之前用 overrides 的值强制覆盖回来。
  const homePath = overridesPreferences.app?.defaultHomePath;
  if (homePath) {
    updatePreferences({ app: { defaultHomePath: homePath } });
  }

  // 启动应用并挂载
  // vue应用主要逻辑及视图
  const { bootstrap } = await import('./bootstrap');
  await bootstrap(namespace);

  // 移除并销毁loading
  unmountGlobalLoading();
}

initApplication();
