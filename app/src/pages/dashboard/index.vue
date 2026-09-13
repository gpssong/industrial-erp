<template>
  <view class="container">
    <view class="header card">
      <view class="row">
        <view>
          <text style="font-size:16px;font-weight:bold">{{ greeting }}, {{ user?.nickname || user?.username || '用户' }}</text>
          <text class="muted" style="display:block;margin-top:4px">{{ today }}</text>
        </view>
        <text class="badge">{{ user?.deptName || user?.roles?.[0] || '' }}</text>
      </view>
    </view>
    <view class="grid-4" style="margin-bottom:10px" v-if="kpiVisible">
      <view class="kpi"><text class="kpi-value">¥{{ kpi.todaySales || 0 }}</text><text class="kpi-label">今日销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.totalSales || 0 }}</text><text class="kpi-label">累计销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.arBalance || 0 }}</text><text class="kpi-label">应收余额</text></view>
      <view class="kpi"><text class="kpi-value">{{ kpi.stockSkuCount || 0 }}</text><text class="kpi-label">SKU数</text></view>
    </view>
    <view class="card">
      <text class="title">业务快捷</text>
      <view class="grid-4" style="margin-top:8px">
        <view class="quick-item" v-for="item in visibleMenus" :key="item.path" @click="nav(item.path)">
          <text class="quick-icon">{{ item.icon }}</text>
          <text>{{ item.title }}</text>
        </view>
      </view>
    </view>
    <view class="card" v-if="kpi.warningCount">
      <text class="title">⚠️ 库存预警 ({{ kpi.warningCount }})</text>
      <text class="muted">有 {{ kpi.warningCount }} 个商品库存低于安全线</text>
      <!-- v1.1.44+: 显示具体产品列表 (点击跳库存详情) -->
      <view v-for="item in warningItems" :key="item.id || (item.productId + '-' + item.warehouseId)"
            class="warning-item" @click="openWarningDetail(item)">
        <view class="warning-row1">
          <text class="warning-code">{{ item.productCode || '-' }}</text>
          <text class="warning-name">{{ item.productName || '-' }}</text>
        </view>
        <view class="warning-row2">
          <text class="warning-wh">📍 {{ item.warehouseName || '仓库' }}</text>
          <text class="warning-qty">库存 <text class="num-red">{{ formatNum(item.qty) }}</text> / 安全 {{ formatNum(item.safetyStock) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../../api/index.js'
import { navigateTo } from '../../utils/nav.js'
import { applyTabBar, isAdmin } from '../../utils/permission.js'

// v1.1.8+: KPI 区块按 App 端"经营简报"权限控制 (整体显示/隐藏)
// 检查顺序: erp_permissions 包含 report:view (PC 端白名单已用此 perm 接入 id=951 报表查看按钮) → 管理员豁免
// 注意: 此处返回 ref, 模板中用 kpiVisible 引用, 不要再写 v-if="hasKpiPerm" (那是函数引用永远 true)
const kpiVisible = ref(false)
function recomputeKpiVisible() {
  if (isAdmin()) { kpiVisible.value = true; return }
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    kpiVisible.value = Array.isArray(perms) && perms.includes('report:view')
  } catch { kpiVisible.value = false }
}

// v1.1.8+: 经营简报快捷入口也按 report:view perm 控制
const reportEntryVisible = ref(false)
function recomputeReportEntryVisible() {
  if (isAdmin()) { reportEntryVisible.value = true; return }
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    reportEntryVisible.value = Array.isArray(perms) && perms.includes('report:view')
  } catch { reportEntryVisible.value = false }
}

const user = ref({})
const kpi = ref({ todaySales: 0, totalSales: 0, arBalance: 0, stockSkuCount: 0, warningCount: 0 })
// v1.1.44+: 库存预警具体产品列表 (编码/名称/仓库/当前库存/安全库存)
const warningItems = ref([])
const today = new Date().toISOString().substring(0, 10)
const greeting = ref('您好')

