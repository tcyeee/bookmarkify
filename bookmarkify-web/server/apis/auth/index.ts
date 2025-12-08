import type { UserInfoEntity } from '@typing'
import http from '../http'

export const authByDeviceInfo = (deviceId: string) =>
  http.get('/auth/loginByDeviceId', { deviceId: deviceId }) as Promise<UserInfoEntity>
