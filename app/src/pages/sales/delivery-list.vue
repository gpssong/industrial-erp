<template>
  <view class="container">
    <view class="card search-bar">
      <view class="search-row">
        <input class="input" v-model="keyword" placeholder="单号" @confirm="loadData" />
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
        <text class="info-label">客户</text>
        <text class="info-value">{{ item.customerName || '—' }}</text>
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

    <view v-if="!loading && !list.length" class="empty">暂无出库单</view>
    <view v-if="loading && pageNum === 1" class="empty">加载中...</view>
    <view v-if="loading && pageNum > 1" class="empty">加载更多中...</view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
import { applyTabBar } from '../../utils/permission.js'

const STATUS_OPTIONS = [
  { value: '',        label: '全部' },
  { value: 'DRAFT',   label: '草稿' },
  { value: 'CHECKED', label: '已审核' }
]

const STATUS_MAP = {
  DRAFT: '草稿', CHECKED: '已审核'
}

const keyword = ref('')
const billStatus = ref('')
const list = ref([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(50)
const hasMore = ref(false)

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
    if (billStatus.value) params.billStatus = billStatus.value
    const r = await api.salesDeliveryPage(params)
    const records = (r && r.records) || []
    list.value = list.value.concat(records)
    hasMore.value = records.length >= pageSize.value
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
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
  uni.navigateTo({ url: '/pages/sales/delivery-detail?id=' + String(item.id) })
}

onMounted(() => { loadData(); applyTabBar() })

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
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.search-bar { padding: 10px; }
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