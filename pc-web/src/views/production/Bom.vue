<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" clearable placeholder="编码/名称" /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增BOM</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="bomCode" label="BOM编号" width="160" />
        <el-table-column prop="bomName" label="BOM名称" />
        <el-table-column prop="productCode" label="成品编码" width="140" />
        <el-table-column prop="productName" label="成品名称" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="onView(row)">明细</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑BOM' : '新增BOM'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="BOM名称"><el-input v-model="form.bomName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="成品">
            <el-select v-model="form.productId" placeholder="请选择成品" filterable style="width:100%">
              <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="版本"><el-input v-model="form.version" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="损耗率%"><el-input-number v-model="form.lossRate" :min="0" :max="100" :precision="2" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item></el-col>
        </el-row>
        <!-- 原料明细 -->
        <el-form-item label="原料明细">
          <el-button size="small" type="primary" plain @click="addDetail">添加原料</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-select v-model="row.materialType" style="width:100%">
                  <el-option label="原料" value="MATERIAL" />
                  <el-option label="半成品" value="SEMI" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="原料" width="220">
              <template #default="{ row }">
                <el-select v-model="row.productId" placeholder="原料" filterable style="width:100%">
                  <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="100" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column label="基础用量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.baseQty" :min="0" :precision="4" size="small" style="width:100%" /></template>
            </el-table-column>
            <el-table-column label="损耗率%" width="100">
              <template #default="{ row }"><el-input-number v-model="row.lossRate" :min="0" :precision="2" size="small" style="width:100%" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }"><el-button link type="danger" @click="form.details.splice($index,1)">删</el-button></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 明细查看弹窗 -->
    <el-dialog v-model="detailVisible" title="BOM明细" width="800px">
      <el-table :data="current?.details || []" size="small" border>
        <el-table-column prop="materialType" label="类型" width="80" />
        <el-table-column prop="productCode" label="原料编码" width="120" />
        <el-table-column prop="productName" label="原料名称" />
        <el-table-column prop="spec" label="规格" />
        <el-table-column prop="baseQty" label="基础用量" width="100" align="right" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { bomApi } from '@/api/production'
import { productApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const submitting = ref(false)
const current = ref(null)
const products = ref([])
const form = ref({ id: null, bomName: '', productId: null, version: 'V1', lossRate: 0, remark: '', details: [] })

async function loadData() {
  loading.value = true
  try { data.value = (await bomApi.page(query)).data } finally { loading.value = false }
}

async function loadProducts() {
  if (products.value.length === 0) products.value = (await productApi.page({ pageNum: 1, pageSize: 999 })).data?.records || []
}

function onAdd() {
  form.value = { id: null, bomName: '', productId: null, version: 'V1', lossRate: 0, remark: '', details: [] }
  loadProducts()
  dialogVisible.value = true
}

async function onEdit(row) {
  const r = await bomApi.detail(row.id)
  form.value = { ...r.data, details: r.data.details || [] }
  loadProducts()
  dialogVisible.value = true
}

async function onView(row) {
  current.value = (await bomApi.detail(row.id)).data
  detailVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除BOM ${row.bomName}?`, '提示', { type: 'warning' })
  await bomApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}

function addDetail() {
  form.value.details.push({ materialType: 'MATERIAL', productId: null, productCode: '', productName: '', spec: '', baseQty: 1, lossRate: 0 })
}

async function onSubmit() {
  if (!form.value.bomName) { ElMessage.warning('请填写BOM名称'); return }
  if (!form.value.productId) { ElMessage.warning('请选择成品'); return }
  submitting.value = true
  try {
    // 同步成品信息到表单
    const p = products.value.find(x => x.id === form.value.productId)
    if (p) {
      form.value.productCode = p.productCode
      form.value.productName = p.productName
    }
    // 同步原料信息
    let line = 0
    for (const d of form.value.details) {
      d.lineNo = ++line
      const mp = products.value.find(x => x.id === d.productId)
      if (mp) {
        d.productCode = mp.productCode
        d.productName = mp.productName
        d.spec = mp.spec
      }
    }
    if (form.value.id) {
      await bomApi.update(form.value)
    } else {
      await bomApi.add(form.value)
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
