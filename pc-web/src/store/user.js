import { defineStore } from 'pinia'
import { login, logout, me } from '@/api/auth'

/**
 * 安全: token 不再写 localStorage, 由后端 Set-Cookie (httpOnly, SameSite=Lax) 自动携带.
 * 已有的旧 erp_token 在初次启动时清理一次, 之后不再读取.
 */
function clearLegacyLocalStorageTokens() {
  try { localStorage.removeItem('erp_token') } catch (e) {}
}

export const useUserStore = defineStore('user', {
  state: () => {
    clearLegacyLocalStorageTokens()
    // 从 localStorage 恢复 userInfo (页面刷新后 Pinia state 会丢失)
    let savedInfo = null
    try { savedInfo = JSON.parse(localStorage.getItem('erp_user_info') || 'null') } catch (e) {}
    return {
      // token 不存于前端, 由浏览器自动管理 httpOnly cookie
      token: '',
      userInfo: savedInfo,
      permissions: JSON.parse(localStorage.getItem('erp_permissions') || '[]'),
      menus: JSON.parse(localStorage.getItem('erp_menus') || '[]')
    }
  },
  actions: {
    async loginAction(data) {
      const r = await login(data)
      // token 由后端 Set-Cookie 写入, 此处不再保存 to localStorage
      this.token = ''
      this.userInfo = r.data
      this.permissions = r.data.permissions || []
      this.menus = r.data.menus || []
      localStorage.setItem('erp_user_info', JSON.stringify(r.data))
      localStorage.setItem('erp_permissions', JSON.stringify(this.permissions))
      localStorage.setItem('erp_menus', JSON.stringify(this.menus))
      return r.data
    },
    async fetchMe() {
      const r = await me()
      this.userInfo = r.data
      this.permissions = r.data.permissions || []
      this.menus = r.data.menus || []
      localStorage.setItem('erp_user_info', JSON.stringify(r.data))
      localStorage.setItem('erp_permissions', JSON.stringify(this.permissions))
      localStorage.setItem('erp_menus', JSON.stringify(this.menus))
      return r.data
    },
    async logoutAction() {
      try { await logout() } catch (e) {}
      this.token = ''
      this.userInfo = null
      this.permissions = []
      this.menus = []
      // 登出时也要清 cookie — 由后端 setMaxAge=0 来清, 前端这里清其他本地缓存
      localStorage.removeItem('erp_user_info')
      localStorage.removeItem('erp_permissions')
      localStorage.removeItem('erp_menus')
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
