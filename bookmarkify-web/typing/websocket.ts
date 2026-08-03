import type { SocketTypes, ShareStatus } from './enum'
import type { UserLayoutNodeVO } from './bookmark'

// 服务端推送的实时消息（按 type 区分的可辨识联合）

// 单个书签节点的内容更新：data 必为 type=BOOKMARK 且 typeApp 非空
export interface HomeItemUpdateMessage {
  type: SocketTypes.HOME_ITEM_UPDATE
  data: UserLayoutNodeVO
}

// 单个文件夹节点 + 其直接子节点（data.children）
export interface HomeDirUpdateMessage {
  type: SocketTypes.HOME_DIR_UPDATE
  data: UserLayoutNodeVO
}

// 整棵桌面布局树的根节点（形状同 /bookmark/query 的返回）
export interface HomeLayoutRefreshMessage {
  type: SocketTypes.HOME_LAYOUT_REFRESH
  data: UserLayoutNodeVO
}

export interface ShareStatusChangedMessage {
  type: SocketTypes.SHARE_STATUS_CHANGED
  data: {
    id: string
    status: ShareStatus
    rejectReason?: string | null
  }
}

export type SocketMessage =
  | HomeItemUpdateMessage
  | HomeDirUpdateMessage
  | HomeLayoutRefreshMessage
  | ShareStatusChangedMessage
