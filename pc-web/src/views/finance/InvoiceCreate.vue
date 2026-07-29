<template>
  <div>
    <div class="page-card">
      <el-form :model="form" inline>
        <el-form-item label="客户/供应商">
          <el-select v-model="form.partnerId" filterable placeholder="选择客户" style="width:300px" @change="onPartnerChange">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadUninvoiced" :disabled="!form.partnerId">查询未开票</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <el-table :data="uninvoicedList" border stripe v-loading="loading" @selection-change="onSelectChange">
        <el-table-column type="selection" width="55" :selectable="r => Number(r.uninvoicedAmount) > 0" />
        <el-table-column prop="sourceBillNo" label="来源单号" width="180" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.billType==='AR'?'应收':'应付' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bizDate" label="业务日期" width="110" />
        <el-table-column prop="amount" label="原单金额" width="120" align="right" />
        <el-table-column prop="uninvoicedAmount" label="未开票金额" width="120" align="right" />
        <el-table-column label="开票金额" width="160">
          <template #default="{ row }">
            <el-input-number
              v-model="applyAmountMap[row.id]"
              :min="0.01"
              :max="Number(row.uninvoicedAmount)"
              :precision="2"
              size="small"
              style="width:100%"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card" v-if="selected.length > 0">
      <el-form :model="form" label-width="100px">
        <h3>发票信息</h3>
        <el-form-item label="发票号(外部)">
          <el-input v-model="form.externalNo" placeholder="增值税发票号, 如 20260725001" />
        </el-form-item>
        <el-form-item label="发票日期">
          <el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="发票抬头">
          <el-input v-model="form.title" placeholder="选填" />
        </el-form-item>
        <el-form-item label="税额">
          <el-input-number v-model="form.taxAmount" :precision="2" :min="0" style="width:100%" />
        </el-form-item>
        <el-form-item label="到期日">
          <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label=" ">
          <el-tag>已选 {{ selected.length }} 单, 合计 ¥{{ totalSelected }}</el-tag>
        </el-form-item>
      </el-form>
      <div style="text-align:right">
        <el-button @click="onCancel">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">提交开票</el-button>
      </div>
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { invoiceApi } from '@/api/finance'
import { ElMessage } from 'element-plus'

const router = useRouter()
const customers = ref([])
const uninvoicedList = ref([])
const selected = ref([])
const applyAmountMap = reactive({})
const loading = ref(false)
const submitting = ref(false)

const form = reactive({
  partnerId: null,
  partnerType: 'CUSTOMER',
  invoiceType: 'AR_SALE',
  externalNo: '',
  billDate: new Date().toISOString().substring(0, 10),
  taxAmount: 0,
  dueDate: '',
  title: '',
  remark: ''
})

const totalSelected = computed(() => {
  return selected.value.reduce((sum, r) => sum + Number(applyAmountMap[r.id] || 0), 0).toFixed(2)
})

async function loadCustomers() {
  const r = await request.get('/base/customer/list')
  customers.value = r.data || []
}

async function loadUninvoiced() {
  if (!form.partnerId) return
  loading.value = true
  try {
    const r = await invoiceApi.uninvoiced({ customerId: form.partnerId })
    uninvoicedList.value = r.data || []
    // 默认每单开票金额 = 未开票金额
    uninvoicedList.value.forEach(r => { applyAmountMap[r.id] = Number(r.uninvoicedAmount) })
  } finally { loading.value = false }
}

function onPartnerChange() { /* keep */ }
function onSelectChange(rows) { selected.value = rows }

function onCancel() { router.back() }

async function onSubmit() {
  if (selected.value.length === 0) { ElMessage.warning('请勾选要开票的单据'); return }
  if (!form.externalNo) { ElMessage.warning('请录入外部发票号'); return }
  const items = selected.value.map(r => ({
    arapId: r.id,
    applyAmount: Number(applyAmountMap[r.id] || 0)
  }))
  // 校验开票金额
  for (const item of items) {
    if (item.applyAmount <= 0) {
      ElMessage.warning(`单据 ${selected.value.find(r => r.id === item.arapId).sourceBillNo} 开票金额必须 > 0`)
      return
    }
  }
  submitting.value = true
  try {
    await invoiceApi.issue({
      externalNo: form.externalNo,
      invoiceType: form.invoiceType,
      partnerType: form.partnerType,
      partnerId: form.partnerId,
      billDate: form.billDate,
      taxAmount: form.taxAmount,
      dueDate: form.dueDate || null,
      title: form.title,
      remark: form.remark,
      items
    })
    ElMessage.success('开票成功')
    router.push('/finance/invoice')
  } catch (e) {
    ElMessage.error(e.message || '开票失败')
  } finally { submitting.value = false }
}

onMounted(loadCustomers)
</script>
<style scoped>.page-card { margin-bottom: 12px; }</style>