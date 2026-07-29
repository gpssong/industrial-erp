<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="发票类型">
          <el-select v-model="query.invoiceType" style="width:130px" clearable>
            <el-option label="销项发票" value="AR_SALE" />
            <el-option label="进项发票" value="AP_PURCHASE" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.invoiceStatus" style="width:120px" clearable>
            <el-option label="已开票" value="ISSUED" />
            <el-option label="部分收款" value="PARTIAL" />
            <el-option label="已收款" value="PAID" />
            <el-option label="已作废" value="VOID" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="发票号 / 客户名" clearable style="width:200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button type="success" @click="goCreate">申请开票</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column prop="billNo" label="内部单号" width="160" />
        <el-table-column prop="externalNo" label="外部票号" width="160" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.invoiceType==='AR_SALE'?'danger':'info'">{{ row.invoiceType==='AR_SALE'?'销项':'进项' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="partnerName" label="客户/供应商" />
        <el-table-column prop="partnerTaxNo" label="税号" width="180" />
        <el-table-column prop="billDate" label="发票日期" width="110" />
        <el-table-column prop="totalAmount" label="票面金额" width="120" align="right" />
        <el-table-column prop="collectedAmount" label="已收/付" width="120" align="right" />
        <el-table-column prop="balance" label="未收/付" width="120" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.invoiceStatus==='PAID'?'success':row.invoiceStatus==='PARTIAL'?'warning':row.invoiceStatus==='VOID'?'info':'primary'">
              {{ ({ISSUED:'已开票',PARTIAL:'部分收款',PAID:'已收款',VOID:'已作废',DRAFT:'草稿'})[row.invoiceStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row)">详情</el-button>
            <el-button v-if="row.invoiceStatus!=='VOID' && row.collectedAmount===0" link type="danger" @click="doVoid(row)">作废</el-button>
            <el-button v-if="row.balance>0 && row.invoiceStatus!=='VOID'" link type="success" @click="collect(row)">收款</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="发票详情" width="800px">
      <div v-if="detail">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="内部单号">{{ detail.invoice.billNo }}</el-descriptions-item>
          <el-descriptions-item label="外部票号">{{ detail.invoice.externalNo }}</el-descriptions-item>
          <el-descriptions-item label="发票日期">{{ detail.invoice.billDate }}</el-descriptions-item>
          <el-descriptions-item label="客户/供应商">{{ detail.invoice.partnerName }}</el-descriptions-item>
          <el-descriptions-item label="税号">{{ detail.invoice.partnerTaxNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="抬头">{{ detail.invoice.title || '—' }}</el-descriptions-item>
          <el-descriptions-item label="票面金额">¥{{ detail.invoice.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="税额">¥{{ detail.invoice.taxAmount }}</el-descriptions-item>
          <el-descriptions-item label="已收/付">¥{{ detail.invoice.collectedAmount }}</el-descriptions-item>
          <el-descriptions-item label="未收/付">¥{{ detail.invoice.balance }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ ({ISSUED:'已开票',PARTIAL:'部分',PAID:'已收款',VOID:'已作废'})[detail.invoice.invoiceStatus] }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ detail.invoice.dueDate || '—' }}</el-descriptions-item>
        </el-descriptions>
        <h4 style="margin-top:16px">关联单据</h4>
        <el-table :data="detail.applies" border size="small">
          <el-table-column prop="arap.sourceBillNo" label="来源单号" width="160" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small">{{ row.arap.billType==='AR'?'应收':'应付' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="arap.customerName" label="客户/供应商" />
          <el-table-column prop="arap.bizDate" label="业务日期" width="110" />
          <el-table-column prop="arap.amount" label="原单金额" width="120" align="right" />
          <el-table-column prop="applyAmount" label="本次开票" width="120" align="right" />
          <el-table-column label="开票状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.arap.invoiceStatus==='FULL_INVOICED'?'success':row.arap.invoiceStatus==='PARTIAL_INVOICED'?'warning':'danger'">
                {{ ({UNINVOICED:'未开票',PARTIAL_INVOICED:'部分开票',FULL_INVOICED:'已开票'})[row.arap.invoiceStatus] }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 收款弹窗 -->
    <el-dialog v-model="collectVisible" title="按发票收款" width="500px">
      <el-form :model="collectForm" label-width="100px">
        <el-form-item label="发票号">
          <el-input :model-value="collectForm.billNo" disabled />
        </el-form-item>
        <el-form-item label="客户">
          <el-input :model-value="collectForm.partnerName" disabled />
        </el-form-item>
        <el-form-item label="票面金额">
          <el-input :model-value="collectForm.totalAmount" disabled />
        </el-form-item>
        <el-form-item label="未收金额">
          <el-input :model-value="collectForm.balance" disabled />
        </el-form-item>
        <el-form-item label="本次收款">
          <el-input-number v-model="collectForm.amount" :min="0.01" :max="Number(collectForm.balance)" :precision="2" style="width:100%" />
        </el-form-item>
        <el-form-item label="收/付方式">
          <el-select v-model="collectForm.payType" style="width:100%">
            <el-option label="银行转账" value="BANK" />
            <el-option label="现金" value="CASH" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="collectForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="collectVisible=false">取消</el-button>
        <el-button type="primary" @click="doCollect">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { invoiceApi, arapApi } from '@/api/finance'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const query = reactive({ pageNum: 1, pageSize: 20, invoiceType: '', invoiceStatus: '', keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)

const detail = ref(null)
const detailVisible = ref(false)
const collectVisible = ref(false)
const collectForm = reactive({ invoiceId: null, billNo: '', partnerName: '', totalAmount: 0, balance: 0, amount: 0, payType: 'BANK', remark: '' })

async function loadData() {
  loading.value = true
  try { data.value = (await invoiceApi.page(query)).data }
  finally { loading.value = false }
}

async function viewDetail(row) {
  const r = await invoiceApi.detail(row.id)
  detail.value = r.data
  detailVisible.value = true
}

function goCreate() {
  router.push('/finance/invoice/create')
}

function collect(row) {
  collectForm.invoiceId = row.id
  collectForm.billNo = row.billNo
  collectForm.partnerName = row.partnerName
  collectForm.totalAmount = row.totalAmount
  collectForm.balance = row.balance
  collectForm.amount = Number(row.balance)
  collectForm.payType = 'BANK'
  collectForm.remark = ''
  collectVisible.value = true
}

async function doCollect() {
  try {
    const r = await arapApi.cash({
      billType: 'RECEIPT',
      customerId: undefined,
      amount: collectForm.amount,
      payType: collectForm.payType,
      invoiceId: collectForm.invoiceId,
      remark: collectForm.remark
    })
    ElMessage.success('收款成功')
    collectVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '收款失败')
  }
}

async function doVoid(row) {
  try {
    await ElMessageBox.confirm(`确认作废发票 ${row.billNo} (¥${row.totalAmount})? 作废后无法恢复.`, '提示', { type: 'warning' })
    await invoiceApi.void(row.id)
    ElMessage.success('已作废')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '作废失败')
  }
}

onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>