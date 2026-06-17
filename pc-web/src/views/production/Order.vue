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
        <el-table-column prop="productName" label="成品" />
        <el-table-column prop="planQty" label="计划数量" width="100" align="right" />
        <el-table-column prop="actualQty" label="实际数量" width="100" align="right" />
        <el-table-column prop="goodQty" label="良品" width="100" align="right" />
        <el-table-column prop="lossQty" label="损耗" width="100" align="right" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
        <el-table-column prop="workshop" label="车间" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag>{{ ({DRAFT:'草稿',RELEASED:'已开工',PRODUCING:'生产中',FINISHED:'已完成',CLOSED:'已关闭'})[row.billStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT' || row.billStatus==='RELEASED'" link type="primary" @click="onRelease(row)">开工</el-button>
            <el-button v-if="row.billStatus==='RELEASED' || row.billStatus==='PRODUCING'" link type="primary" @click="onFinish(row)">完工</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="data.total" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
    <el-dialog v-model="finishVisible" title="完工登记" width="420px">
      <el-form :model="finishForm" label-width="100px">
        <el-form-item label="良品数量"><el-input-number v-model="finishForm.goodQty" :precision="4" :min="0" /></el-form-item>
        <el-form-item label="损耗数量"><el-input-number v-model="finishForm.lossQty" :precision="4" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="finishVisible=false">取消</el-button>
        <el-button type="primary" @click="doFinish">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { prdOrderApi } from '@/api/production'
import { ElMessage } from 'element-plus'
const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const finishVisible = ref(false)
const finishForm = reactive({ id: null, goodQty: 0, lossQty: 0 })

async function loadData() { loading.value = true; try { data.value = (await prdOrderApi.page(query)).data } finally { loading.value = false } }
async function onRelease(row) {
  await prdOrderApi.release(row.id); ElMessage.success('已开工, 自动生成领料单'); loadData()
}
function onFinish(row) {
  finishForm.id = row.id; finishForm.goodQty = row.planQty || 0; finishForm.lossQty = 0
  finishVisible.value = true
}
async function doFinish() {
  await prdOrderApi.finish(finishForm.id, { goodQty: finishForm.goodQty, lossQty: finishForm.lossQty })
  ElMessage.success('完工, 成品已入库'); finishVisible.value = false; loadData()
}
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
