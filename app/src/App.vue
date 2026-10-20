<template>
  <view class="app">
    <!-- uni-app 页面由 pages.json 管理，无需 router-view -->
  </view>
</template>
<script setup>
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { onAppBack, navigateTo, BACK_EXIT } from './utils/nav.js'
import { loadFromServer as loadAppModuleConfig } from './utils/appModuleEnabled.js'
import { installScanLifecycle } from './utils/scan.js'
import { isNativeApp } from './utils/runtime.js'
// v1.1.106: 静态 import — @capacitor/app 提供硬件返回键事件源 (App.addListener('backButton'))。
//   不能动态 import: vite 会把它重写成带 hash 的相对 chunk 名, 而 Capacitor 本地文件服务器
//   按裸模块名解析, 打不开 (实测 "Failed to resolve module specifier '..-node_modules-..')。
//   静态 import 会打进主 bundle 随 APK 走, H5 侧 registerBackButton() 有 isNativeApp() 短路, 不会误触发。
import { App as CapApp } from '@capacitor/app'

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
  // H5 运行时 uni.reLaunch 在 onLaunch 阶段 (uni-h5 router 首个 page 未挂好) 会 reject 'reLaunch:fail',
  // 用 navigateTo (原生走 uni, H5走 hash 路由) 兜底, 行为不变且 H5 不再报错.
  navigateTo('/pages/dashboard/index')
}

// v1.1.106: 全局拦截 Android 硬件返回键
//   - 工作台 / 已登录页 → 吞掉事件不退出 App
//   - 其他页面 → 按栈深 navigateBack / switchTab 工作台
//   - 登录页 → 真正退出 App
// 注: 仅触发 Android 物理 back 键, 不影响导航栏 back 按钮 (那个仍走 navigateBack)
//
// ⚠ v1.1.105 的 bug: 原实现是 `plus.key.addEventListener('backbutton', onAppBack)`。
//   `plus` 是 HBuilder 原生运行时的全局, 本项目 APK 是 **Capacitor 6**, WebView 里没有 `plus`
//   → 该监听从未注册, onAppBack() 一次都没执行过 (v1.1.60 设计的整套规则表是死代码)。
//   实测: 工作台按硬件 back 直接被系统关掉 App。
//   现改用 @capacitor/app 的 App.addListener('backButton') (Capacitor 官方通道)。
//
// ⚠ 连带语义: AppPlugin 注册的是 OnBackPressedCallback(enabled=true), 一旦我们监听 backButton,
//   Android 默认"按 back 退出 App"就被接管。所以 onAppBack() 返回 BACK_EXIT 时必须显式调
//   App.exitApp(), 否则 App 会卡在"按 back 没反应"。
async function registerBackButton() {
  if (!isNativeApp()) return
  try {
    // v1.1.106: 静态 import 的 CapApp, 直接注册
    await CapApp.addListener('backButton', () => {
      if (onAppBack() === BACK_EXIT) {
        CapApp.exitApp().catch(() => { /* 退出失败无补救手段 */ })
      }
    })
    console.log('[App] backButton 监听已注册 (Capacitor)')
  } catch (e) {
    console.warn('[App] 注册 backButton 监听失败:', e)
  }
}

onLaunch(() => {
  console.log('App Launch')
  // 首次启动, 如果有 token 就跳过登录
  tryAutoLogin()

  // v1.1.80+: 启动时拉 App 模块开关 (后台静默, 失败 fallback 全开)
  // 必须在 tryAutoLogin 之后, 避免 login 页触发时延迟跳转
  loadAppModuleConfig().catch(() => { /* 静默 */ })

  // v1.1.106: 扫码页 hide 时自动关摄像头 (幂等, 只装一次)
  installScanLifecycle()

  registerBackButton()
})

onShow(() => {
  // 每次从后台回到前台, 也尝试一次 (处理用户已登录但被路由回登录页的边缘情况)
  tryAutoLogin()
  // v1.1.80+: 切前台时刷新 App 模块配置 (5 min TTL 内不重复打)
  loadAppModuleConfig().catch(() => { /* 静默 */ })
})
</script>
<style>
@import './static/css/common.css';
</style>
