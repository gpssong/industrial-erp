<template>
  <view class="container">
    <view class="card search-bar">
      <input class="input" v-model="keyword" placeholder="扫码或输入编码/名称" @confirm="loadData" />
    </view>
    <view class="card" v-for="item in list" :key="item.id" @click="onDetail(item)">
      <view class="row">
        <view>
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }} / {{ item.spec }}</text>
        </view>
        <view style="text-align:right">
          <text style="color:var(--primary);font-weight:bold;font-size:16px">{{ item.qty }}</text>
          <text class="muted" style="display:block">{{ item.unitName }}</text>
        </view>
      </view>
      <view class="row" style="margin-top:6px">
        <text class="muted">{{ item.warehouseName }} · {{ item.batchNo || '无批次' }}</text>
        <text class="muted">均价 ¥{{ item.avgCost }}</text>
      </view>
    </view>
    <view v-if="!list.length" class="empty">暂无库存数据</view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '@/api/index.js'
const keyword = ref('')
const list = ref([])
async function loadData() { list.value = (await api.stockPage({ pageNum: 1, pageSize: 50, keyword: keyword.value })).records || [] }
function onDetail(item) { uni.showModal({ title: item.productName, content: `库存: ${item.qty} ${item.unitName}\n均价: ¥${item.avgCost}\n总成本: ¥${item.totalCost}` }) }
onMounted(loadData)
</script>
<style scoped>.search-bar { padding: 10px; } .empty { text-align: center; color: #999; padding: 40px; }</style>
