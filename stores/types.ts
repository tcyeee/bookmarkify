export interface KeyEvent {
    currentPath: string,
    triggerFunc: Function,
}

export enum DialogStatus {
    LOGIN = "LOGIN",
    REGISTER = "REGISTER",
}

export enum SocketTypes {
    BOOKMARK_UPDATE_ONE
}