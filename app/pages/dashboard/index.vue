<template>
  <view class="container">
    <view class="header card">
      <view class="row">
        <view>
          <text style="font-size:16px;font-weight:bold">{{ greeting }}, {{ user?.nickname }}</text>
          <text class="muted" style="display:block;margin-top:4px">{{ today }}</text>
        </view>
        <text class="badge">{{ user?.deptName }}</text>
      </view>
    </view>
    <view class="grid-4" style="margin-bottom:10px">
      <view class="kpi"><text class="kpi-value">¥{{ kpi.todaySales }}</text><text class="kpi-label">今日销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.totalSales }}</text><text class="kpi-label">累计销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.arBalance }}</text><text class="kpi-label">应收余额</text></view>
      <view class="kpi"><text class="kpi-value">{{ kpi.stockSkuCount }}</text><text class="kpi-label">SKU数</text></view>
    </view>
    <view class="card">
      <text class="title">业务快捷</text>
      <view class="grid-4" style="margin-top:8px">
        <view class="quick-item" @click="go('/pages/sales/quick')"><text class="quick-icon">📝</text><text>手机开单</text></view>
        <view class="quick-item" @click="go('/pages/scan/in')"><text class="quick-icon">📥</text><text>扫码入库</text></view>
        <view class="quick-item" @click="go('/pages/scan/out')"><text class="quick-icon">📤</text><text>扫码出库</text></view>
        <view class="quick-item" @click="go('/pages/count/index')"><text class="quick-icon">📋</text><text>外勤盘点</text></view>
        <view class="quick-item" @click="go('/pages/inventory/query')"><text class="quick-icon">📦</text><text>查库存</text></view>
        <view class="quick-item" @click="go('/pages/sales/order')"><text class="quick-icon">📃</text><text>销售订单</text></view>
        <view class="quick-item" @click="go('/pages/purchase/order')"><text class="quick-icon">📋</text><text>采购订单</text></view>
        <view class="quick-item" @click="go('/pages/report/index')"><text class="quick-icon">📊</text><text>经营简报</text></view>
      </view>
    </view>
    <view class="card" v-if="kpi.warningCount">
      <text class="title">⚠️ 库存预警 ({{ kpi.warningCount }})</text>
      <text class="muted">有 {{ kpi.warningCount }} 个商品库存低于安全线</text>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
const user = ref({})
const kpi = ref({ todaySales: 0, totalSales: 0, arBalance: 0, stockSkuCount: 0, warningCount: 0 })
const today = new Date().toISOString().substring(0, 10)
const greeting = ref('您好')
function go(url) {
  const tabbarPages = ['/pages/dashboard/index', '/pages/inventory/query', '/pages/sales/quick', '/pages/report/index', '/pages/profile/index']
  if (tabbarPages.includes(url)) uni.switchTab({ url })
  else uni.navigateTo({ url })
}
onMounted(async () => {
  user.value = JSON.parse(uni.getStorageSync('erp_user') || '{}')
  const h = new Date().getHours()
  greeting.value = h < 6 ? '凌晨好' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
  try { kpi.value = await api.dashboard() } catch (e) {}
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
