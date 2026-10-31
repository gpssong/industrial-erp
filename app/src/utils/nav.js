// 统一导航: 原生 App 走 uni API, H5 走 hash 路由
// v1.1.105: 原判断只认 HBuilder 的 `plus`, 会把 Capacitor APK 误判成 H5
//           → 原生 App 里所有跳转退化成 window.location.hash, uni.switchTab/reLaunch 全失效。
//           统一收敛到 utils/runtime.js (先认 window.Capacitor, 再退回 plus)。
import { isNativeApp } from './runtime.js'
import { rememberRoute } from './routeQuery.js'

export function isNative() {
  return isNativeApp()
}

// 实际 tabBar 页面 (与 pages.json 的 tabBar.list 保持一致)
// v1.1.100+: 加工单 tab (order-list) — 原生走 switchTab, H5 走 hash
const TABBAR_PAGES = [
  '/pages/dashboard/index',
  '/pages/inventory/query',
  '/pages/scan/in',
  '/pages/production/order-list',
  '/pages/profile/index'
]

// v1.1.54+: App 首页 = 工作台 (其他 tabBar 页都从工作台跳转过去)
const HOME_PAGE = '/pages/dashboard/index'

// 不允许硬件 back 重定向到工作台的页面 (登录页必须可退出)
const BACK_SKIP_PAGES = new Set([
  '/pages/login/index'
])

export function navigateTo(url) {
  // v1.1.140: 跳转前先登记本次 path+query —— uni-app H5 派发目标页生命周期时, location.hash
  //           可能还是上一页的、框架塞给 onLoad 的 query 也可能是上一趟的, 只有这里记下的才是本次的。
  rememberRoute(url)
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
 * v1.1.106 变更 (重要): 原实现靠 HBuilder 的 `plus.key.addEventListener('backbutton', ...)` 收事件,
 *   而本项目 APK 是 **Capacitor 6**, WebView 里没有 `plus` → 该监听从未注册成功,
 *   onAppBack() 一次都没跑过 (v1.1.60 设计的整套规则表是死代码)。
 *   现改由 @capacitor/app 的 `App.addListener('backButton', ...)` 驱动 (见 App.vue)。
 *
 *   ⚠ 连带语义变化: AppPlugin 注册的是 `OnBackPressedCallback(enabled=true)`,
 *     **只要我们监听了 backButton, Android 默认的"按 back 退出 App"就被禁用**。
 *     因此本函数返回 EXIT 时, 调用方必须显式调 `App.exitApp()` 才能真退出;
 *     以前返回 false 让系统自己退的写法在 Capacitor 下不再成立。
 *
 * 设计目标:
 *   - 区分栈深度: 栈深 >= 3 (工作台 → 列表 → 详情) → navigateBack 回上一页
 *                 栈深 = 2 (工作台 → 详情) → switchTab 回工作台
 *   - 工作台页本身 → 吞掉事件不退出 App
 *   - 登录页 → 退出 (需调用方调 App.exitApp())
 *   - 仅物理 back 拦截, 不影响程序内 uni.navigateBack 跳转
 *
 * 规则表:
 *   1. 登录页 → EXIT (调用方调 App.exitApp() 真正退出)
 *   2. 工作台页 → HANDLED (吞掉事件, 不退出 App)
 *   3. 栈深 >= 3 → uni.navigateBack({delta:1}) + HANDLED
 *   4. 栈深 = 2 → uni.switchTab(HOME_PAGE) + HANDLED
 *   兜底 (栈深=1 但非工作台) → uni.switchTab(HOME_PAGE) + HANDLED
 *
 * 历史:
 *   v1.1.54~v1.1.59: 任何非工作台页面都直接 switchTab 工作台 (粗暴跳过中间栈)
 *   v1.1.60: 区分栈深度, 详情先回列表再回工作台, 工作台吞掉事件不退出
 *   v1.1.106: 事件源从 plus.key 换成 @capacitor/app, 返回值语义 boolean → ACTION 常量
 */

/** back 事件已被本 App 处理 (不退出) */
export const BACK_HANDLED = 'handled'
/** 需要真正退出 App (调用方负责调 App.exitApp()) */
export const BACK_EXIT = 'exit'

export function onAppBack() {
  // 仅原生 App 生效 (H5 走浏览器历史, 不接管)
  if (!isNative()) return BACK_HANDLED

  let pages = []
  try {
    pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  } catch (e) {
    return BACK_HANDLED  // 异常兜底: 不让 App 卡在无法退出的状态
  }

  if (!pages || pages.length === 0) return BACK_EXIT

  const cur = pages[pages.length - 1]
  const curRoute = cur ? ('/' + (cur.route || '')) : ''
  const stackLen = pages.length

  // === 规则 1: 登录页 → 退出 App ===
  // 无内容可返回, 且系统默认行为已被 AppPlugin 禁用 → 必须显式 exitApp
  if (curRoute === '/pages/login/index') return BACK_EXIT

  // === 规则 2: 工作台页 (HOME) → 吞掉事件不退出 ===
  // 工作台 = App 唯一常驻首页, 用户预期"按 back 不动"
  // 退出 App 应通过: 系统多任务右上角滑掉 或 我的 → 退出登录
  if (curRoute === HOME_PAGE) return BACK_HANDLED

  // === 规则 3: 栈深 >= 3 → navigateBack 回上一页 ===
  // 例: 工作台 → 列表(栈深=2) → 详情(栈深=3), 详情按 back → navigateBack 回列表
  //     列表再按 back → 命中栈深=2 走规则 4 switchTab 工作台
  if (stackLen >= 3) {
    uni.navigateBack({ delta: 1 })
    return BACK_HANDLED
  }

  // === 规则 4: 栈深 = 2 → switchTab 回工作台 ===
  // 例: 工作台 → 列表(栈深=2) 按 back → switchTab 工作台
  // 例: 工作台 → 详情(栈深=2) 按 back → switchTab 工作台
  // 用 switchTab 而非 navigateBack: 因为 push 来的列表/详情不是 tabBar,
  //   pop 后栈空可能被框架弹回登录页, switchTab 更安全
  if (stackLen === 2) {
    uni.switchTab({ url: HOME_PAGE })
    return BACK_HANDLED
  }

  // === 兜底: 栈深 = 1 但不是工作台 (理论不该出现, 防御性) ===
  // 例: uni.reLaunch 跳详情后栈重置为 1, 此时走 switchTab 工作台
  uni.switchTab({ url: HOME_PAGE })
  return BACK_HANDLED
}
