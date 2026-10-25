<template>
  <view class="container">
    <!-- v1.1.100+: 蓝色渐变头部 (问候/角色/班组/日期/班次) — 按设计图 -->
    <view class="hero">
      <view class="hero-top">
        <text class="hero-hello">{{ greeting }}, {{ user?.nickname || user?.username || '用户' }}</text>
        <text class="hero-bell">🔔</text>
      </view>
      <view class="hero-sub">
        <text class="hero-sub-role">{{ user?.deptName || user?.roles?.[0] || '东厂 · 早班' }}</text>
      </view>
      <view class="hero-date">
        <text>{{ today }}</text>
        <text class="hero-shift">早班</text>
      </view>
    </view>

    <!-- v1.1.100+: 全部功能 + 子标签横向滚动 (按 perm 裁剪) -->
    <view class="card func-card">
      <view class="func-head">
        <text class="func-title">全部功能</text>
        <text class="func-more">›</text>
      </view>
      <scroll-view scroll-x="true" class="func-tabs" :show-scrollbar="false">
        <view class="func-tab-list">
          <view v-for="g in funcGroups" :key="g.name"
                :class="['func-tab', activeFuncGroup === g.name ? 'func-tab-active' : '']"
                @click="activeFuncGroup = g.name">
            {{ g.name }}
          </view>
        </view>
      </scroll-view>
      <view class="func-grid">
        <view v-for="item in activeFuncItems" :key="item.path + item.title"
              class="quick-item" @click="nav(item.path)">
          <text class="quick-icon">{{ item.icon }}</text>
          <text>{{ item.title }}</text>
        </view>
        <view v-if="!activeFuncItems.length" class="func-empty">该分类暂无可用功能</view>
      </view>
    </view>

    <!-- v1.1.100+: KPI 四卡 (保留, 按 kpiVisible) -->
    <view class="grid-4" style="margin-bottom:10px" v-if="kpiVisible">
      <view class="kpi"><text class="kpi-value">¥{{ kpi.todaySales || 0 }}</text><text class="kpi-label">今日销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.totalSales || 0 }}</text><text class="kpi-label">累计销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.arBalance || 0 }}</text><text class="kpi-label">应收余额</text></view>
      <view class="kpi"><text class="kpi-value">{{ kpi.stockSkuCount || 0 }}</text><text class="kpi-label">SKU数</text></view>
    </view>

    <!-- v1.1.100+: 库存预警 (保留预警列表 + 「查看台账 ›」入口) -->
    <view class="card" v-if="warningVisible && warningItems.length">
      <view class="warn-head">
        <view>
          <text class="title">库存预警 <text class="warn-count">{{ warningItems.length }}</text></text>
          <text class="muted">低于安全库存, 需要及时处理</text>
        </view>
        <text class="warn-more" @click="openLedger">查看台账 ›</text>
      </view>
      <view v-for="item in warningItems" :key="item.id || (item.productId + '-' + item.warehouseId)"
            class="warning-item" @click="openWarningDetail(item)">
        <view class="warning-row1">
          <text class="warning-code">{{ item.productCode || '-' }}</text>
          <text class="warning-name">{{ item.productName || '-' }}</text>
          <text class="warn-cur">当前 / 安全</text>
        </view>
        <view class="warning-row2">
          <text class="warning-wh">📍 {{ item.warehouseName || '仓库' }}</text>
          <text class="warning-qty">{{ formatNum(item.qty) }} / {{ formatNum(item.safetyStock) }}</text>
        </view>
      </view>
    </view>

    <!-- v1.1.100+: 现场待办 (到货待检 IQC / 维保任务 / 复检 OQC — 真接后端) -->
    <view class="card" v-if="todoItems.length">
      <view class="todo-head">
        <text class="title">现场待办</text>
        <text class="muted">今日需要完成的工作</text>
      </view>
      <view v-for="t in todoItems" :key="t.key" class="todo-item" @click="openTodo(t)">
        <view class="todo-ic">{{ t.icon }}</view>
        <view class="todo-main">
          <text class="todo-cat">{{ t.cat }}</text>
          <text class="todo-title">{{ t.title }}</text>
        </view>
        <text class="todo-time">{{ t.time }}</text>
        <text class="todo-arrow">›</text>
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../../api/index.js'
import { navigateTo } from '../../utils/nav.js'
import { applyTabBar, isAdmin } from '../../utils/permission.js'
import { isModuleEnabled, isPermEnabled, loadFromServer as loadAppModuleConfig } from '../../utils/appModuleEnabled.js'

// v1.1.53: KPI 区块按新 perm dashboard:kpi 控制 (之前是 report:view).
// 之前 report:view 既管 KPI 又管"经营简报"快捷入口 (PAGE_TO_APP 里),
// 拆细后: KPI 卡片 → dashboard:kpi, 经营简报入口继续 report:view (它跳 /pages/report/index 不是内嵌)
// 库存预警区块独立 — 走 inventory:warning:list perm (复用 sql/28 已建行).
// 销售趋势 / 销售排行 当前 App dashboard 模板没渲染这两个 section (只有 PC 端有),
// 但 isTrendVisible/isRankingVisible 计算属性保留, 后续如要加卡片直接 v-if 即可.
// v1.1.80+: 区块也受"App模块开关"控制 (KPI 走 report 模块, 库存预警走 inventory 模块)
const kpiVisible = ref(false)
function recomputeKpiVisible() {
  if (!isModuleEnabled('report')) { kpiVisible.value = false; return }
  if (isAdmin()) { kpiVisible.value = true; return }
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    kpiVisible.value = Array.isArray(perms) && perms.includes('dashboard:kpi')
  } catch { kpiVisible.value = false }
}