// 数字格式化: 整数直接显示, 小数最多保留 2 位 (避免显示 63000.0000)
function formatNum(n) {
  if (n == null) return '0'
  const num = Number(n)
  if (!isFinite(num)) return '0'
  if (Number.isInteger(num)) return String(num)
  return num.toFixed(2).replace(/\.?0+$/, '')
}

// 点击预警项跳库存详情 (复用"查库存"页面)
function openWarningDetail(item) {
  if (!item) return
  try {
    uni.setStorageSync('erp_stock_filter', {
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName
    })
  } catch (e) {}
  navigateTo('/pages/inventory/query')
}

// v1.0.10+: PC 端菜单路径 -> App 端页面映射 (兼容老版本)
const PATH_TO_APP = {
  '/base/product/add':     { path: '/pages/base/product-add', title: '新增商品', icon: '➕' },
  '/sales/return':         { path: '/pages/scan/out', title: '扫码出库', icon: '📤' },
  '/purchase/receipt':     { path: '/pages/scan/in', title: '扫码入库', icon: '📥' },
  '/inventory/stock':      { path: '/pages/inventory/query', title: '查库存', icon: '📦' },
  '/inventory/ledger':     { path: '/pages/inventory/query', title: '库存台账', icon: '📒' },
  '/production/order':     { path: '/pages/production/order-list', title: '生产加工单', icon: '🏭' },
  '/inventory/check':      { path: '/pages/count/index', title: '外勤盘点', icon: '📋' },
  '/report':               { path: '/pages/report/index', title: '经营简报', icon: '📊' },
  '/_report_kpi':          { path: '/pages/report/index', title: '经营简报', icon: '📊' }
}

// v1.1.12+: App 端业务快捷映射白名单 — 与 PC 端 Role.vue APP_MENU_WHITELIST 严格对齐.
// 这里按 sys_menu.perms 匹配 (不是 path), 因为 PC 端白名单 perms 映射到的 sys_menu.path 可能没在 PATH_TO_APP 里.
// 同时支持"外勤盘点" / "生产加工单(新增)" 共用同一 sys_menu 但要不同 App 入口:
// 用特殊 sys_menu.path 前缀区分 (/inventory/check 是盘点, /production/order 是生产单).
const APP_MENU_TO_PAGE = [
  { perms: 'base:product:list',      path: '/base/product',         page: { path: '/pages/base/product-add', title: '新增商品', icon: '➕' } },
  { perms: 'purchase:receipt:list',  path: '/purchase/receipt',     page: { path: '/pages/scan/in', title: '扫码入库', icon: '📥' } },
  { perms: 'sales:return:list',      path: '/sales/return',         page: { path: '/pages/scan/out', title: '扫码出库', icon: '📤' } },
  { perms: 'inventory:stock:list',   path: '/inventory/stock',      page: { path: '/pages/inventory/query', title: '查库存', icon: '📦' } },
  { perms: 'inventory:ledger:list',  path: '/inventory/ledger',     page: { path: '/pages/inventory/query', title: '库存台账', icon: '📒' } },
  // 生产管理两条独立 sys_menu (外勤盘点=603, 生产加工单=702)
  { perms: 'inventory:check:list',   path: '/inventory/check',      page: { path: '/pages/count/index', title: '外勤盘点', icon: '📋' } },
  { perms: 'production:order:list',  path: '/production/order',     page: { path: '/pages/production/order-list', title: '生产加工单', icon: '🏭' } },
  { perms: 'report:view',            path: '/_report_kpi',          page: { path: '/pages/report/index', title: '经营简报', icon: '📊' } },
  // v1.1.14+: 销售出库单查询 (sys_menu id=502 path=/sales/delivery)
  { perms: 'sales:delivery:list',    path: '/sales/delivery',       page: { path: '/pages/sales/delivery-list', title: '销售出库单', icon: '📋' } },
  // v1.1.15+: 采购入库单查询 (sys_menu id=402 path=/purchase/receipt perms=purchase:receipt:list)
  { perms: 'purchase:receipt:list',  path: '/purchase/receipt',     page: { path: '/pages/purchase/receipt-list', title: '采购入库单', icon: '🧾' } }
]

