export interface LoginParams {
    account: string
    password: string
}

export interface UserEntity {
    uid: string,
    token: string
    nickName?: string
    mail?: string
}

export interface UserAuthParams {
    deviceUid: string
    token?: string
}