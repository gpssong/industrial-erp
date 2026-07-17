<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="客户">
          <el-select v-model="query.customerId" clearable filterable style="width:160px">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品名称"><el-input v-model="query.productName" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable style="width:120px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
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
        <el-table-column prop="warehouseName" label="仓库" min-width="120" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column prop="costAmount" label="成本" width="100" align="right" />
        <el-table-column prop="profitAmount" label="毛利" width="100" align="right" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.billStatus==='CHECKED'?'success':'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT'" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus==='DRAFT'" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus==='DRAFT'" link type="success" @click="onCheck(row)">审核</el-button>
            <el-button v-if="['DRAFT','CHECKED'].includes(row.billStatus)" link type="warning" @click="onPrint(row)">打印</el-button>
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
              <el-select v-model="form.warehouseId" style="width:100%" @change="onWarehouseChange">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="收货地址"><el-input v-model="form.address" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="收货电话"><el-input v-model="form.phone" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="整单折扣"><el-input-number v-model="form.discountAmount" :step-strictly="false" :formatter="stripZeroFormat" :parser="stripZeroParse" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="抹零"><el-input-number v-model="form.tailAmount" :step-strictly="false" :formatter="stripZeroFormat" :parser="stripZeroParse" /></el-form-item></el-col>
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
            <el-table-column label="单位" width="100">
              <template #default="{ row }">
                <el-select v-model="row.unitId" size="small" style="width:100%" @change="v=>onUnitChange(row, v)">
                  <el-option v-for="u in (row._units || [])" :key="u.unitId" :label="u.unitName" :value="u.unitId" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right"><template #default="{ row }"><span>{{ stripTrailingZero4(row.qty * row.price) }}</span></template></el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="税率" width="80">
              <template #default="{ row }"><el-input-number v-model="row.taxRate" :precision="2" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template>
            </el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="价税合计" width="120" align="right">
              <template #default="{ row }"><span>{{ stripTrailingZero4((row.qty||0)*(row.price||0)*(1+((row.taxRate||0))/100)) }}</span></template>
            </el-table-column>
            <el-table-column label="批次" width="160">
              <template #default="{ row }">
                <el-select v-model="row.batchNo" size="small" filterable allow-create
                  default-first-option clearable placeholder="选批次"
                  style="width:100%">
                  <el-option v-for="b in (row._batchOptions || [])" :key="(b.batchNo || '<NULL>')"
                    :label="`${b.batchNo || '<无批次>'} (${b.qty})`" :value="b.batchNo || ''" />
                </el-select>
              </template>
            </el-table-column>
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
              <p>合计数量: <b>{{ stripTrailingZero4(totalQty) }}</b></p>
              <p v-if="taxSeparation !== 'true'">合计金额: <b>¥ {{ stripTrailingZero2(totalAmount) }}</b></p>
              <template v-if="taxSeparation === 'true'">
                <p>不含税金额: <b>¥ {{ stripTrailingZero2(totalAmount) }}</b></p>
                <p>税额: <b>¥ {{ stripTrailingZero2(totalAmount * 0.13) }}</b></p>
                <p class="total">价税合计: <b>¥ {{ stripTrailingZero2(totalAmount * 1.13) }}</b></p>
              </template>
            </div>
          </el-col>
        </el-row>

        <!-- 该客户历史销售产品 (v1.1.7+ 选定客户后展示, 点击行复制到上方明细) -->
        <el-form-item v-if="form.customerId" label="历史销售">
          <div class="customer-history" style="width:100%">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
              <span style="color:#666;font-size:12px">最近 50 条出库记录, 单击行把该明细复制到上方</span>
              <el-button size="small" link type="primary" @click="loadCustomerHistory" :loading="historyLoading">刷新</el-button>
            </div>
            <el-table :data="customerHistory" size="small" border max-height="220"
              @row-click="onHistoryRowClick" highlight-current-row stripe>
              <el-table-column prop="billDate" label="日期" width="100" />
              <el-table-column prop="productCode" label="产品编码" width="140" />
              <el-table-column prop="productName" label="产品名称" />
              <el-table-column prop="spec" label="规格" width="120" />
              <el-table-column prop="unitName" label="单位" width="60" />
              <el-table-column prop="qty" label="数量" width="80" align="right">
                <template #default="{ row }">{{ stripTrailingZero4(row.qty) }}</template>
              </el-table-column>
              <el-table-column prop="price" label="单价(含税)" width="100" align="right">
                <template #default="{ row }">{{ stripTrailingZero2(row.price) }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" show-overflow-tooltip />
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click.stop="onHistoryRowClick(row)">加入明细</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="customerHistory.length === 0 && !historyLoading" style="color:#999;font-size:12px;padding:10px;text-align:center">
              该客户暂无历史销售记录
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存为草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { salDeliveryApi } from '@/api/sales'
import { customerApi, warehouseApi, productApi, unitApi } from '@/api/base'
import { stockApi } from '@/api/inventory'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { usePrint, BIZ_TYPES } from '@/composables/usePrint'
import { ElMessage, ElMessageBox } from 'element-plus'

