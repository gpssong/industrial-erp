<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column prop="bomCode" label="BOM编号" width="160" />
        <el-table-column prop="bomName" label="BOM名称" />
        <el-table-column prop="productCode" label="成品编码" width="140" />
        <el-table-column prop="productName" label="成品名称" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="onView(row)">查看明细</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="data.total" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
    <el-dialog v-model="detailVisible" :title="`BOM: ${current?.bomName}`" width="900px">
      <el-table :data="current?.details || []" size="small" border>
        <el-table-column prop="materialType" label="类型" width="80" />
        <el-table-column prop="productCode" label="原料编码" width="120" />
        <el-table-column prop="productName" label="原料名称" />
        <el-table-column prop="spec" label="规格" />
        <el-table-column prop="baseQty" label="基础用量" width="100" align="right" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { bomApi } from '@/api/production'
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const detailVisible = ref(false)
const current = ref(null)
async function loadData() { loading.value = true; try { data.value = (await bomApi.page(query)).data } finally { loading.value = false } }
async function onView(row) { current.value = (await bomApi.detail(row.id)).data; detailVisible.value = true }
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
