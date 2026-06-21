<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onAdd" type="warning"><el-icon><Plus /></el-icon>新增退货</el-button>
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
        <el-table-column prop="totalAmountTax" label="退货金额" width="120" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='CHECKED'?'success':'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT'" link type="primary" @click="onCheck(row)">审核</el-button>
            <el-button link type="primary" @click="onPrint(row)">打印</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
    <el-dialog v-model="dialogVisible" title="新增采购退货单" width="1100px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="退货日期"><el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="供应商"><el-select v-model="form.supplierId" filterable style="width:100%"><el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="退回仓库"><el-select v-model="form.warehouseId" style="width:100%"><el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" /></el-select></el-form-item></el-col>
        </el-row>
        <el-alert type="warning" :closable="false" style="margin-bottom:8px">采购退货审核后, 库存减少并冲减应付账款</el-alert>
        <el-form-item label="退货明细">
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
            <el-table-column label="数量" width="120"><template #default="{ row }"><el-input-number v-model="row.qty" :precision="4" size="small" /></template></el-table-column>
            <el-table-column label="单价(含税)" width="120"><template #default="{ row }"><el-input-number v-model="row.price" :precision="4" size="small" /></template></el-table-column>
            <el-table-column v-if="taxSeparation === 'true'" label="税率" width="80"><template #default="{ row }"><el-input-number v-model="row.taxRate" :precision="2" size="small" /></template></el-table-column>
            <el-table-column label="金额" width="120" align="right"><template #default="{ row }"><span>{{ ((+row.qty||0) * (+row.price||0)).toFixed(2) }}</span></template></el-table-column>
            <el-table-column label="批次"><template #default="{ row }"><el-input v-model="row.batchNo" size="small" /></template></el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index,1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="printVisible" title="打印预览" width="500px">
      <iframe v-if="printUrl" :src="printUrl" style="width:100%;height:500px;border:0" />
      <p v-else style="text-align:center;color:#999;padding:40px">暂无打印内容</p>
      <template #footer>
        <el-button @click="printVisible=false">关闭</el-button>
        <el-button type="primary" @click="doPrint">打 印</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { purReturnApi } from '@/api/purchase'
import { supplierApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { ElMessage } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const printVisible = ref(false)
const printUrl = ref('')
const submitting = ref(false)
const suppliers = ref([])
const warehouses = ref([])
const products = ref([])
const productLoading = ref(false)
const form = reactive({ billDate: new Date().toISOString().substring(0,10), supplierId: null, warehouseId: null, remark: '', details: [] })
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

async function loadData() {
  loading.value = true
  try { data.value = (await purReturnApi.page(query)).data } finally { loading.value = false }
}

async function onAdd() {
  loadTaxSeparation()
  form.id = null; form.details = []
  suppliers.value = (await supplierApi.page({ pageNum: 1, pageSize: 500 })).data.records
  warehouses.value = (await warehouseApi.list()).data
  products.value = (await productApi.page({ pageNum: 1, pageSize: 100 })).data.records
  dialogVisible.value = true
}

function addLine() {
  form.details.push({ qty: null, price: 0, taxRate: 13 })
}

async function onProduct(row, v) {
  const p = products.value.find(x => x.id === v); if (!p) return
  row.productId = p.id; row.productCode = p.productCode; row.productName = p.productName; row.spec = p.spec
  row.price = +p.purchasePrice || 0
}

async function onSave() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  if (!form.warehouseId) return ElMessage.warning('请选择退回仓库')
  if (!form.details.length) return ElMessage.warning('请添加商品')
  submitting.value = true
  try {
    const payload = { ...form }
    let totalAmount = 0, taxAmount = 0
    payload.details.forEach(d => {
      const amt = (+d.qty || 0) * (+d.price || 0)
      totalAmount += amt
      taxAmount += amt * ((d.taxRate || 13) / 100)
    })
    payload.totalAmount = totalAmount
    payload.taxAmount = taxAmount
    payload.totalAmountTax = totalAmount + taxAmount
    await purReturnApi.add(payload); ElMessage.success('保存成功'); dialogVisible.value = false; loadData()
  } finally { submitting.value = false }
}

async function onCheck(row) {
  await purReturnApi.check(row.id); ElMessage.success('审核成功, 库存已扣减并冲减应付'); loadData()
}

function onPrint(row) {
  printUrl.value = `/api/print/purchase-return/${row.id}.html?token=${localStorage.getItem('erp_token')}`
  printVisible.value = true
}
function doPrint() { printVisible.value = false; window.open(printUrl.value, '_blank') }

onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