// v1.1.53: 库存预警区块独立可见性 (不复用 kpiVisible, 让没 KPI perm 也能单独看预警)
const warningVisible = ref(false)
function recomputeWarningVisible() {
  if (!isModuleEnabled('inventory')) { warningVisible.value = false; return }
  if (isAdmin()) { warningVisible.value = true; return }
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    warningVisible.value = Array.isArray(perms) && perms.includes('inventory:warning:list')
  } catch { warningVisible.value = false }
}

// v1.1.8+: 经营简报快捷入口仍按 report:view perm 控制 (它跳 /pages/report/index 不是工作台内嵌)
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
// v1.1.100+: 现场待办 3 类 (到货待检 IQC 草稿 / 维保任务 / 复检 OQC), 各取前 3
const todoItems = ref([])
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
  '/purchase/receipt':     { path: '/pages/scan/in', title: '原料入库', icon: '📥' },
  '/inventory/stock':      { path: '/pages/inventory/query', title: '查库存', icon: '📦' },
  '/inventory/ledger':     { path: '/pages/inventory/query', title: '库存台账', icon: '📒' },
  '/production/order':     { path: '/pages/production/order-list', title: '生产加工单', icon: '🏭' },
  '/inventory/check':      { path: '/pages/count/index', title: '外勤盘点', icon: '📋' },
  '/report':               { path: '/pages/report/index', title: '经营简报', icon: '📊' },
  '/_report_kpi':          { path: '/pages/report/index', title: '经营简报', icon: '📊' },
  // v1.1.76+: AI 助手 (perms=report:ai → 命中 sql/48 建的 sys_menu 行 path=/report/ai)
  '/report/ai':            { path: '/pages/report/ai', title: 'AI 助手', icon: '🤖' }
}

