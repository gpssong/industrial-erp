<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" placeholder="单号" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <!-- v1.1.19+: 含税单价口径, totalAmount = totalAmountTax = 开单金额, 只显示「金额」一列 -->
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='DRAFT'?'info':'success'">{{ row.billStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:edit')" link type="primary" size="small" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:delete')" link type="danger" size="small" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:check')" link type="success" size="small" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:order:uncheck')" link type="warning" size="small" @click="onUncheck(row)">反审核</el-button>
            <!-- v1.1.35: 已审核订单一键生成销售出库 (走现有 salDeliveryApi.add) -->
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:delivery:add')" link type="primary" size="small" @click="onGenerateDelivery(row)">生成出库单</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑订单' : '新增订单'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="客户"><el-select v-model="form.customerId" placeholder="请选择客户" filterable style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库"><el-select v-model="form.warehouseId" placeholder="请选择仓库" style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货日期"><el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="付款方式"><el-select v-model="form.payType" style="width:100%">
            <el-option label="款到发货" value="PREPAY" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="货到付款" value="ARRIVAL" />
          </el-select></el-form-item></el-col>
          <el-col :span="16"><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item></el-col>
        </el-row>
        <!-- 商品明细 -->
        <el-form-item label="明细">
          <el-button size="small" type="primary" plain @click="addDetail">添加商品</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="商品" width="200">
              <template #default="{ row }">
                <el-select v-model="row.productId" placeholder="商品" filterable @change="onProductChange(row)">
                  <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column label="数量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="4" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }"><el-button link type="danger" @click="form.details.splice($index, 1)">删除</el-button></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.35: 由销售订单一键生成销售出库单 (轻量级弹窗, 自动带入订单客户/仓库/明细) -->
    <el-dialog v-model="generateDialogVisible"
      :title="sourceOrder ? '生成出库单 (源 ' + sourceOrder.billNo + ')' : '生成出库单'"
      width="1000px" destroy-on-close>
      <el-form :model="deliveryForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="单据日期">
            <el-date-picker v-model="deliveryForm.billDate" type="date"
              value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="客户">
            <el-input v-model="deliveryForm.customerName" disabled />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库">
            <el-select v-model="deliveryForm.warehouseId" filterable style="width:100%">
              <el-option v-for="w in warehouses" :key="w.id"
                :label="w.warehouseName" :value="w.id" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="收货地址">
            <el-input v-model="deliveryForm.address" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="收货电话">
            <el-input v-model="deliveryForm.phone" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="整单折扣">
            <el-input-number v-model="deliveryForm.discountAmount" :min="0" :step-strictly="false" />
          </el-form-item></el-col>
        </el-row>

        <el-form-item label="商品明细">
          <el-table :data="deliveryForm.details" size="small" border max-height="380">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column prop="spec" label="规格" width="120" />
            <el-table-column prop="unitName" label="单位" width="60" />
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input v-model="row.qty" size="small" type="text" inputmode="decimal"
                  @blur="row.qty = normNum(row.qty)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }">
                <el-input v-model="row.price" size="small" type="text" inputmode="decimal"
                  @blur="row.price = normNum(row.price)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">
                <span>{{ ((+row.qty || 0) * (+row.price || 0)).toString() }}</span>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="160">
              <template #default="{ row }">
                <el-input v-model="row.batchNo" size="small" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="库位" width="100">
              <template #default="{ row }">
                <el-input v-model="row.locationName" size="small" />
              </template>
            </el-table-column>
          </el-table>
          <p style="color:#999;font-size:12px;margin-top:6px">
            提示: 数量默认为订单全量, 如部分发货请手工调整. 源订单 {{ sourceOrder?.billNo || '-' }} 关联字段已自动写入.
          </p>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deliveryForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onConfirmGenerate" :loading="submitting">保存为草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { salOrderApi, salDeliveryApi } from '@/api/sales'
import { useUserStore } from '@/store/user'
import { customerApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { ElMessage, ElMessageBox } from 'element-plus'

// v1.1.33: el-input + inputmode=decimal 数字归一化 (与 Delivery.vue 同模式)
const normNum = (v) => {
  if (v == null || v === '') return 0
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : 0
}

const userStore = useUserStore()

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref({ id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] })
const customers = ref([])
const warehouses = ref([])
const products = ref([])
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

// v1.1.35: 由销售订单生成出库单的轻量级弹窗状态
const generateDialogVisible = ref(false)
const sourceOrder = ref(null)        // 源订单完整 SalOrder
const generating = ref(false)        // 加载源订单中
const deliveryForm = reactive({
  billDate: '', customerId: null, customerName: '',
  warehouseId: null, address: '', phone: '',
  discountAmount: 0, tailAmount: 0, remark: '',
  details: [],
  // 联动字段 (后端 BaseMapper.insert 自动写入 sal_delivery.order_id/order_no)
  orderId: null, orderNo: ''
})

async function loadData() {
  loading.value = true
  try { data.value = (await salOrderApi.page(query)).data } finally { loading.value = false }
}

async function loadOptions() {
  if (customers.value.length === 0) customers.value = (await customerApi.list()).data || []
  if (warehouses.value.length === 0) warehouses.value = (await warehouseApi.list()).data || []
  if (products.value.length === 0) products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data?.records || []
}

function onAdd() {
  loadTaxSeparation()
  form.value = { id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] }
  loadOptions()
  dialogVisible.value = true
}

