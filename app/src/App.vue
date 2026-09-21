<template>
  <view class="app">
    <!-- uni-app 页面由 pages.json 管理，无需 router-view -->
  </view>
</template>
<script setup>
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { onAppBack } from './utils/nav.js'

// v1.1.8+: Token 由后端 Set-Cookie (httpOnly) 自动管理, 不再从 storage 读取 erp_token.
// 自动登录仅检查 erp_user 是否仍在 localStorage (页面刷新后 Cookie 仍有效).
function tryAutoLogin() {
  let user = null
  try {
    const raw = uni.getStorageSync('erp_user')
    user = typeof raw === 'object' ? raw : (raw ? JSON.parse(raw) : null)
  } catch (e) {
    return
  }
  if (!user) return

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

  // v1.1.54+: 全局拦截 Android 硬件返回键
  // - 工作台 / 登录页 → 放行 (允许退出 App)
  // - 其他任何页面 → 重定向到工作台, 阻止 App 直接退出
  // 注: 仅触发 Android 物理 back 键, 不影响导航栏 back 按钮 (那个仍走 navigateBack)
  try {
    if (typeof plus !== 'undefined' && plus.key && plus.key.addEventListener) {
      plus.key.addEventListener('backbutton', onAppBack)
    }
  } catch (e) {
    console.warn('[App] 注册 backbutton 监听失败:', e)
  }
})

onShow(() => {
  // 每次从后台回到前台, 也尝试一次 (处理用户已登录但被路由回登录页的边缘情况)
  tryAutoLogin()
})
</script>
<style>
@import './static/css/common.css';
</style>