// v1.1.12+: App 端业务快捷映射白名单 — 与 PC 端 Role.vue APP_MENU_WHITELIST 严格对齐.
// 这里按 sys_menu.perms 匹配 (不是 path), 因为 PC 端白名单 perms 映射到的 sys_menu.path 可能没在 PATH_TO_APP 里.
// 同时支持"外勤盘点" / "生产加工单(新增)" 共用同一 sys_menu 但要不同 App 入口:
// 用特殊 sys_menu.path 前缀区分 (/inventory/check 是盘点, /production/order 是生产单).
const APP_MENU_TO_PAGE = [
  { perms: 'base:product:list',      path: '/base/product',         module: 'base',       page: { path: '/pages/base/product-add', title: '新增商品', icon: '➕' } },
  { perms: 'purchase:receipt:list',  path: '/purchase/receipt',     module: 'purchase',   page: { path: '/pages/scan/in', title: '原料入库', icon: '📥' } },
  { perms: 'sales:return:list',      path: '/sales/return',         module: 'sales',      page: { path: '/pages/scan/out', title: '扫码出库', icon: '📤' } },
  { perms: 'inventory:stock:list',   path: '/inventory/stock',      module: 'inventory',  page: { path: '/pages/inventory/query', title: '查库存', icon: '📦' } },
  { perms: 'inventory:ledger:list',  path: '/inventory/ledger',     module: 'inventory',  page: { path: '/pages/inventory/query', title: '库存台账', icon: '📒' } },
  // 生产管理两条独立 sys_menu (外勤盘点=603, 生产加工单=702)
  { perms: 'inventory:check:list',   path: '/inventory/check',      module: 'inventory',  page: { path: '/pages/count/index', title: '外勤盘点', icon: '📋' } },
  { perms: 'production:order:list',  path: '/production/order',     module: 'production', page: { path: '/pages/production/order-list', title: '生产加工单', icon: '🏭' } },
  // v1.1.80 MES: 扫码报工 (perms=production:report:list, F 型 perm 载体 path='', 走下方 perm-only 兜底分支)
  { perms: 'report:view',            path: '/_report_kpi',          module: 'report',     page: { path: '/pages/report/index', title: '经营简报', icon: '📊' } },
  // v1.1.76+: AI 助手 (perms=report:ai, sys_menu.path=/report/ai, 双匹配命中)
  { perms: 'report:ai',             path: '/report/ai',            module: 'report',     page: { path: '/pages/report/ai', title: 'AI 助手', icon: '🤖' } },
  // v1.1.126: 吹膜米克重采集 (sys_menu M 节点 path=/production/weight, perms=mes:weight:list)
  { perms: 'mes:weight:list',       path: '/production/weight',    module: 'production', page: { path: '/pages/production/weight', title: '吹膜米重采集', icon: '⚖' } },
  // v1.1.14+: 销售出库单查询 (sys_menu id=502 path=/sales/delivery)
  { perms: 'sales:delivery:list',    path: '/sales/delivery',       module: 'sales',      page: { path: '/pages/sales/delivery-list', title: '销售出库单', icon: '📋' } },
  // v1.1.56+: 采购入库单查询 — sys_menu id=2090345792472715351 是 F 类型 perm 载体 (path=''),
  //           与 L135 扫码入库 (402, purchase:receipt:list) 分离, 对齐销售 502/503 双 sys_menu 模型.
  //           因为 F 载体 path 为空, 不进 (perms,path) 双匹配, 由下面 visibleMenus 里的
  //           "purchase:receipt:query perm-only 补一个入口" 逻辑处理 (同 report:view 模式).
  //           这里保留条目仅作文档说明 — 实际匹配走 perms-only 分支, 不会命中.
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
  // v1.1.80+: admin 也要走模块开关 (用户决策: 全局一刀切, admin 关了也看不到)
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
    // v1.1.80+: 模块级 + perm 级双重过滤 (admin 也走)
    if (match && !seen.has(match.page.path)
        && isModuleEnabled(match.module)
        && isPermEnabled(match.module, match.perms)) {
      seen.add(match.page.path)
      result.push(match.page)
    }
  }
  // 经营简报: PC 端白名单 APP_MENU_WHITELIST 用 perms='report:view' 接入 id=951 按钮,
  // 但 sys_menu 报表查看 id=951 的 path 为空, 不会进入 appMenus 路径匹配, 所以这里按 perm 补一个入口
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('report:view') && !seen.has('/pages/report/index')
        && isModuleEnabled('report') && isPermEnabled('report', 'report:view')) {
      seen.add('/pages/report/index')
      result.push(PATH_TO_APP['/_report_kpi'])
    }
  } catch (e) {}
  // v1.1.56+: purchase:receipt:query 与 report:view 同理 — F 类型 perm 载体 sys_menu 行
  // path 为空, 不会进 (perms, path) 双匹配, 按 perm-only 补一个入口
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('purchase:receipt:query') && !seen.has('/pages/purchase/receipt-list')
        && isModuleEnabled('purchase') && isPermEnabled('purchase', 'purchase:receipt:query')) {
      seen.add('/pages/purchase/receipt-list')
      result.push({ path: '/pages/purchase/receipt-list', title: '采购入库单', icon: '🧾' })
    }
  } catch (e) {}
  // v1.1.76+: AI 助手 perm-only 兜底 (同 report:view 模式) — 即使 erp_menus 里 path 匹配不上,
  // 只要 App 端 perm 含 report:ai 就补一个入口
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('report:ai') && !seen.has('/pages/report/ai')
        && isModuleEnabled('report') && isPermEnabled('report', 'report:ai')) {
      seen.add('/pages/report/ai')
      result.push(PATH_TO_APP['/report/ai'])
    }
  } catch (e) {}
  // v1.1.80 MES: 扫码报工 perm-only 兜底 (production:report:list 是 F 型 perm 载体,
  // sys_menu path='', 不进双匹配; 有 perm + production 模块开就补入口)
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('production:report:list') && !seen.has('/pages/production/report')
        && isModuleEnabled('production') && isPermEnabled('production', 'production:report:list')) {
      seen.add('/pages/production/report')
      result.push({ path: '/pages/production/report', title: '扫码报工', icon: '📷' })
    }
  } catch (e) {}
  // v1.1.90+: 质量检验 / 工艺 SOP / 设备台账 perm-only 兜底 (同 production:report:list 模式)
  // 3 个 F 型 perm 载体 (quality:inspection:list / pdm:sop:list / equipment:master:list)
  // sys_menu path='', 不进 (perms,path) 双匹配; 有 perm + 模块开就补一个入口
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('quality:inspection:list') && !seen.has('/pages/quality/inspect')
        && isModuleEnabled('qms') && isPermEnabled('qms', 'quality:inspection:list')) {
      seen.add('/pages/quality/inspect')
      result.push({ path: '/pages/quality/inspect', title: '质量检验', icon: '✔' })
    }
    if (Array.isArray(perms) && perms.includes('pdm:sop:list') && !seen.has('/pages/process/sop')
        && isModuleEnabled('pdm') && isPermEnabled('pdm', 'pdm:sop:list')) {
      seen.add('/pages/process/sop')
      result.push({ path: '/pages/process/sop', title: '工艺 SOP', icon: '📘' })
    }
    if (Array.isArray(perms) && perms.includes('equipment:master:list') && !seen.has('/pages/equipment/my')
        && isModuleEnabled('em') && isPermEnabled('em', 'equipment:master:list')) {
      seen.add('/pages/equipment/my')
      result.push({ path: '/pages/equipment/my', title: '设备台账', icon: '⚙' })
    }
  } catch (e) {}
  // v1.1.100+: 飞牛/老库没跑 v1.1.24+ perm seed, 用户 perm 数组中包含的 perm
  // 在 sys_menu 里没对应 path → (perms,path) 双匹配永远 false. 这里加 perm-only 兜底:
  // 只看用户有没有 perm, 不依赖 sys_menu. 同时这些入口的 perm
  // 都是 SUPER_ADMIN 必备, 在角色管理里能稳定授权.
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms)) {
      // 1. 原料入库 (purchase:receipt:list) — 飞牛没 seed 也能命中
      if (perms.includes('purchase:receipt:list') && !seen.has('/pages/scan/in')
          && isModuleEnabled('purchase') && isPermEnabled('purchase', 'purchase:receipt:list')) {
        seen.add('/pages/scan/in')
        result.push({ path: '/pages/scan/in', title: '原料入库', icon: '📥' })
      }
      // 2. 扫码出库 (sales:return:list) — 飞牛 perm 有, sys_menu 路径可能没
      if (perms.includes('sales:return:list') && !seen.has('/pages/scan/out')
          && isModuleEnabled('sales') && isPermEnabled('sales', 'sales:return:list')) {
        seen.add('/pages/scan/out')
        result.push({ path: '/pages/scan/out', title: '扫码出库', icon: '📤' })
      }
      // 3. 查库存 (inventory:stock:list)
      if (perms.includes('inventory:stock:list') && !seen.has('/pages/inventory/query')
          && isModuleEnabled('inventory') && isPermEnabled('inventory', 'inventory:stock:list')) {
        seen.add('/pages/inventory/query')
        result.push({ path: '/pages/inventory/query', title: '查库存', icon: '📦' })
      }
      // 4. 外勤盘点 (inventory:check:list) — 仓库主管专用
      if (perms.includes('inventory:check:list') && !seen.has('/pages/count/index')
          && isModuleEnabled('inventory') && isPermEnabled('inventory', 'inventory:check:list')) {
        seen.add('/pages/count/index')
        result.push({ path: '/pages/count/index', title: '外勤盘点', icon: '📋' })
      }
      // 5. 库存预警 (inventory:warning:list) — 走工作台上的"库存预警"区块入口也行,
      //    这里加 dashboard 快捷图标 (快捷卡片路径, 点开复用 query 页)
      if (perms.includes('inventory:warning:list') && !seen.has('/pages/inventory/warning')
          && isModuleEnabled('inventory') && isPermEnabled('inventory', 'inventory:warning:list')) {
        seen.add('/pages/inventory/warning')
        // 没有独立 /pages/inventory/warning 路由, 跳到查库存 (storage 预填)
        result.push({ path: '/pages/inventory/query', title: '库存预警', icon: '⚠' })
      }
      // 6. 销售出库单 (sales:delivery:list) — 缺 sales:delivery:query 也能命中
      if (perms.includes('sales:delivery:list') && !seen.has('/pages/sales/delivery-list')
          && isModuleEnabled('sales') && isPermEnabled('sales', 'sales:delivery:list')) {
        seen.add('/pages/sales/delivery-list')
        result.push({ path: '/pages/sales/delivery-list', title: '销售出库单', icon: '📤' })
      }
      // 7. 采购入库单 (purchase:receipt:query) — 飞牛 perm 有
      if (perms.includes('purchase:receipt:query') && !seen.has('/pages/purchase/receipt-list')
          && isModuleEnabled('purchase') && isPermEnabled('purchase', 'purchase:receipt:query')) {
        seen.add('/pages/purchase/receipt-list')
        result.push({ path: '/pages/purchase/receipt-list', title: '采购入库单', icon: '🧾' })
      }
      // 8. 经营简报 (report:view) — 飞牛 perm 有
      if (perms.includes('report:view') && !seen.has('/pages/report/index')
          && isModuleEnabled('report') && isPermEnabled('report', 'report:view')) {
        seen.add('/pages/report/index')
        result.push({ path: '/pages/report/index', title: '经营简报', icon: '📊' })
      }
      // 9. AI 助手 (report:ai) — 飞牛 perm 有
      if (perms.includes('report:ai') && !seen.has('/pages/report/ai')
          && isModuleEnabled('report') && isPermEnabled('report', 'report:ai')) {
        seen.add('/pages/report/ai')
        result.push({ path: '/pages/report/ai', title: 'AI 助手', icon: '🤖' })
      }
    }
  } catch (e) {}
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms)) {
      // 1. XK 工作台 — 走 dashboard:kpi (与 ReportController.dashboard 同源)
      if (perms.includes('dashboard:kpi') && !seen.has('/pages/xk/dashboard')
          && (isModuleEnabled('report') || isModuleEnabled('qms'))) {
        seen.add('/pages/xk/dashboard')
        result.push({ path: '/pages/xk/dashboard', title: 'XK 工作台', icon: '🛡' })
      }
      // 2. 原料入库 — 统一到真流程 /pages/scan/in (v1.1.101, 与 tabBar/PC 采购入库同源)
      // 老 XK 死壳 /pages/xk/material (unsplash mock) 不再作为独立入口; scan/in 已被 seen 去重
      if ((perms.includes('purchase:receipt:list') || perms.includes('purchase:receipt:add'))
          && !seen.has('/pages/scan/in') && isModuleEnabled('purchase')) {
        seen.add('/pages/scan/in')
        result.push({ path: '/pages/scan/in', title: 'XK 原料', icon: '📥' })
      }
      // 3. 批次溯源 — production:report:list
      if (perms.includes('production:report:list') && !seen.has('/pages/xk/trace')
          && isModuleEnabled('production')) {
        seen.add('/pages/xk/trace')
        result.push({ path: '/pages/xk/trace', title: 'XK 批次溯源', icon: '🔗' })
      }
      // 4. 设备维保 (XK 视图) — work:maintain:list (与 /xk/maintenance/orders 同 perm)
      if (perms.includes('work:maintain:list') && !seen.has('/pages/xk/maintenance')
          && isModuleEnabled('em')) {
        seen.add('/pages/xk/maintenance')
        result.push({ path: '/pages/xk/maintenance', title: 'XK 设备维保', icon: '🔧' })
      }
      // 5. 安全卫生 — xk:audit:list 或 work:safety:list
      if ((perms.includes('xk:audit:list') || perms.includes('work:safety:list'))
          && !seen.has('/pages/xk/safety') && isModuleEnabled('em')) {
        seen.add('/pages/xk/safety')
        result.push({ path: '/pages/xk/safety', title: 'XK 安全卫生', icon: '⚠' })
      }
      // 6. 单据打印 — report:view (ReportController 内部)
      if (perms.includes('report:view') && !seen.has('/pages/xk/print')
          && isModuleEnabled('report')) {
        seen.add('/pages/xk/print')
        result.push({ path: '/pages/xk/print', title: 'XK 单据打印', icon: '🖨' })
      }
      // 7. 审计归档 — xk:audit:list 或 xk:audit:archive
      if ((perms.includes('xk:audit:list') || perms.includes('xk:audit:archive'))
          && !seen.has('/pages/xk/audit')) {
        seen.add('/pages/xk/audit')
        result.push({ path: '/pages/xk/audit', title: 'XK 审计归档', icon: '📋' })
      }
    }

    // v1.1.99 C4 App 端联动: 生产排程 + 设备 OEE (perm=production:schedule:list, 模块 production)
    // 与 XK 一样走 perm-only 兜底 — sys_menu 里尚无 path 节点, 双匹配永远 false.
    if (Array.isArray(perms) && perms.includes('production:schedule:list')
        && isModuleEnabled('production') && isPermEnabled('production', 'production:schedule:list')) {
      // 排程管理
      if (!seen.has('/pages/production/schedule')) {
        seen.add('/pages/production/schedule')
        result.push({ path: '/pages/production/schedule', title: '生产排程', icon: '📅' })
      }
      // 设备 OEE
      if (!seen.has('/pages/production/oee')) {
        seen.add('/pages/production/oee')
        result.push({ path: '/pages/production/oee', title: '设备 OEE', icon: '📊' })
      }
    }

    // v1.1.101 App XK 吹膜生产全流程 — 4 个独立操作入口 (perm-only 兜底, 归生产管理分组)
    // 首检/巡检: 检验员 (quality:inspection:check); 半成品/成品入库: 仓管 (production:*-in:add)
    if (Array.isArray(perms) && isModuleEnabled('production')) {
      // v1.1.126: 吹膜米克重采集 (ESP32 上云) — perm-only 兜底.
      // 双匹配 (perms=mes:weight:list + path=/production/weight) 命中 APP_MENU_TO_PAGE 时走上面那条;
      // 老库若没灌 M 节点 (sql/129) 则 path 匹配不上, 这里按 perm 补一个入口 (同 production:report:list 模式).
      if (perms.includes('mes:weight:list') && isPermEnabled('production', 'mes:weight:list')
          && !seen.has('/pages/production/weight')) {
        seen.add('/pages/production/weight')
        result.push({ path: '/pages/production/weight', title: '吹膜米重采集', icon: '⚖' })
      }
      if (perms.includes('quality:inspection:check') && isPermEnabled('production', 'quality:inspection:check')) {
        if (!seen.has('/pages/scan/fai')) { seen.add('/pages/scan/fai'); result.push({ path: '/pages/scan/fai', title: '首检验', icon: '🔬' }) }
        if (!seen.has('/pages/scan/pat')) { seen.add('/pages/scan/pat'); result.push({ path: '/pages/scan/pat', title: '工序巡检', icon: '📑' }) }
      }
      if (perms.includes('production:semi-in:add') && isPermEnabled('production', 'production:semi-in:add')) {
        if (!seen.has('/pages/scan/semi-in')) { seen.add('/pages/scan/semi-in'); result.push({ path: '/pages/scan/semi-in', title: '半成品入库', icon: '📗' }) }
      }
      if (perms.includes('production:finished-in:add') && isPermEnabled('production', 'production:finished-in:add')) {
        if (!seen.has('/pages/scan/finished-in')) { seen.add('/pages/scan/finished-in'); result.push({ path: '/pages/scan/finished-in', title: '成品入库', icon: '📕' }) }
      }
    }
  } catch (e) {}
  return result
})

