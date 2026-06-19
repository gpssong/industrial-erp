<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable /></el-form-item>
        <el-form-item label="客户">
          <el-select v-model="query.customerId" clearable filterable style="width:200px">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable style="width:140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增出库单</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="110" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="firstProductName" label="商品名称" show-overflow-tooltip />
        <el-table-column prop="warehouseId" label="仓库ID" width="80" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column prop="costAmount" label="成本" width="100" align="right" />
        <el-table-column prop="profitAmount" label="毛利" width="100" align="right" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.billStatus==='CHECKED'?'success':'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT'" link type="primary" @click="onCheck(row)">审核</el-button>
            <el-button link type="primary" @click="openPrint(row)">打印</el-button>
            <el-button link type="primary" @click="onView(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑出库单' : '新增销售出库单'" width="1100px" destroy-on-close>
      <el-form :model="form" label-width="100px" ref="formRef">
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="单据日期"><el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="客户">
              <el-select v-model="form.customerId" filterable style="width:100%" @change="onCustomerChange">
                <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="仓库">
              <el-select v-model="form.warehouseId" style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="收货地址"><el-input v-model="form.address" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="收货电话"><el-input v-model="form.phone" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="整单折扣"><el-input-number v-model="form.discountAmount" :precision="2" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="抹零"><el-input-number v-model="form.tailAmount" :precision="2" /></el-form-item></el-col>
        </el-row>

        <el-form-item label="商品明细">
          <el-button @click="addLine" type="primary" plain size="small"><el-icon><Plus /></el-icon>添加行</el-button>
          <el-button @click="onScan" plain size="small"><el-icon><Camera /></el-icon>扫码</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px" max-height="380">
            <el-table-column label="序号" type="index" width="50" />
            <el-table-column label="商品" width="280">
              <template #default="{ row }">
                <el-select v-model="row.productId" filterable remote :remote-method="searchProduct" style="width:100%" @change="v=>onProductChange(row, v)">
                  <el-option v-for="p in productList" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="规格" width="120"><template #default="{ row }"><span>{{ row.spec }}</span></template></el-table-column>
            <el-table-column label="单位" width="70"><template #default="{ row }"><span>{{ row.unitName }}</span></template></el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :precision="4" size="small" /></template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="4" size="small" /></template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right"><template #default="{ row }"><span>{{ (row.qty*row.price).toFixed(4) }}</span></template></el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="税率" width="80">
              <template #default="{ row }"><el-input-number v-model="row.taxRate" :precision="2" size="small" /></template>
            </el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="价税合计" width="120" align="right">
              <template #default="{ row }"><span>{{ ((row.qty*row.price)*(1+(row.taxRate||0)/100)).toFixed(4) }}</span></template>
            </el-table-column>
            <el-table-column label="批次" width="100"><template #default="{ row }"><el-input v-model="row.batchNo" size="small" /></template></el-table-column>
            <el-table-column label="库位" width="100">
              <template #default="{ row }"><el-input v-model="row.locationName" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index,1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-row>
          <el-col :span="12">
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <div class="summary">
              <p>合计数量: <b>{{ totalQty.toFixed(4) }}</b></p>
              <p v-if="taxSeparation !== 'true'">合计金额: <b>¥ {{ totalAmount.toFixed(2) }}</b></p>
              <template v-if="taxSeparation === 'true'">
                <p>不含税金额: <b>¥ {{ totalAmount.toFixed(2) }}</b></p>
                <p>税额: <b>¥ {{ (totalAmount * 0.13).toFixed(2) }}</b></p>
                <p class="total">价税合计: <b>¥ {{ (totalAmount * 1.13).toFixed(2) }}</b></p>
              </template>
            </div>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存为草稿</el-button>
      </template>
    </el-dialog>

    <!-- 打印预览 -->
    <el-dialog v-model="printVisible" title="打印预览" width="400px">
      <p style="text-align:center">正在加载打印预览...</p>
      <template #footer>
        <el-button @click="printVisible=false">关闭</el-button>
        <el-button type="primary" @click="doPrint">打 印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { salDeliveryApi } from '@/api/sales'