// el-input-number 数字自动去尾 0 (v1.1.6 引入; v1.1.7 补到销售出库):
// EP 的 precision=N 会强制显示 N 位小数, 用 formatter/parser 接管显示.
// 740 → "740", 31.4 → "31.4", 17 → "17"
const stripZeroFormat = (v) => {
  if (v == null || v === '') return ''
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return String(n)
}
const stripZeroParse = (v) => {
  if (v == null || v === '') return null
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : null
}
// 计算列去尾 0: 量纲 4 位 (数量), 金额 2 位 (元)
const stripTrailingZero4 = (v) => {
  if (v == null || v === '' || !isFinite(Number(v))) return ''
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : n.toString()
}
const stripTrailingZero2 = (v) => {
  if (v == null || v === '' || !isFinite(Number(v))) return ''
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/\.?0+$/, '')
}

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '', customerId: null, billStatus: '', productName: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()
const customers = ref([])
const warehouses = ref([])
const units = ref([])
const productList = ref([])
// v1.1.7+: 选定客户后展示历史销售产品列表
const customerHistory = ref([])
const historyLoading = ref(false)

const form = reactive({
  id: null, billNo: '', billDate: new Date().toISOString().substring(0, 10),
  customerId: null, customerName: '', warehouseId: null, address: '', phone: '',
  discountAmount: 0, tailAmount: 0, remark: '',
  details: []
})

const totalQty = computed(() => form.details.reduce((s, d) => s + (+d.qty || 0), 0))
const totalAmount = computed(() => form.details.reduce((s, d) => s + ((+d.qty || 0) * (+d.price || 0)), 0))
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

async function loadCustomers() {
  if (customers.value.length === 0) {
    customers.value = (await customerApi.page({ pageNum: 1, pageSize: 500 })).data.records
  }
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.billNo) delete params.billNo
    if (!params.productName) delete params.productName
    if (!params.billStatus) delete params.billStatus
    const r = await salDeliveryApi.page(params); data.value = r.data
  } finally { loading.value = false }
}

function onReset() {
  query.billNo = ''
  query.customerId = null
  query.productName = ''
  query.billStatus = ''
  query.pageNum = 1
  loadData()
}

async function onAdd() {
  loadTaxSeparation()
  // 完全重置 form (避免上次操作的残留, 比如编辑后再点新增 customerId/warehouseId 还残留)
  form.id = null
  form.billNo = ''
  form.billDate = new Date().toISOString().substring(0, 10)
  form.customerId = null
  form.customerName = ''
  form.warehouseId = null
  form.address = ''
  form.phone = ''
  form.discountAmount = 0
  form.tailAmount = 0
  customerHistory.value = []   // v1.1.7+ 重置历史销售列表
  form.remark = ''
  form.details = []
  await loadCustomers()
  if (!warehouses.value.length) warehouses.value = (await warehouseApi.list()).data
  if (!units.value.length) units.value = (await unitApi.list()).data
  productList.value = []
  dialogVisible.value = true
}

function addLine() {
  if (taxSeparation.value === 'true') {
    form.details.push({ productId: null, qty: null, price: 0, taxRate: 13, lineNo: form.details.length + 1, _units: [] })
  } else {
    form.details.push({ productId: null, qty: null, price: 0, lineNo: form.details.length + 1, _units: [] })
  }
}

async function searchProduct(kw) {
  const r = await productApi.page({ pageNum: 1, pageSize: 20, keyword: kw })
  productList.value = r.data.records
}

