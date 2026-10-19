// 权限工具：检查页面访问权限 + tabBar 动态控制
// P1-3: 默认拒绝 — 未配置权限的敏感页面必须显式 deny
// v1.1.80+: 叠加 "App模块开关" (admin 全局一刀切) — 模块级 + perm 级双重过滤
import { isModuleEnabled as isAppModuleEnabled, isPermEnabled as isAppPermEnabled } from './appModuleEnabled.js'

// tabBar 与权限的映射 (含模块归属 — v1.1.80+)
//  module: 'inventory' / 'purchase' — v1.1.80+ 用于模块开关过滤
//  perm: 原 perm 字符串 (R9 之后 PC+APP 混合 perm, App 端按钮级用 isPermEnabled)
// v1.1.100+: tabBar 5 项 (工作台/库存/原料入库/工单/我的) — 与 pages.json tabBar.list 严格对齐.
// H5 运行时 uni.showTabBarItem/hideTabBarItem 不存在 (tabBar 由 pages.json 静态渲染), applyTabBar 走 method-existence 守卫直接 return.
// 原生/小程序运行时按下面 tabs 数组做动态显隐.
const TAB_PERMS = {
  '/pages/dashboard/index':        { perm: '',                   module: '' },         // 工作台: 总能访问 (内部区块自己控制)
  '/pages/inventory/query':       { perm: 'inventory:stock:list', module: 'inventory' },// 库存
  '/pages/scan/in':               { perm: 'purchase:receipt:list', module: 'purchase' },// 原料入库
  // v1.1.100+: 工单 tab → 生产加工单列表页 (复用现有 order-list), perm=production:order:list
  '/pages/production/order-list': { perm: 'production:order:list', module: 'production' },
  '/pages/profile/index':          { perm: '',                   module: '' }          // 我的: 总能访问
}

