<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="类型">
          <el-select v-model="query.billType" style="width:120px">
            <el-option label="应收" value="AR" />
            <el-option label="应付" value="AP" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column prop="sourceBillNo" label="来源单号" width="180" />
        <el-table-column prop="bizDate" label="日期" width="120" />
        <el-table-column prop="customerName" label="客户/供应商" />
        <el-table-column prop="amount" label="发生金额" width="120" align="right" />
        <el-table-column prop="paidAmount" label="已收/付" width="120" align="right" />
        <el-table-column prop="balance" label="未结" width="120" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.billStatus==='PAID'?'success':row.billStatus==='PARTIAL'?'warning':'danger'">{{ ({UNPAID:'未结清',PARTIAL:'部分',PAID:'已结清'})[row.billStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.balance>0" link type="primary" @click="onPay(row)">{{ row.billType==='AR'?'收款':'付款' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
    <el-dialog v-model="payVisible" :title="form.billType==='AR'?'收款单':'付款单'" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="收/付日期"><el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="收/付金额"><el-input-number v-model="form.amount" :precision="2" :min="0" /></el-form-item>
        <el-form-item label="收/付方式">
          <el-select v-model="form.payType" style="width:100%">
            <el-option label="现金" value="CASH" />
            <el-option label="银行转账" value="BANK" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payVisible=false">取消</el-button>
        <el-button type="primary" @click="doPay">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { arapApi } from '@/api/finance'
import { ElMessage } from 'element-plus'
const query = reactive({ pageNum: 1, pageSize: 20, billType: 'AR' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const payVisible = ref(false)
const form = reactive({ id: null, billType: 'RECEIPT', billDate: new Date().toISOString().substring(0,10), amount: 0, payType: 'BANK', sourceBillId: null, remark: '' })
async function loadData() { loading.value = true; try { data.value = (await arapApi.page(query)).data } finally { loading.value = false } }
function onPay(row) {
  form.id = row.id; form.sourceBillId = row.id
  form.billType = row.billType === 'AR' ? 'RECEIPT' : 'PAYMENT'
  form.amount = row.balance; form.payType = 'BANK'; form.remark = ''
  payVisible.value = true
}
async function doPay() {
  const { id, ...payload } = form
  try {
    await arapApi.cash(payload)
    ElMessage.success('收/付款成功'); payVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '收/付款失败')
  }
}
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
