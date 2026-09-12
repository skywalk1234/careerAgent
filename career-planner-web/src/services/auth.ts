import { http } from './http'

export interface LoginPayload {
  account: string
  password: string
  autoLogin: boolean
}

export interface RegisterPayload {
  username: string
  userphone: string
  password: string
}

export function login(payload: LoginPayload) {
  return http.post('/users/login', {
    userphone: payload.account,
    password: payload.password,
    autoLogin: payload.autoLogin,
  })
}

export function register(payload: RegisterPayload) {
  return http.post('/users/register', payload)
}
