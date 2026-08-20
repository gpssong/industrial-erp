<template>
  <div>
    <!-- Tab 切换 -->
    <el-tabs v-model="activeTab" @change="onTabChange" style="margin-bottom: 12px">
      <el-tab-pane label="全部往来" name="all" />
      <el-tab-pane label="已开发票" name="issued" />
    </el-tabs>

    <!-- 全部往来：搜索栏 -->
    <div v-if="activeTab === 'all'" class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="类型">
          <el-select v-model="query.billType" style="width:120px">
            <el-option label="应收" value="AR" />
            <el-option label="应付" value="AP" />
          </el-select>
        </el-form-item>
        <el-form-item label="结算状态">
          <el-select v-model="query.billStatus" style="width:120px" clearable>
            <el-option label="未结清" value="UNPAID" />
            <el-option label="部分" value="PARTIAL" />
            <el-option label="已结清" value="PAID" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票状态">
          <el-select v-model="query.invoiceStatus" style="width:140px" clearable>
            <el-option label="未开票" value="UNINVOICED" />
            <el-option label="部分开票" value="PARTIAL_INVOICED" />
            <el-option label="已开票" value="FULL_INVOICED" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="客户/单号" clearable style="width:200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button type="success" @click="goInvoiceCreate">申请开票</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column prop="sourceBillNo" label="来源单号" width="170" />
        <el-table-column prop="bizDate" label="日期" width="110" />
        <el-table-column prop="customerName" label="客户/供应商" />
        <el-table-column prop="amount" label="发生金额" width="120" align="right" />
        <el-table-column prop="invoicedAmount" label="已开票" width="110" align="right" />
        <el-table-column prop="uninvoicedAmount" label="未开票" width="110" align="right" />
        <el-table-column label="开票状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.invoiceStatus==='FULL_INVOICED'?'success':row.invoiceStatus==='PARTIAL_INVOICED'?'warning':'danger'">
              {{ ({UNINVOICED:'未开票',PARTIAL_INVOICED:'部分开票',FULL_INVOICED:'已开票'})[row.invoiceStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 已开发票 tab: 发票号列 -->
        <el-table-column label="关联发票" width="220" v-if="activeTab === 'issued'">
          <template #default="{ row }">
            <div v-if="invoiceMap[row.id]">
              <el-tag
                v-for="inv in invoiceMap[row.id]"
                :key="inv.id"
                size="small"
                type="info"
                class="invoice-tag"
              >{{ inv.billNo }}
                <span v-if="inv.externalNo" class="invoice-external">({{ inv.externalNo }})</span>
              </el-tag>
            </div>
            <span v-else-if="invoiceLoadingIds.has(row.id)" class="loading-text">...</span>
            <span v-else class="empty-text">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="paidAmount" label="已收/付" width="110" align="right" />
        <el-table-column prop="balance" label="未结" width="110" align="right" />
        <el-table-column label="结算状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.billStatus==='PAID'?'success':row.billStatus==='PARTIAL'?'warning':'danger'">
              {{ ({UNPAID:'未结清',PARTIAL:'部分',PAID:'已结清'})[row.billStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.balance>0" link type="primary" @click="onPay(row)">
              {{ row.billType==='AR'?'按单收款':'按单付款' }}
            </el-button>
            <el-button v-if="row.uninvoicedAmount>0" link type="success" @click="onInvoiceOne(row)">申请开票</el-button>
            <el-button v-if="row.balance>0 && row.invoiceStatus==='FULL_INVOICED'" link type="warning" @click="onPayByInvoice(row)">按发票收</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 按单收/付款弹窗 -->
    <el-dialog v-model="payVisible" :title="form.billType==='RECEIPT'?'收款单 (按单)':'付款单 (按单)'" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="收/付日期">
          <el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="收/付金额">
          <el-input-number v-model="form.amount" :precision="2" :step-strictly="false" :min="0" />
        </el-form-item>
        <el-form-item label="收/付方式">
          <el-select v-model="form.payType" style="width:100%">
            <el-option label="现金" value="CASH" />
            <el-option label="银行转账" value="BANK" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payVisible=false">取消</el-button>
        <el-button type="primary" @click="doPay">确定</el-button>
      </template>
    </el-dialog>

    <!-- 按发票收/付款弹窗 -->
    <el-dialog v-model="invoicePayVisible" title="按发票收/付款" width="600px">
      <el-form :model="invoicePayForm" label-width="100px">
        <el-form-item label="关联单据">
          <el-input :model-value="invoicePayForm.sourceBillNo" disabled />
        </el-form-item>
        <el-form-item label="客户/供应商">
          <el-input :model-value="invoicePayForm.partnerName" disabled />
        </el-form-item>
        <el-form-item label="选择发票">
          <el-select v-model="invoicePayForm.invoiceId" filterable placeholder="选择发票" style="width:100%">
            <el-option v-for="inv in invoiceOptions" :key="inv.id"
              :label="`${inv.billNo} (¥${inv.balance} 未收)`" :value="inv.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="本次收款">
          <el-input-number v-model="invoicePayForm.amount" :min="0.01" :precision="2" style="width:100%" />
        </el-form-item>
        <el-form-item label="收/付方式">
          <el-select v-model="invoicePayForm.payType" style="width:100%">
            <el-option label="银行转账" value="BANK" />
            <el-option label="现金" value="CASH" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="invoicePayForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoicePayVisible=false">取消</el-button>
        <el-button type="primary" @click="doPayByInvoice">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { arapApi, invoiceApi } from '@/api/finance'
import { ElMessage } from 'element-plus'

const router = useRouter()
const activeTab = ref('all')

// 全部往来 tab 的查询参数
const query = reactive({ pageNum: 1, pageSize: 20, billType: 'AR', billStatus: '', invoiceStatus: '', keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)

// 已开发票 tab: 缓存每张 AR/AP 单关联的发票列表
const invoiceMap = ref({})    // { [arapId]: FinInvoice[] }
const invoiceLoadingIds = ref(new Set()) // 正在加载的 arapId 集合

// 弹窗状态
const payVisible = ref(false)
const form = reactive({ id: null, billType: 'RECEIPT', billDate: new Date().toISOString().substring(0,10), amount: 0, payType: 'BANK', sourceBillId: null, remark: '' })
const invoicePayVisible = ref(false)
const invoiceOptions = ref([])
const invoicePayForm = reactive({ invoiceId: null, sourceBillId: null, sourceBillNo: '', partnerName: '', amount: 0, payType: 'BANK', remark: '' })

// Tab 切换
function onTabChange(tab) {
  if (tab === 'issued') {
    // 切换到已开发票: 固定查询已开票/部分开票状态
    Object.assign(query, { pageNum: 1, pageSize: 20, billType: 'AR', billStatus: '', invoiceStatus: '', invoiceStatuses: 'FULL_INVOICED,PARTIAL_INVOICED', keyword: '' })
    loadData()
  } else {
    // 切回全部: 恢复默认
    Object.assign(query, { pageNum: 1, pageSize: 20, billType: 'AR', billStatus: '', invoiceStatus: '', invoiceStatuses: '', keyword: '' })
    loadData()
  }
}

async function loadData() {
  loading.value = true
  try {
    if (activeTab.value === 'issued') {
      // 已开发票: 使用 invoiceStatuses 参数查询
      const r = await arapApi.page(query)
      data.value = r.data
      // 分页加载完成后, 异步预取关联发票
      await loadInvoicesForPage()
    } else {
      data.value = (await arapApi.page(query)).data
    }
  } finally {
    loading.value = false
  }
}

// 为当前页所有记录异步加载关联发票 (非阻塞)
async function loadInvoicesForPage() {
  const records = data.value.records || []
  for (const row of records) {
    if (invoiceMap.value[row.id]) continue // 已缓存
    await loadInvoiceForArap(row.id)
  }
}

// 加载单个 AR/AP 单的关联发票
async function loadInvoiceForArap(arapId) {
  invoiceLoadingIds.value.add(arapId)
  try {
    const r = await invoiceApi.byArap(arapId)
    invoiceMap.value[arapId] = (r.data || []).filter(inv => inv.invoiceStatus !== 'VOID')
  } catch {
    invoiceMap.value[arapId] = []
  } finally {
    invoiceLoadingIds.value.delete(arapId)
  }
}

function onPay(row) {
  form.id = row.id; form.sourceBillId = row.id
  form.billType = row.billType === 'AR' ? 'RECEIPT' : 'PAYMENT'
  form.amount = Number(row.balance); form.payType = 'BANK'; form.remark = ''
  payVisible.value = true
}

async function doPay() {
  try {
    await arapApi.cash({ ...form })
    ElMessage.success('收/付款成功')
    payVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '收/付款失败')
  }
}

function onInvoiceOne(row) {
  router.push({ path: '/finance/invoice/create', query: { arapId: row.id } })
}

function goInvoiceCreate() { router.push('/finance/invoice/create') }

async function onPayByInvoice(row) {
  try {
    const r = await invoiceApi.byArap(row.id)
    invoiceOptions.value = (r.data || []).filter(inv => Number(inv.balance) > 0 && inv.invoiceStatus !== 'VOID')
    if (invoiceOptions.value.length === 0) {
      ElMessage.warning('该单据没有可用的未收款发票, 请先开票')
      return
    }
    invoicePayForm.invoiceId = null
    invoicePayForm.sourceBillId = row.id
    invoicePayForm.sourceBillNo = row.sourceBillNo
    invoicePayForm.partnerName = row.customerName || row.supplierName
    invoicePayForm.amount = 0
    invoicePayForm.payType = 'BANK'
    invoicePayForm.remark = ''
    invoicePayVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载发票失败')
  }
}

async function doPayByInvoice() {
  if (!invoicePayForm.invoiceId) { ElMessage.warning('请选择发票'); return }
  try {
    await arapApi.cash({
      billType: 'RECEIPT',
      amount: invoicePayForm.amount,
      payType: invoicePayForm.payType,
      invoiceId: invoicePayForm.invoiceId,
      remark: invoicePayForm.remark
    })
    ElMessage.success('按发票收款成功')
    invoicePayVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '收款失败')
  }
}

onMounted(loadData)
</script>
<style scoped>
.pager { margin-top: 12px; text-align: right; }
.invoice-tag { margin-right: 4px; margin-bottom: 2px; }
.invoice-external { color: #909399; font-size: 12px; }
.loading-text { color: #909399; font-size: 12px; }
.empty-text { color: #c0c4cc; font-size: 12px; }
</style>
