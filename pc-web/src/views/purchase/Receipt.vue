<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="query.supplierId" filterable clearable style="width:160px" placeholder="全部">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品名称"><el-input v-model="query.productName" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增入库</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="supplierName" label="供应商" />
        <el-table-column prop="firstProductName" label="商品名称" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='CHECKED'?'success':'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT'" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus==='DRAFT'" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus==='DRAFT'" link type="success" @click="onCheck(row)">审核</el-button>
            <el-dropdown v-if="['DRAFT','CHECKED'].includes(row.billStatus)" trigger="click" @command="(cmd) => onPrintCommand(cmd, row)">
              <el-button link type="warning">
                打印<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="browser">浏览器打印</el-dropdown-item>
                  <el-dropdown-item command="feie-preview">飞鹅打印预览</el-dropdown-item>
                  <el-dropdown-item command="feie-print" divided>飞鹅云打印</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑采购入库单' : '新增采购入库单'" width="1100px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="入库日期"><el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="供应商"><el-select v-model="form.supplierId" filterable style="width:100%" @change="onSupplierChange"><el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库"><el-select v-model="form.warehouseId" style="width:100%"><el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" /></el-select></el-form-item></el-col>
        </el-row>
        <el-form-item label="入库明细">
          <el-button @click="addLine" type="primary" plain size="small">添加行</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="商品" width="280">
              <template #default="{ row }">
                <el-select v-model="row.productId" filterable :loading="productLoading" style="width:100%" @change="v=>onProduct(row, v)">
                  <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="规格" width="120"><template #default="{ row }"><span>{{ row.spec }}</span></template></el-table-column>
            <el-table-column label="数量" width="120"><template #default="{ row }"><el-input-number v-model="row.qty" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template></el-table-column>
            <el-table-column label="单价(含税)" width="120"><template #default="{ row }"><el-input-number v-model="row.price" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template></el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="税率" width="80"><template #default="{ row }"><el-input-number v-model="row.taxRate" :step-strictly="false" size="small" :formatter="stripZeroFormat" :parser="stripZeroParse" /></template></el-table-column>
            <el-table-column label="金额" width="120" align="right"><template #default="{ row }"><span>{{ stripTrailingZero2((row.qty||0)*(row.price||0)) }}</span></template></el-table-column>
            <el-table-column label="批次"><template #default="{ row }"><el-input v-model="row.batchNo" size="small" /></template></el-table-column>
            <el-table-column label="库位"><template #default="{ row }"><el-input v-model="row.locationName" size="small" /></template></el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index,1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>

        <!-- 该供应商历史采购产品 (v1.1.7+ 选定供应商后展示, 点击行复制到上方明细) -->
        <el-form-item v-if="form.supplierId" label="历史采购">
          <div style="width:100%">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
              <span style="color:#666;font-size:12px">最近 50 条入库记录, 单击行把该明细复制到上方</span>
              <el-button size="small" link type="primary" @click="loadSupplierHistory" :loading="historyLoading">刷新</el-button>
            </div>
            <el-table :data="supplierHistory" size="small" border max-height="220"
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
            <div v-if="supplierHistory.length === 0 && !historyLoading" style="color:#999;font-size:12px;padding:10px;text-align:center">
              该供应商暂无历史采购记录
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
    <!-- 飞鹅云打印预览弹窗 -->
    <el-dialog v-model="feiePreviewVisible" title="飞鹅云打印预览" width="560px" destroy-on-close>
      <div v-loading="feiePreviewLoading" style="min-height:200px;">
        <pre style="white-space:pre-wrap;font-family:SimSun,monospace;font-size:12px;background:#fafafa;padding:12px;border-radius:4px;max-height:500px;overflow:auto;">{{ feiePreviewHtml }}</pre>
      </div>
      <template #footer>
        <el-button @click="feiePreviewVisible=false">关闭</el-button>
        <el-button type="primary" :loading="feiePrinting" :disabled="!feiePreviewHtml" @click="feieConfirmPrint">确认打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { purReceiptApi } from '@/api/purchase'
import { supplierApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { useStripZero } from '@/composables/useStripZero'
import { usePrint, BIZ_TYPES } from '@/composables/usePrint'
import { feiePrintApi } from '@/api/feie'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const { stripZeroFormat, stripZeroParse, stripTrailingZero2, stripTrailingZero4 } = useStripZero()

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '', supplierId: null, productName: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const suppliers = ref([])
const warehouses = ref([])
const products = ref([])
const productLoading = ref(false)
// v1.1.7+: 选定供应商后展示历史采购产品列表
const supplierHistory = ref([])
const historyLoading = ref(false)
const form = reactive({ billDate: new Date().toISOString().substring(0,10), supplierId: null, warehouseId: null, remark: '', details: [] })
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

async function loadSuppliers() {
  if (suppliers.value.length === 0) {
    suppliers.value = (await supplierApi.page({ pageNum: 1, pageSize: 200 })).data.records
  }
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    // 空字符串转为 undefined, 后端会忽略
    if (!params.billNo) delete params.billNo
    if (!params.productName) delete params.productName
    data.value = (await purReceiptApi.page(params)).data
  } finally { loading.value = false }
}

function onReset() {
  query.billNo = ''
  query.supplierId = null
  query.productName = ''
  query.pageNum = 1
  loadData()
}

async function onAdd() {
  loadTaxSeparation()
  form.id = null; form.details = []
  await loadSuppliers()
  warehouses.value = (await warehouseApi.list()).data
  products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data.records
  supplierHistory.value = []   // v1.1.7+ 重置历史采购
  dialogVisible.value = true
}

function addLine() {
  if (taxSeparation.value === 'true') {
    form.details.push({ qty: null, price: 0, taxRate: 13 })
  } else {
    form.details.push({ qty: null, price: 0 })
  }
}
async function onProduct(row, v) {
  const p = products.value.find(x => x.id === v); if (!p) return
  row.productId = p.id; row.productCode = p.productCode; row.productName = p.productName; row.spec = p.spec
  // 优先取该供应商对此商品的上次订单单价
  if (form.supplierId && row.productId) {
    try {
      const res = await purReceiptApi.getLastPrice(form.supplierId, row.productId)
      if (res.data > 0) { row.price = res.data; return }
    } catch (e) { /* ignore */ }
  }
  row.price = +p.purchasePrice || 0
}

/**
 * 供应商变化时, 自动加载该供应商历史采购产品列表. v1.1.7+ 新增.
 */
function onSupplierChange() {
  loadSupplierHistory()
}

/**
 * 加载指定供应商最近 50 条历史采购入库明细, 用于弹窗底部"历史采购"列表.
 * v1.1.7+ 新增.
 */
async function loadSupplierHistory() {
  if (!form.supplierId) { supplierHistory.value = []; return }
  historyLoading.value = true
  try {
    const r = await purReceiptApi.getSupplierHistoryProducts(form.supplierId)
    supplierHistory.value = r.data || []
  } catch (e) {
    supplierHistory.value = []
    ElMessage.error('加载供应商历史采购失败: ' + e.message)
  } finally { historyLoading.value = false }
}

/**
 * 点击历史采购行 → 把该明细加到 form.details.
 * 若同 productId+batchNo 已存在, 累加数量; 否则追加新行, 复用 products 找 label.
 */
async function onHistoryRowClick(row) {
  const newLine = {
    productId: row.productId, productCode: row.productCode, productName: row.productName,
    spec: row.spec, unitId: row.unitId, unitName: row.unitName,
    qty: row.qty != null ? Number(row.qty) : 0,
    price: row.price != null ? Number(row.price) : 0,
    taxRate: row.taxRate != null ? Number(row.taxRate) : 13,
    batchNo: row.batchNo, locationName: '', remark: row.remark
  }
  // 合并判断 (按 productId+batchNo 累加数量)
  const exist = form.details.find(d => d.productId === newLine.productId && (d.batchNo || '') === (newLine.batchNo || ''))
  if (exist) {
    exist.qty = (+exist.qty || 0) + (+newLine.qty || 0)
    ElMessage.success(`已合并到明细行 (累计数量 ${exist.qty})`)
    return
  }
  form.details.push(newLine)
  ElMessage.success('已加入明细行')
}

