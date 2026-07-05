<template>
  <div class="container">
    <div class="card" style="display:flex;align-items:center;gap:12px">
      <div class="avatar">{{ avatarChar }}</div>
      <div>
        <div style="font-size:18px;font-weight:bold">{{ displayName }}</div>
        <div class="muted">{{ user?.deptName || '' }}</div>
        <div class="muted" v-if="user?.roles && user.roles.length">
          {{ user.roles.map(r => roleLabel(r)).join(' / ') }}
        </div>
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
import { ref, computed, onMounted } from 'vue'
import { navigateTo } from '../../utils/nav.js'
import { applyTabBar, isAdmin } from '../../utils/permission.js'

const user = ref({})

// 强制从 localStorage 重新解析 (避免 Capacitor 缓存)
function loadUser() {
  try {
    const raw = localStorage.getItem('erp_user')
    user.value = raw ? JSON.parse(raw) : {}
  } catch (e) { user.value = {} }
}

const displayName = computed(() => {
  const u = user.value
  if (!u) return '未登录'
  return u.nickname || u.username || '用户'
})

const avatarChar = computed(() => {
  return (displayName.value || 'U').charAt(0).toUpperCase()
})

function roleLabel(r) {
  const map = {
    SUPER_ADMIN: '超级管理员',
    SALES_MGR: '销售经理',
    PURCHASE_MGR: '采购经理',
    WAREHOUSE_MGR: '仓库主管',
    PRODUCTION_MGR: '生产主管',
    FINANCE: '财务'
  }
  return map[r] || r
}

function onLogout() {
  if (confirm('确定退出登录?')) {
    localStorage.removeItem('erp_token')
    localStorage.removeItem('erp_user')
    localStorage.removeItem('erp_permissions')
    localStorage.removeItem('erp_menus')
    navigateTo('/pages/login/index')
  }
}

onMounted(() => {
  loadUser()
  applyTabBar()
})
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
