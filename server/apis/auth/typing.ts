export interface LoginParams {
    account: string
    password: string
}

export interface UserEntity {
    token: string
    nickName?: string
    mail?: string
}

export interface UserAuth {
    fingerprint?: string
    deviceUid?: string
    token?: string
}