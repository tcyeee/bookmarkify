import type { AiCallScene } from '#/api/ai-call-log';

/** 场景中文名。列表筛选、表格列、详情弹窗共用一份，避免三处各写各的。 */
export const AI_SCENE_LABEL: Record<AiCallScene, string> = {
  APP_NAME: '品牌简称提取',
  CATEGORY_INFER: '分类推断',
  CATEGORY_PROPOSE: '分类提议',
  SIMILAR_SITE: '相似网站推荐',
  NSFW_CHECK: '内容风险检测',
  CONTENT_REVIEW: '分享内容审核',
};

/** 场景释义，鼠标悬浮在"场景"表头时展示 */
export const AI_SCENE_LEGEND: Array<[AiCallScene, string]> = [
  ['APP_NAME', '抓取后从网站标题中提取品牌简称，写入 bookmark.app_name'],
  ['CATEGORY_INFER', '自动抓取链路：只能从已有分类词表里挑，不会新建分类'],
  ['CATEGORY_PROPOSE', '管理员在后台手动触发：允许模型提出词表里没有的新分类'],
  ['SIMILAR_SITE', '按纯模型知识推荐功能/定位相似的其它网站，不联网'],
  ['NSFW_CHECK', '判断网站是否涉及成人/赌博等不宜内容；调用失败按放行处理'],
  ['CONTENT_REVIEW', '分享发布前的合规审核；调用失败按不通过处理'],
];