import { customerApi, warehouseApi, productApi, unitApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { ElMessage } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '', customerId: null, billStatus: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const printVisible = ref(false)
const printUrl = ref('')
function doPrint() {
  printVisible.value = false
  window.open(printUrl.value, '_blank')
}
const submitting = ref(false)
const formRef = ref()
const customers = ref([])
const warehouses = ref([])
const units = ref([])
const productList = ref([])

const form = reactive({
  id: null, billNo: '', billDate: new Date().toISOString().substring(0, 10),
  customerId: null, customerName: '', warehouseId: null, address: '', phone: '',
  discountAmount: 0, tailAmount: 0, remark: '',
  details: []
})

const totalQty = computed(() => form.details.reduce((s, d) => s + (+d.qty || 0), 0))
const totalAmount = computed(() => form.details.reduce((s, d) => s + ((+d.qty || 0) * (+d.price || 0)), 0))
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

async function loadData() {
  loading.value = true
  try { const r = await salDeliveryApi.page(query); data.value = r.data }
  finally { loading.value = false }
}

async function onAdd() {
  loadTaxSeparation()
  form.id = null; form.billNo = ''; form.details = []
  customers.value = (await customerApi.page({ pageNum: 1, pageSize: 500 })).data.records
  warehouses.value = (await warehouseApi.list()).data
  units.value = (await unitApi.list()).data
  dialogVisible.value = true
}

function addLine() {
  if (taxSeparation.value === 'true') {
    form.details.push({ productId: null, qty: null, price: 0, taxRate: 13, lineNo: form.details.length + 1 })
  } else {
    form.details.push({ productId: null, qty: null, price: 0, lineNo: form.details.length + 1 })
  }
}

async function searchProduct(kw) {
  const r = await productApi.page({ pageNum: 1, pageSize: 20, keyword: kw })
  productList.value = r.data.records
}

async function onProductChange(row, v) {
  const p = productList.value.find(x => x.id === v) || (await productApi.detail(v)).data.product
  if (!p) return
  row.productId = p.id; row.productCode = p.productCode; row.productName = p.productName
  row.spec = p.spec
  row.unitId = p.mainUnitId
  row.unitName = units.value.find(u => u.id == p.mainUnitId)?.unitName || '主单位'
  // 优先取该客户对此商品的上次订单单价
  if (form.customerId && row.productId) {
    try {
      const res = await salDeliveryApi.getLastPrice(form.customerId, row.productId)
      if (res.data > 0) { row.price = res.data; return }
    } catch (e) { /* ignore */ }
  }
  // 根据客户价格等级自动选价
  if (form.customerId) {
    const c = customers.value.find(x => x.id === form.customerId)
    if (c) {
      if (c.priceLevel === 'VIP') row.price = +p.vipPrice
      else if (c.priceLevel === 'WHOLESALE') row.price = +p.wholesalePrice
      else row.price = +p.salesPrice
    }
  } else {
    row.price = +p.salesPrice
  }
}

function onCustomerChange() { form.details.forEach(d => d.productId && onProductChange(d, d.productId)) }

async function onSave() {
  if (!form.customerId) return ElMessage.warning('请选择客户')
  if (!form.warehouseId) return ElMessage.warning('请选择仓库')
  if (!form.details.length) return ElMessage.warning('请添加商品明细')
  submitting.value = true
  try {
    const payload = { ...form }
    if (taxSeparation.value === 'true') {
      let totalAmount = 0, taxAmount = 0
      payload.details.forEach(d => {
        const amt = (+d.qty || 0) * (+d.price || 0)
        totalAmount += amt
        taxAmount += amt * ((d.taxRate || 13) / 100)
      })
      payload.totalAmount = totalAmount
      payload.taxAmount = taxAmount
      payload.totalAmountTax = totalAmount + taxAmount
    }
    await salDeliveryApi.add(payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

async function onCheck(row) {
  await salDeliveryApi.check(row.id)
  ElMessage.success('审核成功, 已扣减库存 / 生成应收')
  loadData()
}

async function onView(row) {
  loadTaxSeparation()
  const r = await salDeliveryApi.detail(row.id)
  Object.assign(form, r.data)
  form.billDate = form.billDate
  dialogVisible.value = true
}

function openPrint(row) {
  printUrl.value = `http://localhost:8080/api/print/sales-delivery/${row.id}.html?token=${localStorage.getItem('erp_token')}`
  printVisible.value = true
}

function onScan() {
  ElMessage.info('请配置扫码枪或App扫码 (H5/微信小程序可用 getCameraProfile)')
}

onMounted(loadData)
</script>

<style scoped>
.pager { margin-top: 12px; text-align: right; }
.summary { padding: 8px 16px; background: #f8f8f8; border-radius: 4px;
  p { margin: 4px 0; font-size: 13px; } .total { font-size: 16px; color: #c0392b; } }
</style>
