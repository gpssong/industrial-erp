<template>
  <view class="container">
    <view v-if="loading" class="empty">加载中...</view>
    <view v-else-if="!order || !order.id" class="empty">出库单不存在</view>

    <template v-else>
      <!-- 顶部主表卡 -->
      <view class="card">
        <view class="row">
          <view style="flex:1;min-width:0">
            <view class="bill-no">{{ order.billNo }}</view>
            <view class="muted">{{ order.billDate }}</view>
          </view>
          <view :class="['tag', 'tag-' + (order.billStatus || 'DRAFT').toLowerCase()]">
            {{ statusTag(order.billStatus) }}
          </view>
        </view>
        <view class="info-grid">
          <view class="info-item">
            <text class="info-label">客户</text>
            <text class="info-value">{{ order.customerName || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">仓库</text>
            <text class="info-value">{{ order.warehouseName || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">总数量</text>
            <text class="info-value">{{ formatNum(order.totalQty) }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">总金额</text>
            <text class="info-value">¥ {{ formatMoney(order.totalAmount) }}</text>
          </view>
          <view v-if="order.totalAmountTax != null" class="info-item">
            <text class="info-label">含税金额</text>
            <text class="info-value">¥ {{ formatMoney(order.totalAmountTax) }}</text>
          </view>
          <view v-if="order.address" class="info-item">
            <text class="info-label">收货地址</text>
            <text class="info-value">{{ order.address }}</text>
          </view>
        </view>
      </view>

      <!-- 商品明细 -->
      <view class="card">
        <view class="section-title">商品明细 ({{ order.details ? order.details.length : 0 }})</view>
        <view v-if="!order.details || !order.details.length" class="muted" style="padding: 16px 0">
          暂无明细
        </view>
        <view v-for="(d, i) in order.details || []" :key="i" class="detail-row">
          <view class="detail-line-1">
            <text class="detail-line-no">{{ d.lineNo || (i + 1) }}</text>
            <text class="detail-name">{{ d.productName || '—' }}</text>
          </view>
          <view class="detail-line-2 muted">
            <text v-if="d.productCode">{{ d.productCode }} </text>
            <text v-if="d.spec">{{ d.spec }} </text>
            <text v-if="d.model">{{ d.model }} </text>
            <text v-if="d.colorNo">{{ d.colorNo }} </text>
            <text v-if="d.unitName">{{ d.unitName }}</text>
          </view>
          <view class="detail-line-3">
            <text class="muted">× {{ formatNum(d.qty) }}</text>
            <text v-if="d.price" class="muted"> × ¥ {{ formatMoney(d.price) }}</text>
            <text class="detail-amount">¥ {{ formatMoney(d.amount) }}</text>
          </view>
          <view v-if="d.batchNo || d.locationName" class="detail-line-3 muted" style="font-size:11px">
            <text v-if="d.batchNo">批次: {{ d.batchNo }} </text>
            <text v-if="d.locationName">库位: {{ d.locationName }}</text>
          </view>
        </view>
      </view>

      <!-- 备注 -->
      <view v-if="order.remark" class="card">
        <view class="section-title">备注</view>
        <view class="remark">{{ order.remark }}</view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'

const order = ref({})
const loading = ref(true)

function statusTag(s) {
  const map = { DRAFT: '草稿', CHECKED: '已审核' }
  return map[s] || s || '—'
}
function formatNum(v) { return v == null ? '—' : Number(v).toLocaleString() }
function formatMoney(v) {
  if (v == null) return '—'
  return Number(v).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

onLoad(async (q) => {
  loading.value = true
  try {
    const id = q && q.id
    if (!id) throw new Error('缺少单据 ID')
    const r = await api.salesDeliveryDetail(id)
    order.value = (r && (r.data || r)) || {}
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.bill-no { font-weight: bold; font-size: 15px; color: #303133; word-break: break-all; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.info-grid { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.info-item { display: flex; gap: 8px; font-size: 13px; }
.info-label { color: #999; min-width: 64px; flex-shrink: 0; }
.info-value { color: #303133; flex: 1; word-break: break-all; }
.muted { color: #999; font-size: 12px; }
.tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.tag-draft { background: #f4f4f5; color: #909399; }
.tag-checked { background: #d9ecff; color: #1890ff; }
.detail-row { padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.detail-row:last-child { border-bottom: none; }
.detail-line-1 { display: flex; gap: 8px; align-items: center; }
.detail-line-no { color: #999; font-size: 12px; min-width: 24px; }
.detail-name { color: #303133; font-size: 14px; font-weight: 500; flex: 1; }
.detail-line-2 { margin-top: 4px; }
.detail-line-3 { margin-top: 6px; display: flex; gap: 8px; justify-content: space-between; align-items: center; }
.detail-amount { color: #1e6091; font-weight: bold; font-size: 14px; }
.remark { color: #303133; font-size: 14px; line-height: 1.6; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }
</style>