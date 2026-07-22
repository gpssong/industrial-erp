// 权限工具：检查页面访问权限 + tabBar 动态控制
// P1-3: 默认拒绝 — 未配置权限的敏感页面必须显式 deny

// tabBar 与权限的映射
const TAB_PERMS = {
  '/pages/dashboard/index': '',                          // 工作台: 总能访问
  '/pages/inventory/query': 'inventory:stock:list',      // 库存
  '/pages/scan/in': 'purchase:receipt:list',             // 扫码入库
  '/pages/profile/index': ''                             // 我的: 总能访问
}

// P1-3: 全部业务页面都必须显式声明权限, 没列出的敏感页面默认拒绝
// 之前: `if (!perm) return true` — 默认放行, 攻击者可通过 pages.json 调到未列出的页面
// 现在: 任何敏感页面没在 PAGE_PERMS / TAB_PERMS 里登记 → 默认拒绝
const SENSITIVE_PAGES = new Set([
  '/pages/scan/out',
  '/pages/scan/in',
  '/pages/count/index',
  '/pages/system/users',
  '/pages/production/order-add',
  '/pages/product-add',
  '/pages/change-password',
  '/pages/profile/change-password',
  '/pages/base/product-add',
  '/pages/base/products',
  '/pages/inventory/query',
  '/pages/dashboard/index',
  '/pages/profile/index',
  '/pages/report/index'
])
const PAGE_PERMS = {
  '/pages/scan/out': 'sales:delivery:list',
  '/pages/count/index': 'inventory:stock:list',
  '/pages/system/users': 'system:user:list',
  '/pages/production/order-add': 'production:order:add',
  '/pages/product-add': 'base:product:add',
  '/pages/base/product-add': 'base:product:add',
  '/pages/base/products': 'base:product:list',
  '/pages/change-password': 'system:user:edit',         // 改自己的密码
  '/pages/profile/change-password': 'system:user:edit',
  '/pages/report/index': 'report:view'
}

// 是否为管理员
export function isAdmin() {
  try {
    const raw = uni.getStorageSync('erp_user')
    const u = typeof raw === 'object' ? raw : JSON.parse(raw || '{}')
    if (!u) return false
    if (u.userId === 1 || u.userId === '1' || u.userId === 0 || u.userId === '0') return true
    if (u.isAdmin === 1 || u.isAdmin === true) return true
    return (u.roles || []).includes('SUPER_ADMIN')
  } catch (e) { return false }
}

// 获取权限列表
export function getPermissions() {
  try {
    const raw = uni.getStorageSync('erp_permissions')
    if (typeof raw === 'string') return JSON.parse(raw || '[]')
    if (Array.isArray(raw)) return raw
    return []
  } catch (e) { return [] }
}

/**
 * P1-3: 检查页面访问权限. 默认拒绝语义:
 * - 超管: 直接放行
 * - 敏感页: 必须在 PAGE_PERMS / TAB_PERMS 登记, 否则**拒绝** (之前是放行)
 * - 已登记的: 校验 perms.includes(perm)
 */
export function checkPagePermission(path) {
  if (isAdmin()) return true
  // 公开页 (登录页) 不受权限控制
  const PUBLIC_PAGES = new Set(['/pages/login/index', '/pages/server-settings/index'])
  if (PUBLIC_PAGES.has(path)) return true
  // 敏感页没登记 → 默认拒绝 (修复前是默认放行)
  if (SENSITIVE_PAGES.has(path) && !PAGE_PERMS[path] && !TAB_PERMS[path]) {
    console.warn('[perm] 敏感页面未声明权限, 默认拒绝:', path)
    return false
  }
  const perm = PAGE_PERMS[path] || TAB_PERMS[path] || ''
  if (!perm) return true  // 非敏感页 + 已配置为空 = 公开
  const perms = getPermissions()
  return perms.includes(perm)
}

// 根据权限过滤 tabBar (调用 uni.hideTabBar / showTabBarItem)
export function applyTabBar() {
  if (typeof uni === 'undefined') return
  const tabs = [
    { idx: 0, path: '/pages/dashboard/index', text: '工作台' },
    { idx: 1, path: '/pages/inventory/query', text: '库存', perm: 'inventory:stock:list' },
    { idx: 2, path: '/pages/scan/in', text: '扫码入库', perm: 'purchase:receipt:list' },
    { idx: 3, path: '/pages/profile/index', text: '我的' }
  ]
  const admin = isAdmin()
  const perms = getPermissions()

  tabs.forEach(t => {
    let show = admin
    if (!show) show = !t.perm || perms.includes(t.perm)
    if (show) {
      uni.showTabBarItem({ index: t.idx })
    } else {
      uni.hideTabBarItem({ index: t.idx })
    }
  })
}

// 在 onShow 时调用，保证页面切换时刷新 tabBar
export function onShowWithPermission() {
  return applyTabBar
}
