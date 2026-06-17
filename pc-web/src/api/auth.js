import request from '@/utils/request'

export const login = (data) => request.post('/auth/login', data)
export const logout = () => request.post('/auth/logout')
export const me = () => request.get('/auth/me')
export const captcha = () => request.get('/auth/captcha')