function nav(url) {
  navigateTo(url)
}

// ============================================================
// v1.1.100+: 全部功能 (子标签分组) — 数据源仍是 visibleMenus (已按 perm 裁剪),
//   只是把它们按业务分类分到横向子标签里. 每个功能项带 group 字段:
//   生产管理 / 采购管理 / 销售管理 / 库存管理 / XK记录 / 现场填报 / 经营报表
// ============================================================
const FUNC_GROUP_ORDER = ['生产管理', '采购管理', '销售管理', '库存管理', 'XK记录', '现场填报', '经营报表']

// 按目标路径判分类 (visibleMenus 每项 {path,title,icon})
function classifyFunc(item) {
  const p = item.path
  if (p.startsWith('/pages/production/')) return '生产管理'
  // v1.1.101 XK 生产全流程 4 个 scan 操作页 (首检/巡检/半成品入库/成品入库) 归生产管理
  if (p === '/pages/scan/fai' || p === '/pages/scan/pat' || p === '/pages/scan/semi-in' || p === '/pages/scan/finished-in') return '生产管理'
  if (p.startsWith('/pages/purchase/') || p === '/pages/scan/in') return '采购管理'
  if (p.startsWith('/pages/sales/') || p === '/pages/scan/out') return '销售管理'
  if (p.startsWith('/pages/inventory/') || p === '/pages/count/index') return '库存管理'
  // v1.1.102 hotfix-3: 设备维保走现场填报 (外勤巡检+拍照取证, 业务上属现场作业)
  //   其他 XK 视图 (批次溯源/安全/打印/审计) 仍归 XK记录, 唯独设备维保单独划到现场填报
  if (p === '/pages/xk/maintenance') return '现场填报'
  if (p.startsWith('/pages/xk/')) return 'XK记录'
  if (p === '/pages/report/ai') return '经营报表'
  if (p === '/pages/report/index') return '经营报表'
  return '现场填报'
}

