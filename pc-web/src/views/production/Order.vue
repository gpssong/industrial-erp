<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增生产单</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="productName" label="成品" />
        <el-table-column prop="planQty" label="计划数量" width="100" align="right" />
        <el-table-column prop="actualQty" label="实际数量" width="100" align="right" />
        <el-table-column prop="goodQty" label="良品" width="100" align="right" />
        <el-table-column prop="lossQty" label="损耗" width="100" align="right" />
        <el-table-column prop="lossRate" label="损耗率%" width="100" align="right" />
        <el-table-column prop="workshop" label="车间" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag>{{ ({DRAFT:'草稿',RELEASED:'已开工',PRODUCING:'生产中',FINISHED:'已完成',CLOSED:'已关闭'})[row.billStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onPrint(row)">打印</el-button>
            <el-button v-if="row.billStatus==='DRAFT' || row.billStatus==='RELEASED'" link type="primary" @click="onRelease(row)">开工</el-button>
            <el-button v-if="row.billStatus==='RELEASED' || row.billStatus==='PRODUCING'" link type="primary" @click="onFinish(row)">完工</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 新增弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增生产单" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item label="BOM" prop="bomId">
          <el-select v-model="form.bomId" placeholder="请选择BOM" filterable style="width:100%" @change="onBomChange">
            <el-option v-for="b in bomList" :key="b.id" :label="`${b.bomCode} ${b.bomName}`" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="成品">
          <el-input v-model="form.productName" readonly placeholder="选择BOM后自动带出" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="计划数量" prop="planQty">
              <el-input-number v-model="form.planQty" :min="0" :precision="4" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="损耗率%">
              <el-input-number v-model="form.lossRate" :min="0" :precision="2" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="车间">
              <el-input v-model="form.workshop" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="仓库">
              <el-select v-model="form.warehouseId" placeholder="请选择仓库" clearable style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="开工日期">
              <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束日期">
              <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="负责人">
          <el-input v-model="form.leader" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 完工登记弹窗 -->
    <el-dialog v-model="finishVisible" title="完工登记" width="420px">
      <el-form :model="finishForm" label-width="100px">
        <el-form-item label="良品数量"><el-input-number v-model="finishForm.goodQty" :precision="4" :min="0" /></el-form-item>
        <el-form-item label="损耗数量"><el-input-number v-model="finishForm.lossQty" :precision="4" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="finishVisible=false">取消</el-button>
        <el-button type="primary" @click="doFinish">确定</el-button>
      </template>
    </el-dialog>

    <!-- 打印弹窗 -->
    <el-dialog v-model="printVisible" title="生产加工单打印" width="850px" body-style="padding:0">
      <iframe v-if="printUrl" :src="printUrl" style="width:100%;height:700px;border:0" />
      <template #footer>
        <el-button @click="printVisible=false">取消</el-button>
        <el-button type="primary" @click="doPrint">打 印</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { prdOrderApi, bomApi } from '@/api/production'
import { warehouseApi } from '@/api/base'
import { ElMessage } from 'element-plus'
const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const finishVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const bomList = ref([])
const warehouses = ref([])
const form = ref({ bomId: null, productId: null, productName: '', productCode: '', spec: '', unitId: null, unitName: '', planQty: 1, lossRate: 0, workshop: '', warehouseId: null, leader: '', startDate: '', endDate: '', remark: '' })
const finishForm = reactive({ id: null, goodQty: 0, lossQty: 0 })
const printVisible = ref(false)
const printUrl = ref('')
function doPrint() {
  printVisible.value = false
  const w = window.open(printUrl.value, '_blank')
  if (w) w.onload = () => w.print()
}
const rules = { bomId: [{ required: true, message: '请选择BOM', trigger: 'change' }], planQty: [{ required: true, message: '请填写计划数量', trigger: 'blur' }] }

async function loadData() { loading.value = true; try { data.value = (await prdOrderApi.page(query)).data } finally { loading.value = false } }
async function loadBomList() { if (bomList.value.length === 0) bomList.value = (await bomApi.page({ pageNum: 1, pageSize: 999 })).data?.records || [] }
async function loadWarehouses() { if (warehouses.value.length === 0) warehouses.value = (await warehouseApi.list()).data || [] }

async function onAdd() {
  form.value = { bomId: null, productId: null, productName: '', productCode: '', spec: '', unitId: null, unitName: '', planQty: 1, lossRate: 0, workshop: '', warehouseId: null, leader: '', startDate: '', endDate: '', remark: '' }
  await loadBomList()
  await loadWarehouses()
  dialogVisible.value = true
}

async function onBomChange(bomId) {
  const bom = bomList.value.find(b => b.id === bomId)
  if (bom) {
    form.value.productId = bom.productId
    form.value.productName = bom.productName
    form.value.productCode = bom.productCode
    form.value.spec = bom.spec
    form.value.unitId = bom.unitId
    form.value.unitName = bom.unitName
    form.value.lossRate = bom.lossRate || 0
  }
}

async function onSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    await prdOrderApi.add(form.value)
    ElMessage.success('新增成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  } finally { submitting.value = false }
}

async function onRelease(row) {
  await prdOrderApi.release(row.id); ElMessage.success('已开工, 自动生成领料单'); loadData()
}
function onFinish(row) {
  finishForm.id = row.id; finishForm.goodQty = row.planQty || 0; finishForm.lossQty = 0
  finishVisible.value = true
}
async function doFinish() {
  await prdOrderApi.finish(finishForm.id, { goodQty: finishForm.goodQty, lossQty: finishForm.lossQty })
  ElMessage.success('完工, 成品已入库'); finishVisible.value = false; loadData()
}
function onPrint(row) {
  printUrl.value = `http://localhost:8080/api/print/prd-order/${row.id}.html`
  printVisible.value = true
}
onMounted(loadData)
</script>
<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
</style>
