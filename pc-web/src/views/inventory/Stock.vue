<template>
  <div>
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">库存管理 / 查询</div>
        <h1 class="xk-title">库存查询</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="loadData">查询</el-button>
      </div>
    </div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" clearable placeholder="编码/名称/规格/批次" /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="productCode" label="商品编码" width="140" />
        <el-table-column prop="productName" label="商品名称" />
        <el-table-column prop="spec" label="规格" width="140" />
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column prop="batchNo" label="批次" width="100" />
        <el-table-column prop="qty" label="库存" width="100" align="right" />
        <el-table-column prop="availableQty" label="可用" width="100" align="right" />
        <el-table-column prop="lockQty" label="锁定" width="80" align="right" />
        <el-table-column prop="avgCost" label="平均成本" width="100" align="right" />
        <el-table-column prop="totalCost" label="总成本" width="120" align="right" />
        <el-table-column prop="lastInDate" label="最后入库" width="110" />
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" :current-page="query.pageNum" :page-size="query.pageSize" @current-change="loadData" @size-change="loadData" :page-sizes="[10,20,50,100]" />
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { stockApi } from '@/api/inventory'
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
async function loadData() { loading.value = true; try { data.value = (await stockApi.page(query)).data } finally { loading.value = false } }
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
