<template>
  <div class="login-page">
    <div class="header">
      <div class="logo">🏭</div>
      <div class="title">工业ERP</div>
      <div class="subtitle">薄膜/塑料/五金/加工</div>
    </div>
    <div class="form card">
      <div class="form-item">
        <label class="label">账号</label>
        <input class="input" type="text" v-model="form.username" placeholder="请输入账号" />
      </div>
      <div class="form-item">
        <label class="label">密码</label>
        <input class="input" type="password" v-model="form.password" placeholder="请输入密码" />
      </div>
      <button class="btn btn-block" @click="onLogin">登 录</button>
    </div>
    <div class="muted" style="text-align:center;margin-top:20px">默认 admin / admin123</div>
    <div class="muted" style="text-align:center;margin-top:6px;font-size:11px">v1.0.10+ PC/App端权限分离</div>
    <!-- 服务器设置(折叠) -->
    <div class="card server-section">
      <div class="server-toggle" @click="showServer = !showServer">
        <span>⚙️ 服务器设置</span>
        <span class="arrow" :class="{ open: showServer }">▼</span>
        <span class="current-url muted">{{ currentDisplay }}</span>
      </div>
      <div v-show="showServer" class="server-body">
        <div class="form-item">
          <label class="label">API 地址</label>
          <input class="input" type="text" v-model="apiBase" :placeholder="isNativePlatform ? '留空使用默认: ' + nativeDefault : '留空使用默认: /api'" />
        </div>
        <div class="btn-row">
          <button class="btn btn-sm" @click="onSaveServer">保存</button>
          <button class="btn btn-sm btn-outline" @click="onResetServer">恢复默认</button>
        </div>
        <div class="muted" style="font-size:11px;margin-top:6px">当前：{{ currentDisplay }}</div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import api from '../../api/index.js'
import { navigateTo } from '../../utils/nav.js'
const form = reactive({ username: '', password: '' })

// 服务器设置
const showServer = ref(false)
const nativeDefault = 'http://home.93gushi.com:8088/api'
const apiBase = ref('')
const currentDisplay = ref('')

function onSaveServer() {
  // 已停用: API 地址硬编码, 不再读取 localStorage
}
function onResetServer() {
  // 已停用
}

onMounted(() => {})

async function onLogin() {
  try {
    const r = await api.login(form)
    // v1.0.10+: 分离 PC/App 菜单 — App 端只使用 appMenus
    const persist = (k, v) => {
      const s = typeof v === 'string' ? v : JSON.stringify(v)
      try { if (typeof uni !== 'undefined' && uni.setStorageSync) uni.setStorageSync(k, v) } catch (e) {}
      try { localStorage.setItem(k, s) } catch (e) {}
    }
    persist('erp_token', r.token || '')
    persist('erp_user', r)
    persist('erp_permissions', r.permissions || [])
    // v1.0.10+: 优先使用 appMenus, 兼容旧版 menus 字段
    persist('erp_menus', r.appMenus || r.menus || [])
    persist('erp_client_scope', r.clientScope || 'BOTH')

    // v1.0.10+: clientScope 检查 — 如果角色被限制为仅 PC, 不允许 App 登录
    const scope = r.clientScope || 'BOTH'
    if (scope === 'PC') {
      alert('该账号仅限 PC 端登录, 请使用电脑浏览器访问')
      return
    }

    // 二次拉取 /me 确保菜单数据最新 (兼容老登录)
    try {
      const me = await api.me()
      const userObj = me.data || me
      persist('erp_user', userObj)
      // App 端: /me 也取 appMenus
      persist('erp_menus', userObj.appMenus || userObj.menus || r.appMenus || r.menus || [])
      persist('erp_permissions', userObj.permissions || r.permissions || [])
    } catch (e) { /* 忽略, 使用登录返回数据 */ }
    navigateTo('/pages/dashboard/index')
  } catch (e) {
    console.error('[LOGIN] 登录失败:', e)
    alert('登录失败: ' + (e.msg || e.message || JSON.stringify(e)))
  }
}
</script>
<style scoped>
.login-page { padding: 40px 20px; max-width: 400px; margin: 0 auto; }
.header { text-align: center; margin-bottom: 30px; }
.logo { font-size: 60px; }
.title { font-size: 22px; font-weight: bold; color: #1e6091; margin-top: 8px; }
.subtitle { font-size: 12px; color: #999; margin-top: 4px; }
.form-item { margin-bottom: 14px; }
.label { display: block; font-size: 13px; color: #666; margin-bottom: 4px; }
.input { width: 100%; height: 40px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 12px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 16px; width: 100%; }
.btn:hover { background: #2980b9; }
.btn-sm { width: auto; padding: 6px 16px; font-size: 13px; }
.btn-outline { background: transparent; color: #1e6091; border: 1px solid #1e6091; }
.btn-outline:hover { background: #1e6091; color: #fff; }
.btn-row { display: flex; gap: 8px; margin-top: 8px; }
.muted { color: #999; font-size: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.server-section { margin-top: 16px; }
.server-toggle { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; }
.server-toggle .arrow { transition: transform 0.2s; font-size: 10px; }
.server-toggle .arrow.open { transform: rotate(180deg); }
.server-toggle .current-url { margin-left: auto; font-size: 11px; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.server-body { margin-top: 12px; padding-top: 12px; border-top: 1px solid #eee; }
</style>
