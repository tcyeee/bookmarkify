import type { VxeTableGridOptions } from '@vben/plugins/vxe-table';
import { h } from 'vue';
import { setupVbenVxeTable, useVbenVxeGrid } from '@vben/plugins/vxe-table';
// 必须是**具名**导入：element-plus 的 default 导出是安装器对象 `{ install, version }`，
// 不是组件。`import ElButton from 'element-plus'` 拿到的是那个对象，h() 渲染出来是空的 ——
// 下面两个 renderer 一直如此。样式由 unplugin-element-plus 按具名导入自动注入。
import { ElButton, ElImage } from 'element-plus';
import {
  getAdminGridConfigApi,
  saveAdminGridConfigApi,
} from '#/api/admin-grid-config';
import { useVbenForm } from './form';

setupVbenVxeTable({
  configVxeTable: (vxeUI) => {
    vxeUI.setConfig({
      grid: {
        align: 'left',
        border: false,
        columnConfig: {
          resizable: true,
        },
        // 列宽/显隐/排序持久化到后端，按当前管理员账号 + grid id 隔离
        // 每个表格只需指定唯一的 gridOptions.id 即可自动获得存取能力
        customConfig: {
          storage: true,
          restoreStore: async ({ id }) => {
            const res = await getAdminGridConfigApi(id);
            return res.storeData ?? {};
          },
          updateStore: async ({ id, storeData }) => {
            await saveAdminGridConfigApi(id, storeData);
          },
        },
        minHeight: 180,
        formConfig: {
          // 全局禁用vxe-table的表单配置，使用formOptions
          enabled: false,
        },
        proxyConfig: {
          autoLoad: true,
          response: {
            result: 'items',
            total: 'total',
            list: 'items',
          },
          showActiveMsg: true,
          showResponseMsg: false,
        },
        round: true,
        showOverflow: true,
        size: 'small',
      } as VxeTableGridOptions,
    });

    // 表格配置项可以用 cellRender: { name: 'CellImage' },
    vxeUI.renderer.add('CellImage', {
      renderTableDefault(renderOpts, params) {
        const { props } = renderOpts;
        const { column, row } = params;
        const src = row[column.field];
        return h(ElImage, { src, previewSrcList: [src], ...props });
      },
    });

    // 表格配置项可以用 cellRender: { name: 'CellLink' },
    vxeUI.renderer.add('CellLink', {
      renderTableDefault(renderOpts) {
        const { props } = renderOpts;
        return h(
          ElButton,
          { size: 'small', link: true },
          { default: () => props?.text },
        );
      },
    });

    // 这里可以自行扩展 vxe-table 的全局配置，比如自定义格式化
    // vxeUI.formats.add
  },
  useVbenForm,
});

export { useVbenVxeGrid };

export type * from '@vben/plugins/vxe-table';
