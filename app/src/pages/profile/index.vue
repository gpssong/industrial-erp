<template>
  <div class="container">
    <div class="card" style="display:flex;align-items:center;gap:12px">
      <div class="avatar">{{ (user?.nickname || 'U').charAt(0) }}</div>
      <div>
        <div style="font-size:18px;font-weight:bold">{{ user?.nickname || user?.username }}</div>
        <div class="muted">{{ user?.deptName || '' }}</div>
        <div class="muted" v-if="isAdmin">管理员</div>
      </div>
    </div>

    <!-- 管理员功能 -->
    <div class="card" v-if="isAdmin">
      <div class="section-title">管理功能</div>
      <div class="menu-item" @click="navigateTo('/pages/system/users')">
        <span>👥 用户管理</span><span class="arrow">›</span>
      </div>
    </div>

    <!-- 通用功能 -->
    <div class="card">
      <div class="section-title">设置</div>
      <div class="menu-item" @click="navigateTo('/pages/profile/settings')">
        <span>⚙️ 服务器设置</span><span class="arrow">›</span>
      </div>
    </div>

    <div class="card" @click="onLogout" style="cursor:pointer;text-align:center">
      <span style="color:#c0392b">退出登录</span>
    </div>
  </div>
</template>
<script setup>
import { ref, computed } from 'vue'
import { navigateTo } from '../../utils/nav.js'

const user = ref(JSON.parse(localStorage.getItem('erp_user') || '{}'))

const isAdmin = computed(() => {
  const u = user.value
  if (!u) return false
  if (u.userId === 1 || u.userId === '1' || u.userId === 0 || u.userId === '0') return true
  if (u.isAdmin === 1 || u.isAdmin === true) return true
  return (u.roles || []).includes('SUPER_ADMIN')
})

function onLogout() {
  if (confirm('确定退出登录?')) {
    localStorage.removeItem('erp_token')
    localStorage.removeItem('erp_user')
    localStorage.removeItem('erp_permissions')
    localStorage.removeItem('erp_menus')
    navigateTo('/pages/login/index')
  }
}
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.avatar { width: 60px; height: 60px; border-radius: 50%; background: #1e6091; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 24px; }
.muted { color: #999; font-size: 12px; margin-top: 2px; }
.section-title { font-size: 13px; color: #999; margin-bottom: 8px; }
.menu-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid #f0f0f0; cursor: pointer; }
.menu-item:last-child { border-bottom: none; }
.arrow { color: #ccc; font-size: 18px; }
</style>
