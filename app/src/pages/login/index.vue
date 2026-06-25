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
          <input class="input" type="text" v-model="apiBase" placeholder="留空使用默认: /api" />
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
const form = reactive({ username: 'admin', password: 'admin123' })

// 服务器设置
const showServer = ref(false)
const nativeDefault = 'http://home.93gushi.com:8088/api'
const defaultLabel = typeof plus !== 'undefined' ? nativeDefault + ' (默认)' : '/api (默认)'
const apiBase = ref(localStorage.getItem('erp_api_base') || '')
const currentDisplay = ref(localStorage.getItem('erp_api_base') || defaultLabel)

function onSaveServer() {
  const val = apiBase.value.trim()
  if (val) {
    localStorage.setItem('erp_api_base', val)
    currentDisplay.value = val
    alert('已保存: ' + val + '\n重新登录后生效')
  } else {
    onResetServer()
  }
}
function onResetServer() {
  localStorage.removeItem('erp_api_base')
  apiBase.value = ''
  currentDisplay.value = defaultLabel
  alert('已恢复默认')
}

onMounted(() => {
  console.log('[LOGIN] 页面已挂载, form=', JSON.stringify(form))
})

async function onLogin() {
  console.log('[LOGIN] 点击登录, form=', JSON.stringify(form))
  try {
    const r = await api.login(form)
    console.log('[LOGIN] 登录成功:', r)
    localStorage.setItem('erp_token', r.token)
    localStorage.setItem('erp_user', JSON.stringify(r))
    // 保存权限和菜单
    localStorage.setItem('erp_permissions', JSON.stringify(r.permissions || []))
    localStorage.setItem('erp_menus', JSON.stringify(r.menus || []))
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
