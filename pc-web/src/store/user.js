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
      if (this.userInfo?.userId === 1 || this.userInfo?.isAdmin === 1) return true
      return (this.permissions || []).includes(p)
    }
  }
})