// 根据 PC 端分配的菜单权限, 动态生成可见的 App 端快捷功能
function getServerMenus() {
  try {
    const raw = uni.getStorageSync('erp_menus')
    if (typeof raw === 'string') return JSON.parse(raw || '[]')
    if (Array.isArray(raw)) return raw
    return []
  } catch (e) { return [] }
}

const visibleMenus = computed(() => {
  // 管理员: 显示全部 App 端功能 (v1.1.8+ 新增商品 + 生产加工单, 移除采购订单)
  if (isAdmin()) {
    return [
      PATH_TO_APP['/base/product/add'],
      PATH_TO_APP['/sales/return'],
      PATH_TO_APP['/purchase/receipt'],  // v1.1.11+: 去 /production/order/add (重复, 走列表页 ➕)
      PATH_TO_APP['/inventory/stock'],
      PATH_TO_APP['/production/order'],
      PATH_TO_APP['/_report_kpi'],
      PATH_TO_APP['/inventory/check'],
      // v1.1.14+: 销售出库单查询 (管理员 hardcoded 列表也加, 保持对称)
      { path: '/pages/sales/delivery-list', title: '销售出库单', icon: '📋' },
      // v1.1.15+: 采购入库单查询 (管理员 hardcoded 列表也加)
      { path: '/pages/purchase/receipt-list', title: '采购入库单', icon: '🧾' }
    ]
  }
  // 普通用户: 从 PC 端已分配的菜单中, 映射出 App 端可用功能
  // v1.1.12+: 按 (perms + path) 双匹配 — sys_menu.perms 决定"用户是否有此权限",
  // 但同一 perms 可能对应多个 App 入口 (外勤盘点/生产加工单共用 702), 所以 path 必须匹配.
  const serverMenus = getServerMenus()
  const seen = new Set()
  const result = []
  for (const m of serverMenus) {
    if (!m.perms && !m.path) continue
    const perms = String(m.perms || '').split(',').map(p => p.trim()).filter(Boolean)
    const match = APP_MENU_TO_PAGE.find(entry =>
      perms.includes(entry.perms) && entry.path === m.path
    )
    if (match && !seen.has(match.page.path)) {
      seen.add(match.page.path)
      result.push(match.page)
    }
  }
  // 经营简报: PC 端白名单 APP_MENU_WHITELIST 用 perms='report:view' 接入 id=951 按钮,
  // 但 sys_menu 报表查看 id=951 的 path 为空, 不会进入 appMenus 路径匹配, 所以这里按 perm 补一个入口
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('report:view') && !seen.has('/pages/report/index')) {
      seen.add('/pages/report/index')
      result.push(PATH_TO_APP['/_report_kpi'])
    }
  } catch (e) {}
  return result
})

function nav(url) {
  navigateTo(url)
}

function loadUser() {
  const raw = uni.getStorageSync('erp_user')
  if (typeof raw === 'object' && raw) {
    user.value = raw
  } else if (typeof raw === 'string') {
    try { user.value = JSON.parse(raw) } catch (e) { user.value = {} }
  }
}

