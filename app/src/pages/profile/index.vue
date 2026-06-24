<template>
  <div class="container">
    <div class="card" style="display:flex;align-items:center;gap:12px">
      <div class="avatar">{{ (user?.nickname || 'U').charAt(0) }}</div>
      <div>
        <div style="font-size:18px;font-weight:bold">{{ user?.nickname || user?.username }}</div>
        <div class="muted">{{ user?.deptName || '' }}</div>
      </div>
    </div>
    <div class="card" @click="onLogout" style="cursor:pointer">
      <span style="color:#c0392b">退出登录</span>
    </div>
  </div>
</template>
<script setup>
import { ref } from 'vue'
const user = ref(JSON.parse(localStorage.getItem('erp_user') || '{}'))

function onLogout() {
  if (confirm('确定退出登录?')) {
    localStorage.removeItem('erp_token')
    localStorage.removeItem('erp_user')
    window.location.hash = '#/pages/login/index'
  }
}
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.avatar { width: 60px; height: 60px; border-radius: 50%; background: #1e6091; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 24px; }
.muted { color: #999; font-size: 12px; margin-top: 2px; }
</style>
