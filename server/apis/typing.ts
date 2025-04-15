export interface UserInfoShow {
    uid: String
    nickName: String
    phone?: String
    email?: String
    avatarPath?: String
}

export interface UserInfoUpdate {
    nickName?: String
    phone?: String
}