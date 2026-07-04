// 权限工具: 检查页面访问权限 + tabBar 动态控制

// tabBar 与权限的映射
const TAB_PERMS = {
  '/pages/dashboard/index': '',  // 工作台: 总能访问
  '/pages/inventory/query': 'inventory:stock:list',
  '/pages/sales/quick': 'sales:delivery:list',
  '/pages/report/index': '',  // 报表: 总能访问
  '/pages/profile/index': ''   // 我的: 总能访问
}

// 页面与权限的映射 (非 tabBar 页面)
const PAGE_PERMS = {
  '/pages/scan/in': 'purchase:receipt:list',
  '/pages/scan/out': 'sales:delivery:list',
  '/pages/count/index': 'inventory:stock:list',
  '/pages/sales/order': 'sales:order:list',
  '/pages/purchase/order': 'purchase:order:list',
  '/pages/system/users': 'system:user:list'
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

// 检查页面访问权限
export function checkPagePermission(path) {
  if (isAdmin()) return true
  const perm = PAGE_PERMS[path] || TAB_PERMS[path] || ''
  if (!perm) return true  // 没配置权限 = 总能访问
  const perms = getPermissions()
  return perms.includes(perm)
}

// 根据权限过滤 tabBar (调用 uni.hideTabBar / showTabBarItem)
export function applyTabBar() {
  if (typeof uni === 'undefined') return
  const tabs = [
    { idx: 0, path: '/pages/dashboard/index', text: '工作台' },
    { idx: 1, path: '/pages/inventory/query', text: '库存', perm: 'inventory:stock:list' },
    { idx: 2, path: '/pages/sales/quick', text: '开单', perm: 'sales:delivery:list' },
    { idx: 3, path: '/pages/report/index', text: '报表' },
    { idx: 4, path: '/pages/profile/index', text: '我的' }
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

// 在 onShow 时调用, 保证页面切换时刷新 tabBar
export function onShowWithPermission() {
  return applyTabBar
}
