<template>
  <view class="container">
    <view class="card search-bar">
      <view class="search-row">
        <input class="input" v-model="keyword" placeholder="单号" @confirm="loadData" />
        <button class="btn-search" @click="loadData">查询</button>
      </view>
      <view class="search-row" style="margin-top:6px">
        <input class="input" v-model="productName" placeholder="按产品名称" @confirm="loadData" />
        <button class="btn-search" @click="loadData">查询</button>
      </view>
      <!-- 状态筛选 chip -->
      <scroll-view scroll-x="true" class="status-scroll" :show-scrollbar="false">
        <view class="status-chips">
          <view v-for="s in STATUS_OPTIONS" :key="s.value"
            :class="['chip', billStatus === s.value ? 'chip-active' : '']"
            @click="onStatusTap(s.value)">
            {{ s.label }}
          </view>
        </view>
      </scroll-view>
    </view>

    <!-- v1.1.125: 新建「非食品采购 · 免检直通」入口 (perm purchase:receipt:add 门禁) -->
    <view v-if="canAdd" class="add-bar">
      <button class="btn-add" @click="goAddNonFood">+ 非食品采购 · 免检直通</button>
    </view>

    <!-- 列表卡片 -->
    <view class="card" v-for="item in list" :key="item.id" @click="goDetail(item)">
      <view class="row">
        <view style="flex:1;min-width:0">
          <view class="bill-no">{{ item.billNo }}</view>
          <view class="muted">{{ item.billDate }}</view>
        </view>
        <view :class="['tag', 'tag-' + (item.billStatus || 'DRAFT').toLowerCase()]">
          {{ statusTag(item.billStatus) }}
        </view>
      </view>
      <view class="info-line">
        <text class="info-label">供应商</text>
        <text class="info-value">{{ item.supplierName || '—' }}</text>
      </view>
      <view class="info-line">
        <text class="info-label">仓库</text>
        <text class="info-value">{{ item.warehouseName || '—' }}</text>
      </view>
      <view class="product-line">
        <text class="product-name">{{ item.firstProductName || '—' }}</text>
      </view>
      <view class="row bottom-line">
        <text class="muted">总数量 {{ formatNum(item.totalQty) }}</text>
        <text class="qty">¥ {{ formatMoney(item.totalAmount) }}</text>
      </view>
    </view>

    <view v-if="!loading && !list.length" class="empty">暂无入库单</view>
    <view v-if="loading && pageNum === 1" class="empty">加载中...</view>
    <view v-if="loading && pageNum > 1" class="empty">加载更多中...</view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
import { applyTabBar } from '../../utils/permission.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const STATUS_OPTIONS = [
  { value: '',        label: '全部' },
  { value: 'DRAFT',   label: '草稿' },
  { value: 'CHECKED', label: '已审核' }
]

const STATUS_MAP = {
  DRAFT: '草稿', CHECKED: '已审核'
}

const keyword = ref('')
const productName = ref('')
const billStatus = ref('')
const list = ref([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(50)
const hasMore = ref(false)
// v1.1.125: 新建入口门禁 — purchase:receipt:add 的 APP 端授权 (R18)
const canAdd = ref(false)

// 读 App 端 perm 集合 (erp_menus 派生, 兼容 erp_permissions 兜底)
function hasAppPerm(p) {
  try {
    const menusRaw = uni.getStorageSync('erp_menus')
    const menus = typeof menusRaw === 'string' ? JSON.parse(menusRaw || '[]') : (Array.isArray(menusRaw) ? menusRaw : [])
    if (menus.length) {
      const set = new Set()
      for (const m of menus) {
        if (!m.perms) continue
        for (const t of String(m.perms).split(',')) { const x = t.trim(); if (x) set.add(x) }
      }
      if (set.has(p)) return true
    }
    const raw = uni.getStorageSync('erp_permissions')
    const arr = typeof raw === 'string' ? JSON.parse(raw || '[]') : (Array.isArray(raw) ? raw : [])
    return Array.isArray(arr) && arr.includes(p)
  } catch (e) { return false }
}

// v1.1.125: 跳「非食品采购 · 免检直通」建单页
function goAddNonFood() {
  uni.navigateTo({ url: '/pages/purchase/receipt-add' })
}

function statusTag(s) { return STATUS_MAP[s] || s || '—' }
function formatNum(v) { return v == null ? '—' : Number(v).toLocaleString() }
function formatMoney(v) {
  if (v == null) return '—'
  return Number(v).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function onStatusTap(v) {
  billStatus.value = v
  loadData(true)
}

async function loadData(reset = true) {
  if (reset) {
    pageNum.value = 1
    list.value = []
  }
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (keyword.value) params.billNo = keyword.value
    if (productName.value) params.productName = productName.value
    if (billStatus.value) params.billStatus = billStatus.value
    const r = await api.purchaseReceiptPage(params)
    const records = (r && r.records) || []
    list.value = list.value.concat(records)
    hasMore.value = records.length >= pageSize.value
  } catch (e) {
    // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg, catch 不重复弹 (列表 fallback 空数组)
    console.warn('[purchase-receipt-list.loadData] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (loading.value || !hasMore.value) return
  pageNum.value += 1
  loadData(false)
}

function goDetail(item) {
  uni.navigateTo({ url: '/pages/purchase/receipt-detail?id=' + String(item.id) })
}

onMounted(() => {
  // v1.1.80+: 模块停用守卫
  if (!guardPage('purchase')) return
  // v1.1.125: 判定能否新建 (APP 端 purchase:receipt:add)
  canAdd.value = hasAppPerm('purchase:receipt:add')
  loadData(); applyTabBar()
})

// 上拉加载更多 (App 端页面生命周期)
import { onReachBottom, onPullDownRefresh } from '@dcloudio/uni-app'
onReachBottom(() => loadMore())
onPullDownRefresh(async () => {
  await loadData(true)
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.search-bar { padding: 10px; }
.add-bar { padding: 0 0 10px; }
.btn-add { width: 100%; height: 42px; line-height: 42px; background: #67c23a; color: #fff; border: none; border-radius: 8px; font-size: 15px; font-weight: 600; }
.btn-add:active { background: #5daf4d; }
.search-row { display: flex; gap: 6px; align-items: center; }
.input { flex: 1; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn-search { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 0 14px; height: 36px; cursor: pointer; font-size: 14px; }
.btn-search:active { background: #2980b9; }
.status-scroll { margin-top: 10px; white-space: nowrap; }
.status-chips { display: inline-flex; gap: 8px; padding: 2px 0; }
.chip { display: inline-block; padding: 4px 12px; font-size: 12px; border-radius: 16px; background: #f4f4f5; color: #606266; cursor: pointer; }
.chip-active { background: #1e6091; color: #fff; }
.chip:active { opacity: 0.7; }
.row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.bill-no { font-weight: bold; font-size: 14px; color: #303133; word-break: break-all; }
.info-line { margin-top: 6px; font-size: 13px; display: flex; gap: 6px; }
.info-label { color: #999; min-width: 36px; }
.info-value { color: #303133; }
.product-line { margin-top: 8px; font-size: 14px; color: #303133; }
.product-name { font-weight: 500; }
.bottom-line { margin-top: 6px; }
.muted { color: #999; font-size: 12px; }
.qty { color: #1e6091; font-weight: bold; font-size: 14px; }
.tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.tag-draft { background: #f4f4f5; color: #909399; }
.tag-checked { background: #d9ecff; color: #1890ff; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }
</style>