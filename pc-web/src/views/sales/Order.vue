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
        <template v-if="taxSeparation === 'true'">
          <el-table-column prop="totalAmount" label="不含税" width="120" align="right" />
          <el-table-column prop="taxAmount" label="税额" width="100" align="right" />
          <el-table-column prop="totalAmountTax" label="价税合计" width="120" align="right" />
        </template>
        <template v-else>
          <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        </template>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='DRAFT'?'info':'success'">{{ row.billStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
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
            <el-table-column v-if="taxSeparation === 'true'" label="税率" width="80">
              <template #default="{ row }"><el-input-number v-model="row.taxRate" :precision="2" :step-strictly="false" size="small" /></template>
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
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { salOrderApi } from '@/api/sales'
import { customerApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { ElMessage, ElMessageBox } from 'element-plus'

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

async function loadData() {
  loading.value = true
  try { data.value = (await salOrderApi.page(query)).data } finally { loading.value = false }
}

async function loadOptions() {
  if (customers.value.length === 0) customers.value = (await customerApi.list()).data || []
  if (warehouses.value.length === 0) warehouses.value = (await warehouseApi.list()).data || []
  if (products.value.length === 0) products.value = (await productApi.page({ pageNum: 1, pageSize: 999 })).data?.records || []
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
    let totalQty = 0, totalAmount = 0
    if (taxSeparation.value === 'true') {
      let taxAmount = 0
      form.value.details.forEach(d => {
        totalQty += d.qty || 0
        const amt = (d.qty || 0) * (d.price || 0)
        totalAmount += amt
        taxAmount += amt * ((d.taxRate || 13) / 100)
      })
      form.value.totalQty = totalQty
      form.value.totalAmount = totalAmount
      form.value.taxAmount = taxAmount
      form.value.totalAmountTax = totalAmount + taxAmount
    } else {
      form.value.details.forEach(d => {
        totalQty += d.qty || 0
        totalAmount += (d.qty || 0) * (d.price || 0)
      })
      form.value.totalQty = totalQty
      form.value.totalAmount = totalAmount
      form.value.taxAmount = 0
      form.value.totalAmountTax = totalAmount
    }

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
