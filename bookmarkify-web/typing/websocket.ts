import type { SocketTypes } from './enum'
import type { UserLayoutNodeVO } from './bookmark'

// 服务端推送的实时消息（按 type 区分的可辨识联合）
export interface HomeItemUpdateMessage {
  type: SocketTypes.HOME_ITEM_UPDATE
  data: UserLayoutNodeVO
}

export type SocketMessage = HomeItemUpdateMessage
