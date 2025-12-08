import type { SocketTypes } from './enum'
export interface SocketMessage {
  type: SocketTypes
  data: any
}
