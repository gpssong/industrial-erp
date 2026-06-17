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
    <view class="card" @click="onLogout">
      <text style="color:var(--danger)">退出登录</text>
    </view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
const user = ref(JSON.parse(uni.getStorageSync('erp_user') || '{}'))
function onLogout() {
  uni.showModal({ title: '提示', content: '确定退出?', success: (r) => {
    if (r.confirm) { uni.removeStorageSync('erp_token'); uni.removeStorageSync('erp_user'); uni.reLaunch({ url: '/pages/login/index' }) }
  }})
}
</script>
