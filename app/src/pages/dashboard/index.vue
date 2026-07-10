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
    <view class="grid-4" style="margin-bottom:10px">
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
    </view>
  </view>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../../api/index.js'
import { navigateTo } from '../../utils/nav.js'
import { applyTabBar, isAdmin } from '../../utils/permission.js'

const user = ref({})
const kpi = ref({ todaySales: 0, totalSales: 0, arBalance: 0, stockSkuCount: 0, warningCount: 0 })
const today = new Date().toISOString().substring(0, 10)
const greeting = ref('您好')

// PC 端菜单路径 -> App 端页面映射 (v1.1.7+: 删除手机开单 + 销售订单, 扫码出库=PC 销售出库; v1.1.8+ 删除采购订单入口)
const PATH_TO_APP = {
  '/sales/return': { path: '/pages/scan/out', title: '扫码出库', icon: '📤' },
  '/purchase/receipt': { path: '/pages/scan/in', title: '扫码入库', icon: '📥' },
  '/inventory/stock': { path: '/pages/inventory/query', title: '查库存', icon: '📦' },
  '/inventory/ledger': { path: '/pages/inventory/query', title: '库存台账', icon: '📒' },
  '/production/order': { path: '/pages/count/index', title: '外勤盘点', icon: '📋' },
  '/report/dashboard': { path: '/pages/report/index', title: '经营简报', icon: '📊' }
}

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
  // 管理员: 显示全部 App 端功能 (v1.1.8+ 移除采购订单)
  if (isAdmin()) {
    return [
      PATH_TO_APP['/sales/return'],
      PATH_TO_APP['/purchase/receipt'],
      PATH_TO_APP['/inventory/stock'],
      PATH_TO_APP['/production/order'],
      PATH_TO_APP['/report/dashboard'],
      PATH_TO_APP['/inventory/ledger']
    ]
  }
  // 普通用户: 从 PC 端已分配的菜单中, 映射出 App 端可用功能
  const serverMenus = getServerMenus()
  const paths = new Set(serverMenus.map(m => m.path).filter(Boolean))
  const seen = new Set()
  const result = []
  for (const m of serverMenus) {
    if (!m.path) continue
    const app = PATH_TO_APP[m.path]
    if (app && !seen.has(app.path)) {
      seen.add(app.path)
      result.push(app)
    }
  }
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
  try { kpi.value = await api.dashboard() } catch (e) {}
  applyTabBar()
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
</style>
