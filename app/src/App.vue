<template>
  <view class="app">
    <!-- uni-app 页面由 pages.json 管理，无需 router-view -->
  </view>
</template>
<script setup>
import { onLaunch, onShow } from '@dcloudio/uni-app'

// v1.1.8+: 自动登录 - 启动时如果有有效 token 则直接进工作台
function tryAutoLogin() {
  let token = ''
  let user = null
  try {
    token = uni.getStorageSync('erp_token') || ''
    const raw = uni.getStorageSync('erp_user')
    user = typeof raw === 'object' ? raw : (raw ? JSON.parse(raw) : null)
  } catch (e) {
    return
  }
  if (!token || !user) return

  // 当前已是工作台/其他业务页, 不重定向
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  const cur = pages && pages.length ? (pages[pages.length - 1].route || '') : ''
  if (cur && cur !== 'pages/login/index') return

  // 已在登录页则直接跳工作台
  uni.reLaunch({ url: '/pages/dashboard/index' })
}

onLaunch(() => {
  console.log('App Launch')
  // 首次启动, 如果有 token 就跳过登录
  tryAutoLogin()
})

onShow(() => {
  // 每次从后台回到前台, 也尝试一次 (处理用户已登录但被路由回登录页的边缘情况)
  tryAutoLogin()
})
</script>
<style>
@import './static/css/common.css';
</style>