const activeFuncGroup = ref(FUNC_GROUP_ORDER[0])

// 分组: 只保留有功能的分组, 按 FUNC_GROUP_ORDER 排序
const funcGroups = computed(() => {
  const map = new Map()
  for (const it of visibleMenus.value) {
    const g = classifyFunc(it)
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(it)
  }
  const names = FUNC_GROUP_ORDER.filter(n => map.has(n))
  // 兜底: 分类外的杂项并入 "现场填报" (理论上不该出现)
  for (const n of [...map.keys()]) {
    if (FUNC_GROUP_ORDER.indexOf(n) < 0 && !names.includes(n)) names.push(n)
  }
  return names.map(n => ({ name: n, items: map.get(n) }))
})

const activeFuncItems = computed(() => {
  const g = funcGroups.value.find(x => x.name === activeFuncGroup.value)
  if (g) return g.items
  // 当前分组无内容 (perm 收窄后空了) → 回退到第一个非空分组
  return funcGroups.value.length ? funcGroups.value[0].items : []
})

function openLedger() {
  navigateTo('/pages/inventory/query')
}

function loadUser() {
  const raw = uni.getStorageSync('erp_user')
  if (typeof raw === 'object' && raw) {
    user.value = raw
  } else if (typeof raw === 'string') {
    try { user.value = JSON.parse(raw) } catch (e) { user.value = {} }
  }
}

