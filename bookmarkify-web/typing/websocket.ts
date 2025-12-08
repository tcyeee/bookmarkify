import type { SocketTypes } from './enum'

export interface KeyEvent {
  currentPath: string
  triggerFunc: Function
}

export interface SocketMessage {
  type: SocketTypes
  data: any
}
