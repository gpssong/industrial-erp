<template>
  <view class="container">
    <view class="card search-bar">
      <view class="search-row">
        <input class="input" v-model="keyword" placeholder="单号/成品名" @confirm="loadData" />
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
        <view style="flex:1">
          <view class="bill-no">{{ item.billNo }}</view>
          <view class="muted">{{ item.billDate }}</view>
        </view>
        <view :class="['tag', 'tag-' + (item.billStatus || 'DRAFT').toLowerCase()]">
          {{ statusTag(item.billStatus) }}
        </view>
      </view>
      <view class="product-line">
        <text class="product-name">{{ item.productName || '—' }}</text>
        <text v-if="item.spec" class="muted"> ({{ item.spec }})</text>
      </view>
      <view class="row bottom-line">
        <text class="muted">{{ item.workshop || '未指定车间' }} · 损耗 {{ item.lossRate || 0 }}%</text>
        <text class="qty">× {{ item.planQty || 0 }}</text>
      </view>
    </view>

    <view v-if="!loading && !list.length" class="empty">暂无生产单</view>
    <view v-if="loading" class="empty">加载中...</view>

    <!-- 浮动 + 按钮 — 新增生产单入口 -->
    <view class="fab" @click="goAdd">＋</view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { applyTabBar } from '../../utils/permission.js'

const STATUS_OPTIONS = [
  { value: '',        label: '全部' },
  { value: 'DRAFT',   label: '草稿' },
  { value: 'RELEASED', label: '已开工' },
  { value: 'PRODUCING', label: '生产中' },
  { value: 'FINISHED', label: '已完成' },
  { value: 'CLOSED', label: '已关闭' }
]

const STATUS_MAP = {
  DRAFT: '草稿', RELEASED: '已开工', PRODUCING: '生产中',
  FINISHED: '已完成', CLOSED: '已关闭'
}

const keyword = ref('')
const billStatus = ref('')
const list = ref([])
const loading = ref(false)

function statusTag(s) { return STATUS_MAP[s] || s || '—' }

function onStatusTap(v) {
  billStatus.value = v
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: 1, pageSize: 50 }
    if (keyword.value) params.billNo = keyword.value
    if (billStatus.value) params.billStatus = billStatus.value
    const r = await api.prdOrderPage(params)
    list.value = (r && r.records) || []
  } catch (e) {
    list.value = []
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
}

function goDetail(item) {
  // v1.1.11+: 用 uni.navigateTo 显式 push 详情页 (而非 reLaunch)
  // 避免详情页 reLaunch 后 Vue setup 模块级 ref/闭包状态污染导致第二次进入仍显示 a
  uni.navigateTo({ url: '/pages/production/order-detail?id=' + String(item.id) })
}

function goAdd() {
  uni.navigateTo({ url: '/pages/production/order-add' })
}

onMounted(() => { loadData(); applyTabBar() })
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 80px; }
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
.row { display: flex; justify-content: space-between; align-items: center; }
.bill-no { font-weight: bold; font-size: 15px; color: #303133; }
.product-line { margin-top: 8px; font-size: 14px; color: #303133; }
.product-name { font-weight: 500; }
.bottom-line { margin-top: 6px; }
.muted { color: #999; font-size: 12px; }
.qty { color: #1e6091; font-weight: bold; font-size: 16px; }
.tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; }
.tag-draft { background: #f4f4f5; color: #909399; }
.tag-released { background: #e1f3d8; color: #67c23a; }
.tag-producing { background: #faecd8; color: #e6a23c; }
.tag-finished { background: #d9ecff; color: #1890ff; }
.tag-closed { background: #f4f4f5; color: #909399; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }
.fab { position: fixed; right: 20px; bottom: 24px; width: 56px; height: 56px; border-radius: 50%; background: #1e6091; color: #fff; font-size: 28px; line-height: 56px; text-align: center; box-shadow: 0 4px 10px rgba(30,96,145,0.3); z-index: 99; }
.fab:active { background: #2980b9; }
</style>