async function loadWarningItems() {
  if (!warningVisible.value) return
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
  } catch (e) { /* 静默 — 无 perm 时 403 也不报错 */ }
}

// v1.1.100+: 现场待办 — 3 类真接后端 (各取前 3 条合并展示)
//   ① 到货待检: IQC 来料检验单草稿 (qmsInspectionPage, inspectionType=IQC, status=DRAFT)
//   ② 维保任务: em_maintain_order 新表草稿工单 (xk maintenance/orders, billStatus=PENDING —
//     走 XK 端点, v1.1.99 附带清理后唯一维保数据源, 避免读老 wf_maintain_record 死表)
//   ③ XK 复检:  成品复检 OQC 草稿 (qmsInspectionPage, inspectionType=OQC, status=DRAFT)
// 门禁: 各类受对应模块开关 (qms/em) 控制; 无 perm/模块关时静默跳过 (拦截器 403 不弹, R13).
async function loadTodoItems() {
  const todos = []

  // ① 到货待检 (IQC 草稿) — 跳质量检验页
  // 注意: qms controller 的筛选 param 是 status (不是 billStatus), 见 QmsInspectionController.java:56
  if (isModuleEnabled('qms') && isPermEnabled('qms', 'quality:inspection:list')) {
    try {
      const r = await api.qmsInspectionPage({ pageNum: 1, pageSize: 3, inspectionType: 'IQC', status: 'DRAFT' })
      const recs = (r && (r.records || r.list)) || (Array.isArray(r) ? r : [])
      for (const x of (Array.isArray(recs) ? recs : []).slice(0, 3)) {
        todos.push({
          key: 'iqc-' + x.id,
          icon: '📦', cat: '到货待检',
          title: (x.productName || x.billNo || '来料检验'),
          time: (x.billDate || '').toString().substring(5, 10) || '',
          target: '/pages/quality/inspect',
          extra: x.id ? { id: x.id } : {}
        })
      }
    } catch (e) { /* 静默 R13 */ }
  }

  // ② 维保任务 (em_maintain_order 新表, 走 XK 端点拿 PENDING 工单)
  // 原因: api.maintainPage 走 /workflow/maintain/page (老 wf_maintain_record 表, v1.1.99 附带清理后已废弃);
  //   App 端没有 em 模块独立 controller, 唯一新表数据源是 XkController.maintenance/orders,
  //   它内部用 pageForXk(... status='PENDING'). 门禁走 work:maintain:list (XK 自带, 大多数角色都有).
  if (isModuleEnabled('em') && isPermEnabled('em', 'equipment:master:list')) {
    try {
      const r = await api.xkMaintenanceOrders({ pageNum: 1, pageSize: 3, billStatus: 'PENDING' })
      const recs = (r && (r.records || r.list)) || (Array.isArray(r) ? r : [])
      for (const x of (Array.isArray(recs) ? recs : []).slice(0, 3)) {
        todos.push({
          key: 'maint-' + x.id,
          icon: '🔧', cat: '维保任务',
          title: (x.equipmentName || x.equipmentCode || '设备维保') + (x.maintContent ? ' · ' + x.maintContent.substring(0, 12) : ''),
          time: (x.planDate || '').toString().substring(5, 10) || '',
          target: '/pages/equipment/my',
          extra: x.id ? { id: x.id } : {}
        })
      }
    } catch (e) { /* 静默 R13 */ }
  }

  // ③ XK 复检 (OQC 草稿) — 跳质量检验页
  if (isModuleEnabled('qms') && isPermEnabled('qms', 'quality:inspection:list')) {
    try {
      const r = await api.qmsInspectionPage({ pageNum: 1, pageSize: 3, inspectionType: 'OQC', status: 'DRAFT' })
      const recs = (r && (r.records || r.list)) || (Array.isArray(r) ? r : [])
      for (const x of (Array.isArray(recs) ? recs : []).slice(0, 3)) {
        todos.push({
          key: 'oqc-' + x.id,
          icon: '🛡', cat: 'XK 复检',
          title: (x.productName || x.billNo || '成品复检') + ' 复检',
          time: (x.billDate || '').toString().substring(5, 10) || '',
          target: '/pages/quality/inspect',
          extra: x.id ? { id: x.id } : {}
        })
      }
    } catch (e) { /* 静默 R13 */ }
  }

  // ④ 半成品入库待审 (仓管: prd_semi_in DRAFT, 过 FAI 门禁) — 跳 scan/semi-in
  if (isModuleEnabled('production') && isPermEnabled('production', 'production:semi-in:list')) {
    try {
      const r = await api.prdSemiInPage({ pageNum: 1, pageSize: 3, billStatus: 'DRAFT' })
      const recs = (r && (r.records || r.list)) || (Array.isArray(r) ? r : [])
      for (const x of (Array.isArray(recs) ? recs : []).slice(0, 3)) {
        todos.push({
          key: 'semiin-' + x.id,
          icon: '📗', cat: '半成品待入',
          title: (x.productName || x.prdOrderNo || '半成品') + (x.batchNo ? ' · ' + x.batchNo : ''),
          time: (x.billDate || '').toString().substring(5, 10) || '',
          target: '/pages/scan/semi-in',
          extra: x.id ? { id: x.id } : {}
        })
      }
    } catch (e) { /* 静默 R13 */ }
  }

  // ⑤ 成品入库待审 (仓管: prd_finished_in DRAFT, 过工序 + OQC 门禁) — 跳 scan/finished-in
  if (isModuleEnabled('production') && isPermEnabled('production', 'production:finished-in:list')) {
    try {
      const r = await api.prdFinishedInPage({ pageNum: 1, pageSize: 3, billStatus: 'DRAFT' })
      const recs = (r && (r.records || r.list)) || (Array.isArray(r) ? r : [])
      for (const x of (Array.isArray(recs) ? recs : []).slice(0, 3)) {
        todos.push({
          key: 'finin-' + x.id,
          icon: '📕', cat: '成品待入',
          title: (x.productName || x.prdOrderNo || '成品') + (x.batchNo ? ' · ' + x.batchNo : ''),
          time: (x.billDate || '').toString().substring(5, 10) || '',
          target: '/pages/scan/finished-in',
          extra: x.id ? { id: x.id } : {}
        })
      }
    } catch (e) { /* 静默 R13 */ }
  }

  todoItems.value = todos
}

