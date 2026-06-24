<template>
  <view class="container">
    <view class="card">
      <text class="title">📊 经营简报</text>
      <text class="muted">{{ today }}</text>
    </view>
    <view class="grid-2" style="margin-bottom:10px">
      <view class="kpi"><text class="kpi-value">¥{{ kpi.todaySales }}</text><text class="kpi-label">今日销售</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.todayPurchase }}</text><text class="kpi-label">今日采购</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.arBalance }}</text><text class="kpi-label">应收余额</text></view>
      <view class="kpi"><text class="kpi-value">¥{{ kpi.apBalance }}</text><text class="kpi-label">应付余额</text></view>
    </view>
    <view class="card">
      <text class="title">库存 Top 5</text>
      <view v-for="(s, i) in stockList.slice(0,5)" :key="i" class="row" style="padding:6px 0;border-bottom:1px solid #f0f0f0">
        <view><text>{{ i+1 }}. {{ s.productName }}</text>
          <text class="muted" style="display:block;font-size:11px">{{ s.productCode }}</text>
        </view>
        <text style="color:var(--primary)">¥{{ s.totalCost }}</text>
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
const kpi = ref({ todaySales: 0, todayPurchase: 0, arBalance: 0, apBalance: 0 })
const stockList = ref([])
const today = new Date().toISOString().substring(0, 10)
onMounted(async () => {
  kpi.value = await api.dashboard()
  stockList.value = (await api.inventorySummary()) || []
})
</script>
<style scoped>.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }</style>
