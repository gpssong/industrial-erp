<template>
  <view class="container">
    <view class="card" style="display:flex;align-items:center;gap:12px">
      <view style="width:60px;height:60px;border-radius:50%;background:var(--primary);color:#fff;display:flex;align-items:center;justify-content:center;font-size:24px">
        {{ (user?.nickname || 'U').charAt(0) }}
      </view>
      <view>
        <text style="font-size:18px;font-weight:bold">{{ user?.nickname || user?.username }}</text>
        <text class="muted" style="display:block">{{ user?.deptName }}</text>
      </view>
    </view>
    <view class="card" @click="onSetServer">
      <view class="row">
        <text>服务器地址</text>
        <text class="muted">{{ server }}</text>
      </view>
    </view>
    <view class="card" @click="onLogout">
      <text style="color:var(--danger)">退出登录</text>
    </view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
const user = ref(JSON.parse(uni.getStorageSync('erp_user') || '{}'))
const server = ref(uni.getStorageSync('erp_server') || '未设置')
function onSetServer() {
  uni.showModal({
    title: '设置服务器',
    editable: true,
    placeholderText: 'http://192.168.1.100:8080/api',
    success: (r) => {
      if (r.confirm && r.content) {
        const val = r.content.trim()
        uni.setStorageSync('erp_server', val)
        server.value = val
        uni.showToast({ title: '已保存，请重启APP', icon: 'none' })
      }
    }
  })
}
function onLogout() {
  uni.showModal({ title: '提示', content: '确定退出?', success: (r) => {
    if (r.confirm) { uni.removeStorageSync('erp_token'); uni.removeStorageSync('erp_user'); uni.reLaunch({ url: '/pages/login/index' }) }
  }})
}
</script>
