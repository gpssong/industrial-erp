<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号">
          <el-input v-model="query.billNo" placeholder="搜索单号" clearable @keyup.enter="loadData" />
        </el-form-item>
        <el-form-item label="仓库">
          <el-select v-model="query.warehouseId" clearable filterable style="width:160px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable style="width:120px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
            <el-option label="已调整" value="ADJUSTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button type="success" @click="onAdd"><el-icon><Plus /></el-icon>新增盘点单</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="220" />
        <el-table-column prop="billDate" label="业务日期" width="110" />
        <el-table-column prop="warehouseName" label="仓库" width="140" show-overflow-tooltip />
        <el-table-column prop="checkType" label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.checkType === 'ALL' ? 'success' : 'info'">
              {{ row.checkType === 'ALL' ? '全盘' : row.checkType === 'PARTIAL' ? '部分盘点' : row.checkType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差异数量" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: (row.totalDiffQty || 0) > 0 ? '#67c23a' : (row.totalDiffQty || 0) < 0 ? '#f56c6c' : '' }">
              {{ formatNum(row.totalDiffQty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="差异金额" width="120" align="right">
          <template #default="{ row }">
            <span :style="{ color: (row.totalDiffAmount || 0) > 0 ? '#67c23a' : (row.totalDiffAmount || 0) < 0 ? '#f56c6c' : '' }">
              ¥ {{ formatNum(row.totalDiffAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.billStatus === 'ADJUSTED' ? 'success' : row.billStatus === 'CHECKED' ? 'warning' : 'info'">
              {{ row.billStatus === 'ADJUSTED' ? '已调整' : row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onView(row)">详情</el-button>
            <el-button v-if="row.billStatus === 'DRAFT'" link type="success" size="small" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus === 'DRAFT'" link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background
        layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50, 100]"
        @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="盘点单详情" width="1100px" destroy-on-close>
      <div v-if="current" v-loading="detailLoading">
        <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="单号">{{ current.billNo }}</el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ current.billDate }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ current.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="current.billStatus === 'ADJUSTED' ? 'success' : current.billStatus === 'CHECKED' ? 'warning' : 'info'">
              {{ current.billStatus === 'ADJUSTED' ? '已调整' : current.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ current.checkType }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ current.createTime }}</el-descriptions-item>
          <el-descriptions-item label="差异数量" :span="2">
            <span :style="{ color: (current.totalDiffQty || 0) > 0 ? '#67c23a' : (current.totalDiffQty || 0) < 0 ? '#f56c6c' : '' }">
              {{ formatNum(current.totalDiffQty) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="差异金额">
            <span :style="{ color: (current.totalDiffAmount || 0) > 0 ? '#67c23a' : (current.totalDiffAmount || 0) < 0 ? '#f56c6c' : '' }">
              ¥ {{ formatNum(current.totalDiffAmount) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ current.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="current.billStatus === 'ADJUSTED'" type="success" :closable="false" style="margin-bottom:8px">
          已审核, 库存已按差异自动调整 (盘盈入库 / 盘亏出库). 账面数以提交时刻为准.
        </el-alert>
        <el-table :data="current.details" border size="small" max-height="450">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="productCode" label="商品编码" width="140" />
          <el-table-column prop="productName" label="商品名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="账面" width="100" align="right">
            <template #default="{ row }">{{ formatNum(row.bookQty) }}</template>
          </el-table-column>
          <el-table-column label="实盘" width="100" align="right">
            <template #default="{ row }">{{ formatNum(row.actualQty) }}</template>
          </el-table-column>
          <el-table-column label="差异" width="100" align="right">
            <template #default="{ row }">
              <span :style="{ color: (row.diffQty || 0) > 0 ? '#67c23a' : (row.diffQty || 0) < 0 ? '#f56c6c' : '' }">
                {{ formatNum(row.diffQty) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }">¥ {{ formatNum(row.price) }}</template>
          </el-table-column>
          <el-table-column label="差异金额" width="120" align="right">
            <template #default="{ row }">
              <span :style="{ color: (row.diffAmount || 0) > 0 ? '#67c23a' : (row.diffAmount || 0) < 0 ? '#f56c6c' : '' }">
                ¥ {{ formatNum(row.diffAmount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="差异类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.diffType === 'PROFIT' ? 'success' : row.diffType === 'LOSS' ? 'danger' : 'info'">
                {{ row.diffType === 'PROFIT' ? '盘盈' : row.diffType === 'LOSS' ? '盘亏' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="current && current.billStatus === 'DRAFT'" type="success" @click="onCheck(current); detailVisible = false;">审核</el-button>
      </template>
    </el-dialog>

    <!-- 新增盘点单 -->
    <el-dialog v-model="addVisible" title="新增盘点单" width="1100px" destroy-on-close>
      <el-form :model="form" label-width="100px" ref="formRef" :rules="rules">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="业务日期" prop="billDate">
              <el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="仓库" prop="warehouseId">
              <el-select v-model="form.warehouseId" filterable style="width:100%" @change="onWarehouseChange">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="备注">
              <el-input v-model="form.remark" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="盘点明细" prop="details">
          <el-button @click="onAddLine" type="primary" plain size="small" :disabled="!form.warehouseId">
            <el-icon><Plus /></el-icon>添加行
          </el-button>
          <el-button @click="onLoadFromSnapshot" :disabled="!form.warehouseId" plain size="small">
            从仓库账面预填
          </el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px" max-height="380">
            <el-table-column label="#" type="index" width="50" />
            <el-table-column label="商品" min-width="240">
              <template #default="{ row }">
                <el-select v-model="row.productId" filterable remote :remote-method="searchProduct"
                           style="width:100%" @change="v => onProductChange(row, v)">
                  <el-option v-for="p in productList" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="账面" width="100" align="right">
              <template #default="{ row }">
                <el-input-number v-model="row.bookQty" :precision="4" :step-strictly="false" size="small" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="实盘" width="100" align="right">
              <template #default="{ row }">
                <el-input-number v-model="row.actualQty" :precision="4" :step-strictly="false" size="small" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="差异" width="100" align="right">
              <template #default="{ row }">
                <span :style="{ color: (Number(row.actualQty || 0) - Number(row.bookQty || 0)) > 0 ? '#67c23a' : (Number(row.actualQty || 0) - Number(row.bookQty || 0)) < 0 ? '#f56c6c' : '' }">
                  {{ formatNum(Number(row.actualQty || 0) - Number(row.bookQty || 0)) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="120">
              <template #default="{ row }">
                <el-input v-model="row.remark" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }">
                <el-button link type="danger" size="small" @click="form.details.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { invCheckApi } from '@/api/inventory'
import { warehouseApi, productApi } from '@/api/base'

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '', billStatus: '', warehouseId: null })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const warehouses = ref([])
const productList = ref([])

const detailVisible = ref(false)
const detailLoading = ref(false)
const current = ref(null)

const addVisible = ref(false)
const formRef = ref(null)
const submitting = ref(false)
const form = reactive({
  id: null,
  billDate: new Date().toISOString().substring(0, 10),
  warehouseId: null,
  remark: '',
  details: []
})

const rules = {
  billDate: [{ required: true, message: '请选择业务日期', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择仓库', trigger: 'change' }],
  details: [{
    validator: (rule, value, cb) => {
      if (!value || value.length === 0) { cb(new Error('请至少添加一行盘点明细')); return }
      cb()
    },
    trigger: 'change'
  }]
}

function formatNum(n) {
  if (n == null || n === '') return '0'
  const num = Number(n)
  if (isNaN(num)) return n
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
}

async function loadData() {
  loading.value = true
  try {
    data.value = (await invCheckApi.page(query)).data
  } finally {
    loading.value = false
  }
}

async function loadWarehouses() {
  if (warehouses.value.length === 0) {
    warehouses.value = (await warehouseApi.list()).data || []
  }
}

function onReset() {
  Object.assign(query, { pageNum: 1, pageSize: 20, billNo: '', billStatus: '', warehouseId: null })
  loadData()
}

async function onView(row) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    current.value = (await invCheckApi.detail(row.id)).data
  } finally {
    detailLoading.value = false
  }
}

async function onCheck(row) {
  // P1-6: 审核会触发盈亏入库/出库, 关键操作必须二次确认
  try {
    await ElMessageBox.confirm(
      `确认审核盘点单 ${row.billNo}?\n\n审核后将根据差异自动调整库存:\n• 盘盈 → 入库 (按成本价)\n• 盘亏 → 出库\n• 生成库存台账记录 (单据类型: 盘点)\n• 单据状态变为"已调整", 不可再修改\n`,
      '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await invCheckApi.check(row.id)
    ElMessage.success('审核成功, 库存已按差异调整')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除盘点单 ${row.billNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await invCheckApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function onAdd() {
  Object.assign(form, { id: null, billDate: new Date().toISOString().substring(0, 10), warehouseId: null, remark: '', details: [] })
  productList.value = []
  addVisible.value = true
  loadWarehouses()
}

function onWarehouseChange(whId) {
  // 切换仓库时清空已选明细 (账面数与仓库强绑定)
  form.details = []
}

async function onLoadFromSnapshot() {
  if (!form.warehouseId) {
    ElMessage.warning('请先选择仓库')
    return
  }
  try {
    const list = (await invCheckApi.stockSnapshot(form.warehouseId)).data || []
    if (list.length === 0) {
      ElMessage.info('该仓库暂无库存, 请手动添加')
      return
    }
    // 合并到现有明细 (按 productId 去重)
    const existing = new Set(form.details.map(d => d.productId))
    let added = 0
    for (const snap of list) {
      if (existing.has(snap.productId)) continue
      form.details.push({
        productId: snap.productId,
        productCode: snap.productCode,
        productName: snap.productName,
        unitName: snap.unitName,
        bookQty: snap.bookQty,
        actualQty: snap.bookQty,  // 默认实盘 = 账面, 用户逐行修正
        remark: ''
      })
      added++
    }
    ElMessage.success(`已预填 ${added} 个商品 (账面快照)`)
  } catch (e) {
    ElMessage.error('加载账面快照失败: ' + (e.message || ''))
  }
}

function onAddLine() {
  form.details.push({
    productId: null,
    productCode: '',
    productName: '',
    unitName: '',
    bookQty: 0,
    actualQty: 0,
    remark: ''
  })
}

// 商品搜索 (250ms debounce)
let searchProductTimer = null
let productSearchSeq = 0
async function searchProduct(kw) {
  if (searchProductTimer) clearTimeout(searchProductTimer)
  searchProductTimer = setTimeout(async () => {
    const mySeq = ++productSearchSeq
    try {
      const r = await productApi.page({ pageNum: 1, pageSize: 20, keyword: kw })
      if (mySeq !== productSearchSeq) return
      productList.value = r.data.records || []
    } catch {
      if (mySeq === productSearchSeq) productList.value = []
    }
  }, 250)
}

async function onProductChange(row, productId) {
  if (!productId) return
  try {
    const r = await productApi.detail(productId)
    const p = r.data?.product
    if (!p) return
    row.productCode = p.productCode
    row.productName = p.productName
    row.unitName = ''
    if (row.bookQty == null || row.bookQty === 0) row.bookQty = 0
  } catch (e) {
    console.error('[PC Check] load product detail failed', e)
  }
}

async function onSubmit() {
  try { await formRef.value.validate() } catch { return }
  if (form.details.length === 0) {
    ElMessage.warning('请至少添加一行盘点明细')
    return
  }
  submitting.value = true
  try {
    await invCheckApi.add({
      billDate: form.billDate,
      warehouseId: form.warehouseId,
      remark: form.remark,
      checkType: 'PARTIAL',
      details: form.details.map(d => ({
        productId: d.productId,
        productCode: d.productCode,
        productName: d.productName,
        bookQty: d.bookQty || 0,
        actualQty: d.actualQty || 0,
        remark: d.remark
      }))
    })
    ElMessage.success('盘点单已创建')
    addVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadWarehouses()
  loadData()
})
</script>

<style scoped>
.search-bar { padding: 16px; background: #fff; border-radius: 6px; margin-bottom: 12px; }
.page-card { padding: 16px; background: #fff; border-radius: 6px; }
.pager { margin-top: 12px; text-align: right; }
</style>