async function onProductChange(row, v) {
  const detail = (await productApi.detail(v)).data
  const p = detail.product
  if (!p) return
  row.productId = p.id; row.productCode = p.productCode; row.productName = p.productName
  row.spec = p.spec
  // 加载该商品的所有单位, 默认选主单位
  row._units = (detail.units || []).map(u => ({ unitId: u.unitId, unitName: u.unitName, conversionRate: u.conversionRate, isMain: u.isMain }))
  const mainUnit = row._units.find(u => u.isMain) || row._units[0]
  row.unitId = mainUnit ? mainUnit.unitId : p.mainUnitId
  row.unitName = mainUnit ? mainUnit.unitName : '主单位'
  // 加载该商品在该仓库下所有可用批次 (供销售出库选 batchNo, 避免审核时"库存不存在")
  await loadBatchOptionsForRow(row)
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

/**
 * 拉取当前 row.productId + form.warehouseId 下的所有可用批次 (qty > 0).
 * v1.1.7+: 仅展示, 仍可手动输入 (allow-create). 默认优先 qty 最大.
 */
async function loadBatchOptionsForRow(row) {
  row._batchOptions = []
  if (!form.warehouseId || !row.productId) return
  try {
    const r = await stockApi.batches(form.warehouseId, row.productId)
    // 仅展示 qty > 0 的; stockApi.batches 已经按 qty DESC 排好
    row._batchOptions = (r.data || []).filter(b => Number(b.qty) > 0)
    // 若还没有 batchNo 且候选只有一条, 自动填上
    if (!row.batchNo && row._batchOptions.length === 1) {
      row.batchNo = row._batchOptions[0].batchNo || ''
    }
  } catch (e) {
    /* 后端接口失败也不阻塞开单 */
  }
}

function onUnitChange(row, unitId) {
  const u = (row._units || []).find(x => x.unitId === unitId)
  if (u) row.unitName = u.unitName
}

/**
 * 仓库切换: 已选商品的明细行全部刷新批次候选, 并清空 batchNo 等待重选.
 */
function onWarehouseChange() {
  form.details.forEach(r => {
    if (r.productId) {
      r.batchNo = ''
      loadBatchOptionsForRow(r)
    }
  })
}

function onCustomerChange() {
  // 1. 已选明细行的商品重新拉规格/单位/价格
  form.details.forEach(d => d.productId && onProductChange(d, d.productId))
  // 2. 加载该客户历史销售产品列表
  loadCustomerHistory()
}

/**
 * 加载指定客户的最近 50 条历史销售出库明细, 用于弹窗底部"历史销售"列表.
 * v1.1.7+ 新增.
 */
async function loadCustomerHistory() {
  if (!form.customerId) { customerHistory.value = []; return }
  historyLoading.value = true
  try {
    const r = await salDeliveryApi.getCustomerHistoryProducts(form.customerId)
    customerHistory.value = r.data || []
  } catch (e) {
    customerHistory.value = []
    ElMessage.error('加载客户历史销售失败: ' + e.message)
  } finally { historyLoading.value = false }
}

/**
 * 点击历史销售行 → 把该明细加到 form.details.
 * 若同 productId 已存在, 累加数量; 否则追加新行 (复用 productList 找 label, 拉批次候选).
 */
async function onHistoryRowClick(row) {
  // 1. 关键属性复制
  const newLine = {
    productId: row.productId, productCode: row.productCode, productName: row.productName,
    spec: row.spec, unitId: row.unitId, unitName: row.unitName,
    qty: row.qty != null ? Number(row.qty) : 0,
    price: row.price != null ? Number(row.price) : 0,
    taxRate: row.taxRate != null ? Number(row.taxRate) : 13,
    batchNo: row.batchNo, locationName: '', remark: row.remark
  }
  // 2. 合并到 form.details (按 productId+batchNo 累加数量)
  const exist = form.details.find(d => d.productId === newLine.productId && (d.batchNo || '') === (newLine.batchNo || ''))
  if (exist) {
    exist.qty = (+exist.qty || 0) + (+newLine.qty || 0)
    ElMessage.success(`已合并到明细行 (累计数量 ${exist.qty})`)
    return
  }
  form.details.push(newLine)
  // 3. 异步: 加载该商品所有单位 + 该仓库批次候选, 让用户能用下拉
  if (!productList.value.find(p => p.id === newLine.productId)) {
    try {
      const r = await productApi.detail(newLine.productId)
      const p = r.data?.product || r.data
      if (p) {
        productList.value.push({ id: p.id, productCode: p.productCode, productName: p.productName })
      }
    } catch (e) { /* ignore */ }
  }
  // 等下个 tick 让新行挂上, 再加载批次候选
  await Promise.resolve()
  await loadBatchOptionsForRow(newLine)
  ElMessage.success('已加入明细行')
}

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
    if (form.id) {
      await salDeliveryApi.update(payload)
      ElMessage.success('修改成功')
    } else {
      await salDeliveryApi.add(payload)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

async function onEdit(row) {
  // 编辑: 复用新增对话框, 先清空再赋新值 (避免 Object.assign 引用问题)
  loadTaxSeparation()
  // 先把 form 字段重置成新增状态, 再覆盖
  form.id = null; form.billNo = ''; form.billDate = new Date().toISOString().substring(0, 10)
  form.customerId = null; form.customerName = ''; form.warehouseId = null
  form.address = ''; form.phone = ''; form.discountAmount = 0; form.tailAmount = 0; form.remark = ''
  form.details = []

  await loadCustomers()
  if (!warehouses.value.length) warehouses.value = (await warehouseApi.list()).data

  const detail = await salDeliveryApi.detail(row.id)
  const d = detail.data
  // 字段逐一赋值, 保证响应式
  // 注意: 不用 Number() 转 ID! JS 安全整数只到 2^53=9007199254740992 (16位),
  // 我们的 ID 是 19 位 (snowflake), Number() 会丢精度. 后端 Jackson 已返回字符串, 直接用即可
  form.id = d.id
  form.billNo = d.billNo
  form.billDate = d.billDate
  form.customerId = d.customerId
  form.customerName = d.customerName
  form.warehouseId = d.warehouseId
  form.address = d.address || ''
  form.phone = d.phone || ''
  form.discountAmount = d.discountAmount || 0
  form.tailAmount = d.tailAmount || 0
  form.remark = d.remark || ''
  form.details = (d.details || []).map(x => ({
    id: x.id, deliveryId: x.deliveryId, lineNo: x.lineNo,
    productId: x.productId,
    productCode: x.productCode, productName: x.productName,
    spec: x.spec, unitId: x.unitId, unitName: x.unitName,
    qty: x.qty, price: x.price, taxRate: x.taxRate,
    amount: x.amount, taxAmount: x.taxAmount, amountTax: x.amountTax,
    batchNo: x.batchNo, locationName: x.locationName,
    remark: x.remark, _units: []
  }))
  // 预加载每个商品到 productList (避免 el-select 显示空 label)
  productList.value = []
  for (const det of form.details) {
    if (det.productId) {
      try {
        const pd = (await productApi.detail(det.productId)).data
        if (pd.product) {
          if (!productList.value.find(p => p.id === pd.product.id)) {
            productList.value.push(pd.product)
          }
          // 加载单位
          det._units = (pd.units || []).map(u => ({ unitId: u.unitId, unitName: u.unitName, conversionRate: u.conversionRate, isMain: u.isMain }))
        }
      } catch (e) { /* ignore */ }
    }
  }
  dialogVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除出库单 ${row.billNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  await salDeliveryApi.delete(row.id)
  ElMessage.success('删除成功'); loadData()
}

async function onCheck(row) {
  await salDeliveryApi.check(row.id)
  ElMessage.success('审核成功, 已扣减库存 / 生成应收')
  loadData()
}

// 打印 (myprint-design 浏览器打印)
const { doPrint } = usePrint()
const SAL_DELIVERY_HEADER_MAP = {
  billNo: 'billNo',
  billDate: 'billDate',
  customerName: 'customerName',
  warehouseName: 'warehouseName',
  address: 'address',
  phone: 'phone',
  totalQty: 'totalQty',
  totalAmount: 'totalAmount',
  remark: 'remark'
}
const SAL_DELIVERY_DETAIL_MAP = {
  lineNo: 'lineNo',
  productCode: 'productCode',
  productName: 'productName',
  model: 'model',
  spec: 'spec',
  unitName: 'unitName',
  qty: 'qty',
  price: 'price',
  amount: 'amount',
  taxRate: 'taxRate',
  batchNo: 'batchNo',
  locationName: 'locationName'
}
async function onPrint(row) {
  try {
    const r = await salDeliveryApi.detail(row.id)
    await doPrint({
      bizType: BIZ_TYPES.SAL_DELIVERY,
      bill: r.data || {},
      fieldMap: SAL_DELIVERY_HEADER_MAP,
      detailsKey: 'details',
      detailFieldMap: SAL_DELIVERY_DETAIL_MAP
    })
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  }
}

async function onView(row) {
  loadTaxSeparation()
  const r = await salDeliveryApi.detail(row.id)
  Object.assign(form, r.data)
  form.billDate = form.billDate
  // 为每个明细行加载商品单位列表
  await Promise.all((form.details || []).map(async d => {
    if (d.productId) {
      const detail = (await productApi.detail(d.productId)).data
      d._units = (detail.units || []).map(u => ({ unitId: u.unitId, unitName: u.unitName, conversionRate: u.conversionRate, isMain: u.isMain }))
      const u = d._units.find(x => x.unitId === d.unitId)
      if (u) d.unitName = u.unitName
    }
  }))
  dialogVisible.value = true
}

function onScan() {
  ElMessage.info('请配置扫码枪或App扫码 (H5/微信小程序可用 getCameraProfile)')
}

onMounted(async () => { await loadCustomers(); loadData() })
</script>

<style scoped>
.pager { margin-top: 12px; text-align: right; }
.summary { padding: 8px 16px; background: #f8f8f8; border-radius: 4px;
  p { margin: 4px 0; font-size: 13px; } .total { font-size: 16px; color: #c0392b; } }
</style>