async function onSave() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  if (!form.warehouseId) return ElMessage.warning('请选择仓库')
  if (!form.details.length) return ElMessage.warning('请添加商品')
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
      await purReceiptApi.update(payload); ElMessage.success('修改成功')
    } else {
      await purReceiptApi.add(payload); ElMessage.success('保存成功')
    }
    dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

async function onEdit(row) {
  await loadSuppliers()
  warehouses.value = (await warehouseApi.list()).data
  products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data.records
  const detail = await purReceiptApi.detail(row.id)
  // 把后台返回的对象合并进 form, 保留响应式
  Object.assign(form, detail.data)
  dialogVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除入库单 ${row.billNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await purReceiptApi.delete(row.id)
    ElMessage.success('删除成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onCheck(row) {
  // P1-6: 采购入库审核会增加库存和成本, 关键操作必须二次确认
  try {
    await ElMessageBox.confirm(
      `确认审核采购入库单 ${row.billNo}?\n\n审核后将:\n• 增加库存\n• 更新商品成本\n• 生成应付 (AP → 供应商)\n`,
      '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await purReceiptApi.check(row.id); ElMessage.success('审核成功, 库存+成本已更新'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

// 打印
const { doPrint } = usePrint()
const PUR_RECEIPT_HEADER_MAP = {
  billNo: 'billNo',
  billDate: 'billDate',
  supplierName: 'supplierName',
  warehouseName: 'warehouseName',
  totalQty: 'totalQty',
  totalAmount: 'totalAmount',
  remark: 'remark'
}
const PUR_RECEIPT_DETAIL_MAP = {
  productCode: 'productCode',
  productName: 'productName',
  model: 'model',
  colorNo: 'colorNo',
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
    const r = await purReceiptApi.detail(row.id)
    await doPrint({
      bizType: BIZ_TYPES.PUR_RECEIPT,
      bill: r.data || {},
      fieldMap: PUR_RECEIPT_HEADER_MAP,
      detailsKey: 'details',
      detailFieldMap: PUR_RECEIPT_DETAIL_MAP
    })
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  }
}

// ==================== 飞鹅云打印 ====================
const feiePreviewVisible = ref(false)
const feiePreviewLoading = ref(false)
const feiePreviewHtml = ref('')
const feiePreviewBillNo = ref('')
const feiePrinting = ref(false)
let feiePendingBillId = null

async function feiePreview(bizType, row) {
  feiePreviewLoading.value = true
  try {
    feiePreviewBillNo.value = row.billNo || ''
    feiePendingBillId = row.id
    feiePreviewHtml.value = ''
    feiePreviewVisible.value = true
    const r = await feiePrintApi.preview(bizType, row.id)
    feiePreviewHtml.value = r.data || ''
  } catch (e) {
    ElMessage.error('飞鹅预览失败: ' + (e.message || '未知错误'))
    feiePreviewVisible.value = false
  } finally {
    feiePreviewLoading.value = false
  }
}

async function feieConfirmPrint() {
  if (!feiePendingBillId) return
  feiePrinting.value = true
  try {
    const res = await feiePrintApi.print('PUR_RECEIPT', feiePendingBillId)
    ElMessage.success(res.msg || '打印成功')
    feiePreviewVisible.value = false
  } catch (e) {
    ElMessage.error('飞鹅打印失败: ' + (e.message || '未知错误'))
  } finally {
    feiePrinting.value = false
  }
}

function onPrintCommand(cmd, row) {
  if (cmd === 'browser') return onPrint(row)
  if (cmd === 'feie-preview') return feiePreview('PUR_RECEIPT', row)
  if (cmd === 'feie-print') return feiePreview('PUR_RECEIPT', row)
}

onMounted(async () => { await loadSuppliers(); loadData() })
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
