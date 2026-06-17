<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="业务类型">
          <el-select v-model="query.billType" clearable style="width:160px">
            <el-option label="采购入库" value="PUR_RECEIPT" />
            <el-option label="销售出库" value="SAL_DELIVERY" />
            <el-option label="成品入库" value="PROD_IN" />
            <el-option label="领料" value="PROD_OUT" />
            <el-option label="调拨" value="TRANSFER" />
            <el-option label="盘盈亏" value="CHECK" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column prop="bizDate" label="日期" width="110" />
        <el-table-column prop="billType" label="业务" width="120" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="batchNo" label="批次" width="100" />
        <el-table-column label="方向" width="80">
          <template #default="{ row }">
            <el-tag :type="row.bizDirection===1?'success':'danger'">{{ row.bizDirection===1?'入':'出' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="100" align="right" />
        <el-table-column prop="price" label="单价" width="100" align="right" />
        <el-table-column prop="amount" label="金额" width="120" align="right" />
        <el-table-column prop="beforeQty" label="操作前" width="100" align="right" />
        <el-table-column prop="afterQty" label="操作后" width="100" align="right" />
        <el-table-column prop="afterAvgCost" label="平均成本" width="100" align="right" />
        <el-table-column prop="remark" label="备注" />
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="data.total" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ledgerApi } from '@/api/inventory'
const query = reactive({ pageNum: 1, pageSize: 20, billType: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
async function loadData() { loading.value = true; try { data.value = (await ledgerApi.page(query)).data } finally { loading.value = false } }
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
