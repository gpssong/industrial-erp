<template>
  <view class="container">
    <view class="card" v-for="o in list" :key="o.id">
      <view class="row">
        <text style="font-weight:bold">{{ o.billNo }}</text>
        <text :class="o.billStatus==='DRAFT'?'tag warn':'tag ok'">{{ o.billStatus }}</text>
      </view>
      <text class="muted">{{ o.billDate }} · {{ o.customerName }}</text>
      <view class="row" style="margin-top:6px">
        <text class="muted">数量 {{ o.totalQty }}</text>
        <text style="color:var(--primary);font-weight:bold">¥ {{ o.totalAmountTax }}</text>
      </view>
    </view>
    <view v-if="!list.length" class="empty">暂无销售订单</view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
const list = ref([])
async function loadData() { list.value = (await api.salesOrderPage({ pageNum: 1, pageSize: 30 })).records || [] }
onMounted(loadData)
</script>
<style scoped>
.empty { text-align: center; color: #999; padding: 40px; }
.tag { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.tag.warn { background: #fef3e0; color: #e67e22; }
.tag.ok { background: #e8f5e9; color: #27ae60; }
</style>
