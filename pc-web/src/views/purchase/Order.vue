<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="supplierName" label="供应商" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmount" label="不含税" width="120" align="right" />
        <el-table-column prop="totalAmountTax" label="价税合计" width="120" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='DRAFT'?'info':'success'">{{ row.billStatus }}</el-tag></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="data.total" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { purOrderApi } from '@/api/purchase'
const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
async function loadData() { loading.value = true; try { data.value = (await purOrderApi.page(query)).data } finally { loading.value = false } }
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
