export interface KeyEvent {
  currentPath: string
  triggerFunc: Function
}

export enum DialogStatus {
  LOGIN = 'LOGIN',
  REGISTER = 'REGISTER',
}

export enum SocketTypes {
  BOOKMARK_UPDATE_ONE = 'BOOKMARK_UPDATE_ONE',
}

export interface SocketMessage {
  type: SocketTypes
  data: any
}
