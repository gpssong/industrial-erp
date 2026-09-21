// 统一导航: 原生 App 走 uni API, H5 走 hash 路由
export function isNative() {
  return typeof plus !== 'undefined'
}

// 实际 tabBar 页面 (与 pages.json 的 tabBar.list 保持一致)
const TABBAR_PAGES = [
  '/pages/dashboard/index',
  '/pages/inventory/query',
  '/pages/scan/in',
  '/pages/profile/index'
]

// v1.1.54+: App 首页 = 工作台 (其他 tabBar 页都从工作台跳转过去)
const HOME_PAGE = '/pages/dashboard/index'

// 不允许硬件 back 重定向到工作台的页面 (登录页必须可退出)
const BACK_SKIP_PAGES = new Set([
  '/pages/login/index'
])

export function navigateTo(url) {
  if (isNative()) {
    if (TABBAR_PAGES.includes(url)) {
      uni.switchTab({ url })
    } else {
      uni.reLaunch({ url })
    }
  } else {
    window.location.hash = '#' + url
  }
}

/**
 * v1.1.54+: 统一硬件 back 处理
 * - 在工作台 (App 首页) 上按 back → 允许系统退出 App (返回 false)
 * - 在登录页 → 允许系统退出 App (登录前用户应能退出)
 * - 在其他任何页面 → 重定向到工作台 (返回 true 阻止系统退出)
 *
 * 在 App.vue onLaunch 注册: plus.key.addEventListener('backbutton', onAppBack)
 */
export function onAppBack() {
  // 仅原生 App 生效 (H5 走浏览器历史, 不接管)
  if (!isNative()) return false

  let pages = []
  try {
    pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  } catch (e) {
    return false
  }
  const cur = pages && pages.length ? ('/' + (pages[pages.length - 1].route || '')) : ''

  // 在工作台 / 登录页 → 放行 (允许退出 App)
  if (!cur || cur === HOME_PAGE || BACK_SKIP_PAGES.has(cur)) {
    return false
  }

  // 其他页 → 跳工作台
  uni.switchTab({ url: HOME_PAGE })
  return true
}
