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
 * v1.1.60+: 统一硬件 back 处理 — 区分栈深度 + 工作台吞掉事件
 *
 * 设计目标:
 *   - 区分栈深度: 栈深 >= 3 (工作台 → 列表 → 详情) → navigateBack 回上一页
 *                 栈深 = 2 (工作台 → 详情) → switchTab 回工作台
 *   - 工作台页本身 → 吞掉事件不退出 App
 *   - 登录页 → 放行 (允许退出 App)
 *   - 仅物理 back 拦截, 不影响程序内 uni.navigateBack 跳转
 *
 * 规则表:
 *   1. 登录页 → return false (放行系统退出)
 *   2. 工作台页 → return true (吞掉事件, 不退出 App)
 *   3. 栈深 >= 3 → uni.navigateBack({delta:1}) + return true
 *   4. 栈深 = 2 → uni.switchTab(HOME_PAGE) + return true
 *   兜底 (栈深=1 但非工作台) → uni.switchTab(HOME_PAGE) + return true
 *
 * 在 App.vue onLaunch 注册: plus.key.addEventListener('backbutton', onAppBack)
 *
 * 历史:
 *   v1.1.54~v1.1.59: 任何非工作台页面都直接 switchTab 工作台 (粗暴跳过中间栈)
 *   v1.1.60: 区分栈深度, 详情先回列表再回工作台, 工作台吞掉事件不退出
 */
export function onAppBack() {
  // 仅原生 App 生效 (H5 走浏览器历史, 不接管)
  if (!isNative()) return false

  let pages = []
  try {
    pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  } catch (e) {
    return false  // 异常兜底: 放行系统默认行为
  }

  if (!pages || pages.length === 0) return false

  const cur = pages[pages.length - 1]
  const curRoute = cur ? ('/' + (cur.route || '')) : ''
  const stackLen = pages.length

  // === 规则 1: 登录页 → 放行 (允许退出 App) ===
  if (curRoute === '/pages/login/index') return false

  // === 规则 2: 工作台页 (HOME) → 吞掉事件不退出 ===
  // 工作台 = App 唯一常驻首页, 用户预期"按 back 不动"
  // 退出 App 应通过: 系统多任务右上角滑掉 或 我的 → 退出登录
  if (curRoute === HOME_PAGE) return true

  // === 规则 3: 栈深 >= 3 → navigateBack 回上一页 ===
  // 例: 工作台 → 列表(栈深=2) → 详情(栈深=3), 详情按 back → navigateBack 回列表
  //     列表再按 back → 命中栈深=2 走规则 4 switchTab 工作台
  if (stackLen >= 3) {
    uni.navigateBack({ delta: 1 })
    return true
  }

  // === 规则 4: 栈深 = 2 → switchTab 回工作台 ===
  // 例: 工作台 → 列表(栈深=2) 按 back → switchTab 工作台
  // 例: 工作台 → 详情(栈深=2) 按 back → switchTab 工作台
  // 用 switchTab 而非 navigateBack: 因为 push 来的列表/详情不是 tabBar,
  //   pop 后栈空可能被框架弹回登录页, switchTab 更安全
  if (stackLen === 2) {
    uni.switchTab({ url: HOME_PAGE })
    return true
  }

  // === 兜底: 栈深 = 1 但不是工作台 (理论不该出现, 防御性) ===
  // 例: uni.reLaunch 跳详情后栈重置为 1, 此时走 switchTab 工作台
  uni.switchTab({ url: HOME_PAGE })
  return true
}