// P1-3: 全部业务页面都必须显式声明权限, 没列出的敏感页面默认拒绝
// 之前: `if (!perm) return true` — 默认放行, 攻击者可通过 pages.json 调到未列出的页面
// 现在: 任何敏感页面没在 PAGE_PERMS / TAB_PERMS 里登记 → 默认拒绝
// v1.1.80+: 加上 module 字段 — 用于"App模块开关"过滤
const SENSITIVE_PAGES = new Set([
  '/pages/scan/out',
  '/pages/scan/in',
  '/pages/count/index',
  '/pages/system/users',
  '/pages/production/order-add',
  '/pages/production/order-list',
  '/pages/production/order-detail',
  // v1.1.100+: 工单 tab (order-list 现在是 tabBar 页, 敏感页登记 + perm 走 production:order:list)
  '/pages/product-add',
  '/pages/change-password',
  '/pages/profile/change-password',
  '/pages/base/product-add',
  '/pages/base/products',
  '/pages/inventory/query',
  '/pages/dashboard/index',
  '/pages/profile/index',
  '/pages/report/index',
  // v1.1.14+: 销售出库单查询 (列表/详情共用 sales:delivery:list)
  '/pages/sales/delivery-list',
  '/pages/sales/delivery-detail',
  // v1.1.15+: 采购入库单查询 (列表/详情共用 purchase:receipt:list)
  '/pages/purchase/receipt-list',
  '/pages/purchase/receipt-detail',
  // v1.1.76+: AI 助手
  '/pages/report/ai',
  // v1.1.80 MES: 扫码报工
  '/pages/production/report',
  // v1.1.99 C4: 生产排程 + 设备 OEE
  '/pages/production/schedule',
  '/pages/production/oee',
  // v1.1.90 Phase 2: 质量检验 / 工艺 SOP / 设备台账
  '/pages/quality/inspect',
  '/pages/process/sop',
  '/pages/equipment/my',
  // v1.1.100+: XK 7 页 (PUSH 访问, dashboard 全部功能 XK 记录分类入口) —
  //   走 perm 集合 perm-only 兜底 (前 perm 来自 controller 多 perm 端点)
  '/pages/xk/dashboard',
  '/pages/xk/material',
  '/pages/xk/trace',
  '/pages/xk/maintenance',
  '/pages/xk/safety',
  '/pages/xk/print',
  '/pages/xk/audit'
])
// PAGE_PERMS 改结构: { perm, module } — v1.1.80+
//   module: 用于模块开关过滤 (admin 全局一刀切)
//   perm:   原 perm 字符串 (RBAC 角色级)
const PAGE_PERMS = {
  '/pages/scan/out':                  { perm: 'sales:delivery:list',     module: 'sales' },
  '/pages/count/index':               { perm: 'inventory:stock:list',    module: 'inventory' },
  '/pages/system/users':              { perm: 'system:user:list',        module: '' },
  '/pages/production/order-add':      { perm: 'production:order:add',    module: 'production' },
  '/pages/production/order-list':     { perm: 'production:order:list',   module: 'production' },
  '/pages/production/order-detail':   { perm: 'production:order:list',   module: 'production' },
  // v1.1.80 MES: 扫码报工 (报工入口, 读+写 共用 production:report:list; 提交时后端再卡 :add)
  '/pages/production/report':         { perm: 'production:report:list',  module: 'production' },
  // v1.1.99 C4: 生产排程 + 设备 OEE (走 production:schedule:list; 模块 production)
  '/pages/production/schedule':       { perm: 'production:schedule:list', module: 'production' },
  '/pages/production/oee':           { perm: 'production:schedule:list', module: 'production' },
  // v1.1.90 Phase 2: 质量检验 / 工艺 SOP / 设备台账
  '/pages/quality/inspect':           { perm: 'quality:inspection:list', module: 'qms' },
  '/pages/process/sop':               { perm: 'pdm:sop:list',           module: 'pdm' },
  '/pages/equipment/my':              { perm: 'equipment:master:list',  module: 'em' },
  '/pages/product-add':               { perm: 'base:product:add',        module: 'base' },
  '/pages/base/product-add':          { perm: 'base:product:add',        module: 'base' },
  '/pages/base/products':             { perm: 'base:product:list',       module: 'base' },
  '/pages/change-password':           { perm: 'system:user:edit',        module: '' },        // 改自己的密码
  '/pages/profile/change-password':   { perm: 'system:user:edit',        module: '' },
  '/pages/report/index':              { perm: 'report:view',             module: 'report' },
  '/pages/report/ai':                 { perm: 'report:ai',               module: 'report' },
  // v1.1.14+: 销售出库单查询 (仅读, 列表/详情共用 sales:delivery:list)
  '/pages/sales/delivery-list':       { perm: 'sales:delivery:list',     module: 'sales' },
  '/pages/sales/delivery-detail':     { perm: 'sales:delivery:list',     module: 'sales' },
  // v1.1.15+: 采购入库单查询 (仅读, 列表/详情) — v1.1.56 改用独立 perm purchase:receipt:query
  //           (扫码入库 /pages/scan/in 仍用 purchase:receipt:list, 两者入口分离, 对齐销售 502/503 模型)
  '/pages/purchase/receipt-list':     { perm: 'purchase:receipt:query', module: 'purchase' },
  '/pages/purchase/receipt-detail':   { perm: 'purchase:receipt:query', module: 'purchase' },
  // v1.1.100+: XK 7 页 perm 登记 — perm 来自 XkController 端点, module 用一个能体现 XK 归属的 key.
  // 这些 perm 集合可能跨模块 (dashboard→report, material→purchase, trace→production 等),
  //   module 用 '' 让 isAppModuleEnabled 短路, 不再卡模块开关 (XK 本身没在 8 模块开关列表里).
  '/pages/xk/dashboard':              { perm: 'dashboard:kpi',         module: '' },
  '/pages/xk/material':               { perm: 'purchase:receipt:list', module: '' },
  '/pages/xk/trace':                  { perm: 'production:report:list', module: '' },
  '/pages/xk/maintenance':            { perm: 'work:maintain:list',    module: '' },
  '/pages/xk/safety':                 { perm: 'xk:audit:list',         module: '' },
  '/pages/xk/print':                  { perm: 'report:view',           module: '' },
  '/pages/xk/audit':                  { perm: 'xk:audit:list',         module: '' }
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
// v1.1.55 hotfix 2026-09-21 晚: 返回的是 PC+APP 混合 perm (后端 selectPermsByUserId 不分端),
// 仅用于"业务逻辑层"判断 (如 dashboard perm-only 兜底入口), 不应直接用于 App 端按钮显隐
export function getPermissions() {
  try {
    const raw = uni.getStorageSync('erp_permissions')
    if (typeof raw === 'string') return JSON.parse(raw || '[]')
    if (Array.isArray(raw)) return raw
    return []
  } catch (e) { return [] }
}

// v1.1.58 hotfix: 简化 getAppPermissions() — 删掉 erp_app_permissions 优先路径
//   历史: v1.1.55 hotfix 加了 erp_app_permissions 优先路径, 但只在 dashboard onMounted + profile onRefreshPerms
//         里写. 老 APK (v1.1.56-1.1.57) 覆盖升级时, storage 残留老 uncheck perm,
//         getAppPermissions 优先读 → 反审核按钮误显示.
//   现在: 永远从 erp_menus (login + dashboard 已保证最新) 实时派生, 永远从 erp_menus 取, 不依赖 erp_app_permissions.
//         erp_permissions 仅作兜底 (兼容老 APK 早期版本没 erp_menus 时的过渡).
export function getAppPermissions() {
  try {
    // 1. 优先: 从 erp_menus 实时派生 (login 已写入, dashboard onMounted 也会覆盖)
    const menusRaw = uni.getStorageSync('erp_menus')
    const menus = typeof menusRaw === 'string' ? JSON.parse(menusRaw || '[]') : (Array.isArray(menusRaw) ? menusRaw : [])
    if (menus.length) {
      const set = new Set()
      for (const m of menus) {
        if (!m.perms) continue
        for (const p of String(m.perms).split(',')) {
          const t = p.trim()
          if (t) set.add(t)
        }
      }
      if (set.size) return Array.from(set)
    }
    // 2. 兜底: 读 erp_permissions (混合端, 兼容老 APK 过渡期)
    return getPermissions()
  } catch (e) { return [] }
}

/**
 * P1-3: 检查页面访问权限. 默认拒绝语义:
 * - 超管: 直接放行 (但 v1.1.80+ 模块开关仍生效 — isAdmin 内部已让出控制权给上层, 这里再守一道)
 * - 敏感页: 必须在 PAGE_PERMS / TAB_PERMS 登记, 否则**拒绝** (之前是放行)
 * - 已登记的: 先校验模块开关 (v1.1.80+), 再校验 perms.includes(perm)
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
  // v1.1.80+: 先过模块级开关 (admin 全局一刀切 — 即使 perm 满足, 模块关了也进不去)
  const entry = PAGE_PERMS[path] || TAB_PERMS[path]
  if (entry) {
    if (entry.module && !isAppModuleEnabled(entry.module)) {
      console.warn('[perm] 模块停用, 拒绝访问:', path, 'module=', entry.module)
      return false
    }
  }
  const perm = entry ? entry.perm : ''
  if (!perm) return true  // 非敏感页 + 已配置为空 = 公开
  const perms = getPermissions()
  return perms.includes(perm)
}

// 根据权限过滤 tabBar (调用 uni.hideTabBar / showTabBarItem)
// v1.1.80+: 叠加模块开关 — 模块被关的 tabBar 直接 hide
export function applyTabBar() {
  if (typeof uni === 'undefined') return
  // H5 运行时 (浏览器) 无 uni.showTabBarItem / hideTabBarItem — uni-app H5 的 tabBar
  // 由 pages.json 静态配置渲染, 不支持运行时动态增删. 只有原生 App/小程序才有这两个 API.
  // 缺了直接 return, 避免 dashboard onMounted 抛 Uncaught TypeError 打断整页.
  if (typeof uni.showTabBarItem !== 'function' || typeof uni.hideTabBarItem !== 'function') return
  const tabs = [
    { idx: 0, path: '/pages/dashboard/index',        text: '工作台',   entry: TAB_PERMS['/pages/dashboard/index'] },
    { idx: 1, path: '/pages/inventory/query',       text: '库存',     entry: TAB_PERMS['/pages/inventory/query'] },
    { idx: 2, path: '/pages/scan/in',               text: '原料入库', entry: TAB_PERMS['/pages/scan/in'] },
    { idx: 3, path: '/pages/production/order-list', text: '工单',     entry: TAB_PERMS['/pages/production/order-list'] },
    { idx: 4, path: '/pages/profile/index',         text: '我的',     entry: TAB_PERMS['/pages/profile/index'] }
  ]
  const admin = isAdmin()
  const perms = getPermissions()

  // P0 诊断: 输出当前用户实际权限 + 模块开关状态, 便于排查 "菜单不显示" 问题
  console.log('[applyTabBar] user.isAdmin=', admin, ' perms=', perms)

  tabs.forEach(t => {
    let show = admin
    if (!show && t.entry) {
      // v1.1.80+: 模块级短路
      if (t.entry.module && !isAppModuleEnabled(t.entry.module)) {
        show = false
      } else if (!t.entry.perm) {
        show = true
      } else {
        show = perms.includes(t.entry.perm)
      }
    } else if (!show) {
      show = true  // 无 entry (工作台/我的) 默认显示
    }
    console.log('[applyTabBar] tab', t.idx, t.path, 'perm=', t.entry && t.entry.perm, 'module=', t.entry && t.entry.module, 'show=', show)
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