onMounted(async () => {
  loadUser()
  const h = new Date().getHours()
  greeting.value = h < 6 ? '凌晨好' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
  // v1.1.8+: 计算 KPI 区块显隐 + 经营简报入口显隐
  recomputeKpiVisible()
  recomputeReportEntryVisible()
  // v1.1.11+: 有 KPI 权限才请求数据 + 失败时主动隐藏 KPI 区块 (兼容老版本残留 + perm 时序错位)
  if (kpiVisible.value) {
    try {
      kpi.value = await api.dashboard()
      // v1.1.44+: 库存预警数字 > 0 时, 并行拉具体产品列表
      if (kpi.value && kpi.value.warningCount > 0) {
        try {
          const list = await api.warningList()
          // /inventory/warning/list 返回 [{productId, productCode, productName, qty, p_safety_stock (来自 base_product JOIN), safety_stock (来自 inv_stock, 通常 0), warehouseName, ...}]
          // 这里只取前 10 条避免渲染过多
          warningItems.value = (Array.isArray(list) ? list : []).slice(0, 10).map(it => {
            // 优先用 base_product.safety_stock (JOIN 别名 p_safety_stock), 兼容 inv_stock.safety_stock (通常 0)
            const safetyVal = it.p_safety_stock != null ? it.p_safety_stock
              : (it.safety_stock != null ? it.safety_stock : 0)
            return {
              id: it.id,
              productId: it.product_id || it.productId,
              productCode: it.product_code || it.productCode || '-',
              productName: it.product_name || it.productName || '-',
              warehouseId: it.warehouse_id || it.warehouseId,
              warehouseName: it.wh_name || it.warehouseName || it.warehouse_name || '仓库',
              qty: it.qty != null ? Number(it.qty) : 0,
              safetyStock: Number(safetyVal) || 0
            }
          })
        } catch (e) { /* 静默, 仅显示数字 */ }
      }
    } catch (e) {
      // 403 (无权限) / 网络错: 静默, 并隐藏 KPI 区块避免后续空数据报错
      kpiVisible.value = false
    }
  }
  applyTabBar()
  // v1.0.10+: perms 缺失或空时, 主动调 /me 修复 (兼容老版本残留)
  // v1.1.12+: 同时调 /me 时拿到最新 appMenus + permissions, 即使缓存非空也覆盖一次
  // (解决用户在 PC 端改了授权但 App 端不显示的 bug)
  try {
    const r = await api.me()
    const userObj = r.data || r
    localStorage.setItem('erp_permissions', JSON.stringify(userObj.permissions || []))
    // App 端优先用 appMenus
    localStorage.setItem('erp_menus', JSON.stringify(userObj.appMenus || userObj.menus || []))
    localStorage.setItem('erp_user', JSON.stringify(userObj))
    // 无变化不需强制刷新 (computed 自动响应, 因为 storage 不是响应式, 但 localStorage 是同步写)
    // 注意: visibleMenus 是 computed, 依赖 erp_menus storage.
    // uni 环境下 localStorage 写入不触发 vue 响应式 → 需手动 re-launch
    // 但重复 re-launch 会闪烁. 仅在菜单实际变化时 re-launch
    const cachedKeys = uni.getStorageSync('erp_menus_keys') || ''
    const newKeys = (userObj.appMenus || userObj.menus || []).map(m => m.id).sort().join(',')
    if (cachedKeys !== newKeys) {
      uni.setStorageSync('erp_menus_keys', newKeys)
      // 强制重载 dashboard 让 visibleMenus 重新计算
      uni.reLaunch({ url: '/pages/dashboard/index' })
    }
  } catch (e) { /* 忽略 — 不阻塞 UI */ }
})
</script>
<style scoped>
.header { background: linear-gradient(135deg, var(--primary), var(--primary-light)); color: #fff; }
.header text { color: #fff; }
.header .muted { color: rgba(255,255,255,0.8) !important; }
.badge { background: rgba(255,255,255,0.2); padding: 4px 10px; border-radius: 10px; font-size: 12px; }
.quick-item { display: flex; flex-direction: column; align-items: center; padding: 10px 0; background: #f9f9f9; border-radius: 6px; }
.quick-icon { font-size: 24px; margin-bottom: 4px; }
.quick-item text:last-child { font-size: 12px; color: #555; }

/* v1.1.44+ 库存预警产品列表 */
.warning-item {
  margin-top: 10px;
  padding: 10px 12px;
  background: #fff8f0;
  border-left: 3px solid #e67e22;
  border-radius: 4px;
}
.warning-row1 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.warning-code {
  font-size: 12px;
  color: #888;
  font-family: monospace;
  flex-shrink: 0;
}
.warning-name {
  font-size: 14px;
  color: #333;
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.warning-row2 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #666;
}
.warning-wh { color: #888; }
.warning-qty { color: #555; }
.num-red {
  color: #c0392b;
  font-weight: bold;
  font-size: 13px;
}
</style>
