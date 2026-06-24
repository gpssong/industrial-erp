import { defineStore } from 'pinia'
import { login, logout, me } from '@/api/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('erp_token') || '',
    userInfo: null,
    permissions: [],
    menus: []
  }),
  actions: {
    async loginAction(data) {
      const r = await login(data)
      this.token = r.data.token
      this.userInfo = r.data
      this.permissions = r.data.permissions || []
      this.menus = r.data.menus || []
      localStorage.setItem('erp_token', this.token)
      return r.data
    },
    async fetchMe() {
      const r = await me()
      this.userInfo = r.data
      this.permissions = r.data.permissions || []
      this.menus = r.data.menus || []
      return r.data
    },
    async logoutAction() {
      try { await logout() } catch (e) {}
      this.token = ''
      this.userInfo = null
      this.permissions = []
      this.menus = []
      localStorage.removeItem('erp_token')
    },
    hasPerm(p) {
      if (!p) return true
      // 管理员直接放行 (userId 兼容字符串/数字; isAdmin 兼容数字/布尔; 角色兜底)
      const uid = this.userInfo?.userId
      if (uid === 1 || uid === '1' || uid === 0 || uid === '0') return true
      if (this.userInfo?.isAdmin === 1 || this.userInfo?.isAdmin === true) return true
      if ((this.userInfo?.roles || []).includes('SUPER_ADMIN')) return true
      return (this.permissions || []).includes(p)
    }
  }
})