async function onEdit(row) {
  loadTaxSeparation()
  const r = await salOrderApi.detail(row.id)
  form.value = { ...r.data, details: r.data.details || [] }
  loadOptions()
  dialogVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除订单 ${row.billNo}?`, '提示', { type: 'warning' })
  try {
    await salOrderApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// v1.1.11+ 审核 (status-only, 下游出库单触发库存账)
async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核订单 ${row.billNo}?`, '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核订单 ${row.billNo}? 单据状态将回到「草稿」.`, '反审核确认', { type: 'warning', confirmButtonText: '确认反审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '反审核失败')
  }
}

// v1.1.35: 已审核订单 → 加载订单明细 → 弹出生成出库单弹窗
async function onGenerateDelivery(row) {
  generating.value = true
  try {
    const r = await salOrderApi.detail(row.id)
    const o = r.data
    if (!o || !o.details || !o.details.length) {
      ElMessage.warning('订单无明细, 无法生成'); return
    }
    sourceOrder.value = o
    // 重置 deliveryForm (避免上次残留)
    deliveryForm.billDate = new Date().toISOString().substring(0, 10)
    deliveryForm.customerId = o.customerId
    deliveryForm.customerName = o.customerName
    deliveryForm.address = ''
    deliveryForm.phone = ''
    deliveryForm.discountAmount = 0
    deliveryForm.tailAmount = 0
    deliveryForm.remark = ''
    deliveryForm.orderId = o.id
    deliveryForm.orderNo = o.billNo
    // 订单明细 → 出库明细 (默认值 = 全量; 用户可手工改数量做部分发货)
    deliveryForm.details = (o.details || []).map((d, idx) => ({
      productId: d.productId,
      productCode: d.productCode,
      productName: d.productName,
      spec: d.spec,
      unitId: d.unitId,
      unitName: d.unitName,
      qty: d.qty != null ? Number(d.qty) : 0,
      price: d.price != null ? Number(d.price) : 0,
      taxRate: d.taxRate != null ? Number(d.taxRate) : 13,
      lineNo: idx + 1,
      orderDetailId: d.id,                  // 联动字段, 后端自动写入 sal_delivery_detail
      batchNo: '',
      locationName: '',
      remark: ''
    }))
    // 加载仓库列表 (loadOptions 内部已做幂等缓存)
    await loadOptions()
    deliveryForm.warehouseId = o.warehouseId
    generateDialogVisible.value = true
  } catch (e) {
    ElMessage.error('加载订单失败: ' + (e.message || '未知错误'))
  } finally { generating.value = false }
}

// v1.1.35: 确认生成 → 走现有 salDeliveryApi.add (BaseMapper.insert 自动写 orderId/orderNo/orderDetailId)
async function onConfirmGenerate() {
  if (!deliveryForm.warehouseId) {
    ElMessage.warning('请选择仓库'); return
  }
  if (!deliveryForm.details.length) {
    ElMessage.warning('订单无明细'); return
  }
  submitting.value = true
  try {
    let totalAmount = 0
    deliveryForm.details.forEach(d => {
      totalAmount += (+d.qty || 0) * (+d.price || 0)
    })
    const discount = +deliveryForm.discountAmount || 0
    const tail = +deliveryForm.tailAmount || 0
    const payload = {
      ...deliveryForm,
      totalAmount: totalAmount - discount - tail,
      taxAmount: 0,
      totalAmountTax: totalAmount - discount - tail
    }
    // 剔除前端 UI 辅助字段, 避免脏数据传后端
    payload.details = deliveryForm.details.map(d => {
      const cleaned = { ...d }
      delete cleaned._units
      delete cleaned._priceFromUnit
      return cleaned
    })
    await salDeliveryApi.add(payload)
    ElMessage.success(`已生成出库单草稿, 源订单 ${sourceOrder.value.billNo}`)
    generateDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error((e && e.msg) || (e && e.message) || '生成失败')
  } finally { submitting.value = false }
}

function addDetail() {
  if (taxSeparation.value === 'true') {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0, taxRate: 13 })
  } else {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0 })
  }
}

async function onProductChange(row) {
  const p = products.value.find(x => x.id === row.productId)
  if (!p) return
  row.productCode = p.productCode
  row.productName = p.productName
  // 优先取该客户对此商品的上次出库单价
  if (form.value.customerId && row.productId) {
    try {
      const res = await salOrderApi.getLastPrice(form.value.customerId, row.productId)
      if (res.data > 0) { row.price = res.data; return }
    } catch (e) { /* ignore */ }
  }
  row.price = p.salePrice || 0
}

async function onSubmit() {
  if (!form.value.customerId) { ElMessage.warning('请选择客户'); return }
  if (!form.value.details.length) { ElMessage.warning('请添加商品明细'); return }
  submitting.value = true
  try {
    // v1.1.19+: 含税单价. totalAmount = totalAmountTax = 开单金额, 不再算税.
    let totalQty = 0, totalAmount = 0
    form.value.details.forEach(d => {
      totalQty += d.qty || 0
      totalAmount += (d.qty || 0) * (d.price || 0)
    })
    form.value.totalQty = totalQty
    form.value.totalAmount = totalAmount
    form.value.taxAmount = 0
    form.value.totalAmountTax = totalAmount

    if (form.value.id) {
      await salOrderApi.update(form.value)
    } else {
      await salOrderApi.add(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

onMounted(loadData)
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
</style>
