<template>
  <div class="container">
    <div class="card search-bar">
      <div class="search-row">
        <input class="input" v-model="keyword" placeholder="扫码或输入编码/名称" @confirm="loadData" />
        <button class="btn-scan" @click="onScan" title="扫码">📷</button>
        <button class="btn-search" @click="loadData">查询</button>
      </div>
    </div>
    <div class="card" v-for="item in list" :key="item.id" @click="onDetail(item)">
      <div class="row">
        <div>
          <div style="font-weight:bold">{{ item.productName }}</div>
          <div class="muted" style="display:block">{{ item.productCode }} / {{ item.spec }}</div>
        </div>
        <div style="text-align:right">
          <div style="color:#1e6091;font-weight:bold;font-size:16px">{{ item.qty }}</div>
          <div class="muted" style="display:block">{{ item.unitName }}</div>
        </div>
      </div>
      <div class="row" style="margin-top:6px">
        <span class="muted">{{ item.warehouseName }} · {{ item.batchNo || '无批次' }}</span>
        <span class="muted">均价 ¥{{ item.avgCost }}</span>
      </div>
    </div>
    <div v-if="!list.length" class="empty">暂无库存数据</div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'
import { applyTabBar } from '../../utils/permission.js'

const keyword = ref('')
const list = ref([])

async function loadData() {
  try {
    const r = await api.stockPage({ pageNum: 1, pageSize: 50, keyword: keyword.value })
    list.value = (r && r.records) || []
  } catch (e) {
    list.value = []
  }
}

function onDetail(item) {
  alert(`${item.productName}\n库存: ${item.qty} ${item.unitName}\n均价: ¥${item.avgCost}\n总成本: ¥${item.totalCost}`)
}

async function onScan() {
  await doScan({
    onResult(text) { keyword.value = text; loadData() },
    onCancel() {},
    onError(err) { console.error('扫码失败:', err) }
  })
}

onMounted(() => { loadData(); applyTabBar() })
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.search-bar { padding: 10px; }
.search-row { display: flex; gap: 6px; align-items: center; }
.input { flex: 1; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn-scan { background: #27ae60; color: #fff; border: none; border-radius: 4px; width: 40px; height: 36px; cursor: pointer; font-size: 16px; }
.btn-scan:hover { background: #2ecc71; }
.btn-search { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 0 14px; height: 36px; cursor: pointer; font-size: 14px; }
.btn-search:hover { background: #2980b9; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #999; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 40px; }
</style>
