<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" placeholder="编码/名称/条码/规格" clearable /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="left">
          <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增商品</el-button>
          <el-button @click="loadData"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
      </div>
      <el-table :data="data.records" border stripe size="default" v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="productCode" label="商品编码" width="140" />
        <el-table-column prop="productName" label="商品名称" />
        <el-table-column prop="spec" label="规格" width="160" />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column prop="material" label="材质" width="80" />
        <el-table-column prop="thickness" label="厚度" width="80" />
        <el-table-column prop="width" label="幅宽" width="80" />
        <el-table-column prop="colorNo" label="色号" width="80" />
        <el-table-column prop="salesPrice" label="零售价" width="100" align="right" />
        <el-table-column prop="costPrice" label="成本价" width="100" align="right" />
        <el-table-column prop="safetyStock" label="安全库存" width="100" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'启用':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="onStock(row)">库存</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="900px">
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="商品编码" prop="productCode"><el-input v-model="form.productCode" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="商品名称" prop="productName"><el-input v-model="form.productName" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="型号"><el-input v-model="form.model" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="材质"><el-input v-model="form.material" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="色号"><el-input v-model="form.colorNo" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="厚度"><el-input-number v-model="form.thickness" :precision="4" :step="0.1" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="幅宽"><el-input-number v-model="form.width" :precision="4" :step="1" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="密度"><el-input-number v-model="form.density" :precision="6" :step="0.01" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="条形码"><el-input v-model="form.barcode" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="零售价"><el-input-number v-model="form.salesPrice" :precision="4" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="批发价"><el-input-number v-model="form.wholesalePrice" :precision="4" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="大客户价"><el-input-number v-model="form.vipPrice" :precision="4" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="参考采购价"><el-input-number v-model="form.purchasePrice" :precision="4" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="税率(%)"><el-input-number v-model="form.taxRate" :precision="2" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="安全库存"><el-input-number v-model="form.safetyStock" :precision="4" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="批次管理"><el-switch v-model="form.isBatch" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="序列号"><el-switch v-model="form.isSn" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>
        <div class="units-section">
          <el-button @click="addUnit" type="primary" plain size="small"><el-icon><Plus /></el-icon>添加单位</el-button>
          <div v-for="(unit, idx) in form.units" :key="idx" class="unit-row">
            <el-tag :type="unit.isMain === 1 ? 'success' : 'info'" @click="setMainUnit(idx)" style="cursor:pointer; margin-right:8px">主</el-tag>
            <el-input v-model="unit.unitName" placeholder="单位名称" size="small" style="width:100px;margin-right:8px" />
            <el-input-number v-model="unit.conversionRate" :precision="6" :step="0.01" size="small" style="width:120px;margin-right:8px" placeholder="换算率" />
            <el-input-number v-model="unit.salesPrice" :precision="4" size="small" style="width:100px;margin-right:8px" placeholder="零售价" />
            <el-input-number v-model="unit.wholesalePrice" :precision="4" size="small" style="width:100px;margin-right:8px" placeholder="批发价" />
            <el-input-number v-model="unit.vipPrice" :precision="4" size="small" style="width:100px;margin-right:8px" placeholder="大客户价" />
            <el-button link type="danger" @click="form.units.splice(idx, 1)">删除</el-button>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 库存 -->
    <el-dialog v-model="stockVisible" :title="`库存: ${stockDetail?.product?.productName}`" width="700px">
      <el-table :data="stockDetail?.stockSummary || []" size="small" border>
        <el-table-column prop="warehouseId" label="仓库ID" />
        <el-table-column prop="warehouseName" label="仓库" />
        <el-table-column prop="qty" label="库存数量" align="right" />
        <el-table-column prop="availableQty" label="可用数量" align="right" />
        <el-table-column prop="totalCost" label="总成本" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { productApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const stockVisible = ref(false)
const stockDetail = ref(null)
const submitting = ref(false)
const formRef = ref()

const form = reactive({
  id: null, productCode: '', productName: '', spec: '', model: '', material: '',
  thickness: null, width: null, density: null, colorNo: '', barcode: '',
  salesPrice: 0, wholesalePrice: 0, vipPrice: 0, purchasePrice: 0, costPrice: 0,
  taxRate: 13.00, safetyStock: 0, isBatch: 1, isSn: 0, status: 1,
  units: []
})
const rules = {
  productCode: [{ required: true, message: '请输入编码' }],
  productName: [{ required: true, message: '请输入名称' }]
}

async function loadData() {
  loading.value = true
  try {
    const r = await productApi.page(query)
    data.value = r.data
  } finally { loading.value = false }
}

function reset() { query.keyword = ''; loadData() }

function onAdd() {
  form.id = null
  form.productCode = ''
  form.productName = ''
  form.spec = ''
  form.model = ''
  form.material = ''
  form.thickness = null
  form.width = null
  form.density = null
  form.colorNo = ''
  form.barcode = ''
  form.salesPrice = 0
  form.wholesalePrice = 0
  form.vipPrice = 0
  form.purchasePrice = 0
  form.costPrice = 0
  form.taxRate = 13.00
  form.safetyStock = 0
  form.isBatch = 1
  form.isSn = 0
  form.status = 1
  form.units = [{ unitName: '卷', isMain: 1, conversionRate: 1 }]
  dialogVisible.value = true
}

async function onEdit(row) {
  const r = await productApi.detail(row.id)
  const d = r.data
  Object.assign(form, d.product)
  form.units = d.units || []
  dialogVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除 ${row.productName} ?`, '提示', { type: 'warning' })
  await productApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}

function addUnit() {
  form.units.push({ unitName: '', isMain: 0, conversionRate: 1, salesPrice: 0, wholesalePrice: 0, vipPrice: 0 })
}

function setMainUnit(index) {
  form.units.forEach((u, i) => { u.isMain = i === index ? 1 : 0 })
}

async function onSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (form.id) await productApi.update({ product: form, units: form.units })
    else await productApi.add({ product: form, units: form.units })
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

async function onStock(row) {
  const r = await productApi.detail(row.id)
  stockDetail.value = r.data
  stockVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.pager { margin-top: 12px; text-align: right; }
.units-section { margin-bottom: 18px; }
.unit-row { display: flex; align-items: center; margin-top: 8px; gap: 4px; }
</style>