function openTodo(t) {
  if (!t || !t.target) return
  if (t.extra && t.extra.id) {
    try { uni.setStorageSync('erp_todo_focus', { target: t.target, id: t.extra.id }) } catch (e) {}
  }
  navigateTo(t.target)
}

onMounted(async () => {
  loadUser()
  const h = new Date().getHours()
  greeting.value = h < 6 ? '凌晨好' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'

  // v1.1.80+: 进入工作台时强制拉一次最新模块配置 (TTL 内的快速操作也能即时生效)
  await loadAppModuleConfig().catch(() => { /* 静默 */ })

  // v1.1.54 hotfix: 必须在 recompute* 之前先调 /me 同步最新 permissions.
  // 之前顺序是 recomputeWarningVisible() 先跑 (读陈旧 erp_permissions), 后面 /me 才刷新 storage —
  // 老 APK 缓存里没 inventory:warning:list → warningVisible 永远 false → 库存预警区块不渲染,
  // 即使 PC 端已勾选权限且 /me 返回了新 perm.
  // 现在: /me 先写 storage, recompute* 再读, 命中即渲染.
  //
  // v1.1.55 hotfix 2026-09-21 晚: 多写一份 erp_app_permissions (从 appMenus 派生的纯 APP 端 perm 数组).
  //   后端 selectPermsByUserId 不分端 (PC+APP 混合), erp_permissions 含 PC 端 perm.
  //   例如 WAREHOUSE_MGR (仓管员) PC 端有 uncheck 但 APP 端无 uncheck, 混合数组会
  //   让 App 端反审核按钮误显示. 详情页 canCheck/canUncheck 改用 getAppPermissions(),
  //   这里派生并写 storage. 老 APK fallback 仍能工作 (getAppPermissions fallback 到 erp_menus 实时派生).
  let meResult = null
  try {
    const r = await api.me()
    meResult = r.data || r
    localStorage.setItem('erp_permissions', JSON.stringify(meResult.permissions || []))
    // App 端优先用 appMenus
    const appMenus = meResult.appMenus || meResult.menus || []
    localStorage.setItem('erp_menus', JSON.stringify(appMenus))
    // v1.1.55 hotfix: 派生纯 APP 端 perm 数组 (从 appMenus.perms 逗号分隔展开)
    const appPermSet = new Set()
    for (const m of (Array.isArray(appMenus) ? appMenus : [])) {
      if (!m.perms) continue
      for (const p of String(m.perms).split(',')) {
        const t = p.trim()
        if (t) appPermSet.add(t)
      }
    }
    localStorage.setItem('erp_app_permissions', JSON.stringify(Array.from(appPermSet)))
    localStorage.setItem('erp_user', JSON.stringify(meResult))
  } catch (e) { /* 忽略 — 不阻塞 UI (storage 还是老值, recompute 仍走老路径) */ }

  // v1.1.53: 计算各区块显隐 — KPI / 库存预警 / 经营简报入口 各自独立
  recomputeKpiVisible()
  recomputeWarningVisible()
  recomputeReportEntryVisible()
  // v1.1.11+: 有 KPI 权限才请求数据 + 失败时主动隐藏 KPI 区块 (兼容老版本残留 + perm 时序错位)
  if (kpiVisible.value) {
    try {
      kpi.value = await api.dashboard()
    } catch (e) {
      // 403 (无权限) / 网络错: 静默, 并隐藏 KPI 区块避免后续空数据报错
      kpiVisible.value = false
    }
  }
  // v1.1.53: 库存预警列表独立加载 — 即使没 KPI perm 也能单独显示预警
  await loadWarningItems()
  // v1.1.100+: 现场待办 (到货待检/维保任务/复检, 各类受模块开关控制, 无 perm 静默)
  await loadTodoItems()
  applyTabBar()

  // v1.0.10+: perms 缺失或空时, 主动调 /me 修复 (兼容老版本残留)
  // v1.1.12+: 同时调 /me 时拿到最新 appMenus + permissions, 即使缓存非空也覆盖一次
  // (解决用户在 PC 端改了授权但 App 端不显示的 bug)
  // v1.1.53+: /me 之后 re-launch 后 recompute 函数会再跑一遍, 新 perm 自动生效
  // v1.1.54 hotfix: /me 已在上面同步完成, 这里只处理 erp_menus 变化导致的 re-launch
  try {
    if (meResult && meResult.appMenus) {
      const cachedKeys = uni.getStorageSync('erp_menus_keys') || ''
      const newKeys = meResult.appMenus.map(m => m.id).sort().join(',')
      if (cachedKeys !== newKeys) {
        uni.setStorageSync('erp_menus_keys', newKeys)
        // 强制重载 dashboard 让 visibleMenus 重新计算
        // navigateTo: 原生走 uni.reLaunch (dashboard 非 tabBar 实际走 switchTab), H5 走 hash 路由, 避免 H5 'reLaunch:fail'
        navigateTo('/pages/dashboard/index')
        return // re-launch 会重跑 onMounted, 后面的代码不再执行
      }
    }
  } catch (e) { /* 忽略 */ }
})
</script>
<style scoped>
/* v1.1.100+: 蓝色渐变头部 */
.hero {
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  color: #fff;
  border-radius: 12px;
  padding: 16px 16px 14px;
  margin-bottom: 10px;
}
.hero text { color: #fff; }
.hero-top { display: flex; align-items: center; justify-content: space-between; }
.hero-hello { font-size: 17px; font-weight: bold; }
.hero-bell { font-size: 18px; opacity: 0.9; }
.hero-sub { margin-top: 4px; }
.hero-sub-role { font-size: 13px; opacity: 0.9; }
.hero-date { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; opacity: 0.85; }
.hero-shift {
  background: rgba(255,255,255,0.22);
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 11px;
}

/* v1.1.100+: 全部功能 卡片 + 子标签 */
.func-card { margin-bottom: 10px; }
.func-head { display: flex; align-items: center; justify-content: space-between; }
.func-title { font-size: 15px; font-weight: 600; color: #303133; }
.func-more { color: #909399; font-size: 16px; }
.func-tabs { margin: 10px 0 4px; white-space: nowrap; }
.func-tab-list { display: inline-flex; gap: 8px; }
.func-tab {
  display: inline-block;
  padding: 4px 12px;
  font-size: 12px;
  border-radius: 14px;
  background: #f4f4f5;
  color: #606266;
  flex-shrink: 0;
}
.func-tab-active { background: var(--primary); color: #fff; }
.func-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 8px;
}
.func-empty { grid-column: 1 / -1; text-align: center; color: #999; font-size: 12px; padding: 16px 0; }

/* v1.1.100+: 库存预警 头部 (标题 + 查看台账) */
.warn-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 4px; }
.warn-count {
  display: inline-block;
  min-width: 18px; height: 18px; line-height: 18px;
  padding: 0 5px;
  margin-left: 6px;
  background: #f56c6c; color: #fff;
  border-radius: 9px;
  font-size: 11px; text-align: center;
}
.warn-more { color: var(--primary); font-size: 12px; flex-shrink: 0; padding-top: 2px; }
.warn-cur { margin-left: auto; font-size: 11px; color: #909399; }

/* v1.1.100+: 现场待办 */
.todo-head { margin-bottom: 10px; }
.todo-item {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 10px;
  border-radius: 8px;
  background: #f7f9fc;
  margin-bottom: 8px;
}
.todo-item:last-child { margin-bottom: 0; }
.todo-ic {
  width: 38px; height: 38px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: #fff; border: 1px solid #e8ecf2;
  border-radius: 9px; font-size: 18px;
}
.todo-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.todo-cat { font-size: 11px; color: #909399; }
.todo-title { font-size: 14px; color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.todo-time { font-size: 12px; color: var(--primary); font-weight: 600; flex-shrink: 0; }
.todo-arrow { color: #c0c4cc; font-size: 16px; flex-shrink: 0; }

.quick-item { display: flex; flex-direction: column; align-items: center; padding: 10px 0; background: #f9f9f9; border-radius: 6px; }
.quick-icon { font-size: 24px; margin-bottom: 4px; }
.quick-item text:last-child { font-size: 12px; color: #555; text-align: center; }

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
