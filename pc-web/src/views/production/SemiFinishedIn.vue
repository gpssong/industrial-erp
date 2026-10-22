<template>
  <div class="page">
    <!-- 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产管理 / 半成品入库</div>
        <h1 class="xk-title">半成品入库</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="openAdd"><el-icon><Plus /></el-icon>新增入库</el-button>
      </div>
    </div>

    <!-- 搜索条 -->
    <div class="search-bar">
      <el-input
        v-model="query"
        placeholder="搜索入库单号 / 生产工单 / 入库人"
        clearable
        style="width: 320px"
        @keyup.enter="loadList"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="records" border stripe v-loading="loading" empty-text="暂无半成品入库记录">
      <el-table-column type="index" width="50" label="#" />
      <el-table-column prop="billNo" label="入库单号" width="160" />
      <el-table-column prop="billDate" label="入库日期" width="100" />
      <el-table-column prop="prdOrderNo" label="生产工单" width="150">
        <template #default="{ row }">{{ row.prdOrderNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="productName" label="半成品" min-width="140">
        <template #default="{ row }">{{ row.productName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="spec" label="规格" width="120">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="100" align="right" />
      <el-table-column prop="batchNo" label="半成品批号" width="130">
        <template #default="{ row }">{{ row.batchNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="FAI 状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.inspectionResult" :type="row.inspectionResult === 'PASS' || row.inspectionResult === 'PASS_DISPOSITION' ? 'success' : 'danger'" size="small" effect="light">
            {{ row.inspectionResult }}
          </el-tag>
          <span v-else class="muted">未首检</span>
        </template>
      </el-table-column>
      <el-table-column label="FAI 首检单号" width="140">
        <template #default="{ row }">
          <span v-if="row.inspectionReportNo">{{ row.inspectionReportNo }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="首检人" width="90">
        <template #default="{ row }">{{ row.inspectorName || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'" size="small" effect="light">
            {{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <template v-if="row.billStatus !== 'CHECKED'">
            <el-tooltip v-if="!faiPassed(row)" placement="top"
              content="首检 (FAI) 未完成或不合格, 请先在「质量与 XK 检查」完成首检并判定合格">
              <el-button
                v-if="userStore.hasPerm('production:semi-in:check')"
                link type="info" size="small" disabled
              >审核 (先首检)</el-button>
            </el-tooltip>
            <el-button
              v-if="faiPassed(row) && userStore.hasPerm('production:semi-in:check')"
              link type="success" size="small" @click="onCheck(row)"
            >审核</el-button>
            <el-button
              v-if="userStore.hasPerm('production:semi-in:delete')"
              link type="danger" size="small" @click="onDelete(row)"
            >删除</el-button>
          </template>
          <el-button
            v-else-if="userStore.hasPerm('production:semi-in:uncheck')"
            link type="warning" size="small" @click="onUncheck(row)"
          >反审核</el-button>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        :current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="pageNumOnSizeChange"
        @current-change="pageNumOnPageChange"
      />
    </div>

    <!-- 新增半成品入库弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增半成品入库" width="720px" destroy-on-close>
      <el-form :model="form" label-width="90px" class="si-form">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="生产工单">
              <el-select v-model="form.prdOrderId" placeholder="请选择工单" filterable style="width:100%" @change="onOrderChange">
                <el-option v-for="o in orders" :key="o.id" :label="o.billNo" :value="o.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库日期">
              <el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库仓库">
              <el-select v-model="form.warehouseId" placeholder="半成品仓" filterable style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName || ('仓#' + w.id)" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="半成品">
              <el-select v-model="form.productId" placeholder="请选择半成品" filterable style="width:100%" @change="onProductChange">
                <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="规格">
              <el-input v-model="form.spec" placeholder="规格" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位">
              <el-select v-model="form.unitId" placeholder="半成品单位" filterable style="width:100%">
                <el-option v-for="u in units" :key="u.id" :label="u.unitName || ('单位#' + u.id)" :value="u.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="入库数量">
              <el-input-number v-model="form.qty" :min="0" :precision="3" :step="1" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="半成品批号">
              <el-input v-model="form.batchNo" placeholder="留空则自动生成" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注" />
        </el-form-item>
        <div class="gate-hint">
          <el-alert type="warning" :closable="false" show-icon title="XK 门禁: 半成品入库须先有合格 FAI 检验单"
            description="审核前请先在「质量与 XK 检查」新建/关联该半成品的 FAI 检验并判定合格 (PASS), 否则拒绝入库。" />
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存为草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { prdSemiInApi, prdOrderApi } from '@/api/production'
import { warehouseApi, unitApi, productApi } from '@/api/base'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const query = ref('')

async function loadList() {
  loading.value = true
  try {
    const r = await prdSemiInApi.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      billNo: query.value || undefined
    })
    if (r.code === 200) {
      records.value = r.data?.records || []
      total.value = r.data?.total || 0
    }
  } catch (e) {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function pageNumOnPageChange(p) { if (p === pageNum.value) return; pageNum.value = p; loadList() }
function pageNumOnSizeChange(s) { if (s === pageSize.value) return; pageSize.value = s; pageNum.value = 1; loadList() }

function resetQuery() {
  query.value = ''
  pageNum.value = 1
  loadList()
}

// v1.1.100 首检门禁: 关联 FAI 是否合格 (PASS/PASS_DISPOSITION) 才放行入库
function faiPassed(row) {
  return row.inspectionResult === 'PASS' || row.inspectionResult === 'PASS_DISPOSITION'
}

async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核入库单 ${row.billNo}? XK 门禁: 须已关联合格 FAI 检验单。`, '审核确认', {
      type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消'
    })
  } catch { return }
  try {
    await prdSemiInApi.check(row.id)
    ElMessage.success('审核成功')
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核入库单 ${row.billNo}? 将回退入库库存并回到草稿。`, '反审核确认', {
      type: 'warning', confirmButtonText: '确认反审核', cancelButtonText: '取消'
    })
  } catch { return }
  try {
    await prdSemiInApi.uncheck(row.id)
    ElMessage.success('反审核成功')
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '反审核失败')
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除入库单 ${row.billNo}?`, '删除确认', {
      type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消'
    })
  } catch { return }
  try {
    await prdSemiInApi.delete(row.id)
    ElMessage.success('删除成功')
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// ===== 新增入库 =====
const dialogVisible = ref(false)
const submitting = ref(false)
const orders = ref([])
const warehouses = ref([])
const products = ref([])
const units = ref([])
const form = ref({})

function emptyForm() {
  return {
    prdOrderId: null,
    billDate: new Date().toISOString().slice(0, 10),
    warehouseId: null,
    productId: null, spec: '', unitId: null,
    qty: 0, batchNo: '', remark: ''
  }
}

async function loadOptions() {
  const [o, w, p, u] = await Promise.all([
    prdOrderApi.page({ pageNum: 1, pageSize: 200 }),
    warehouseApi.list(),
    // 半成品: 优先过滤 productType=SEMI; 为空则回退全量 (兼容未分类型的老数据)
    productApi.page({ pageNum: 1, pageSize: 200, productType: 'SEMI' }),
    unitApi.list()
  ])
  orders.value = o.data?.records || []
  warehouses.value = (w.data || []).filter(x => x.status === 1)
  let semiProducts = p.data?.records || []
  if (!semiProducts.length) {
    const all = await productApi.page({ pageNum: 1, pageSize: 200 })
    semiProducts = all.data?.records || []
  }
  products.value = semiProducts
  units.value = u.data || []
}

function openAdd() {
  form.value = emptyForm()
  loadOptions().then(() => {
    // 入库仓库默认选半成品仓 (warehouseType=SEMI); 无则回退第一个
    const semi = warehouses.value.find(x => x.warehouseType === 'SEMI')
    if (!form.value.warehouseId) form.value.warehouseId = (semi || warehouses.value[0] || {}).id
  })
  dialogVisible.value = true
}

// 选中工单 → 带出 半成品/规格/单位/数量/仓库/日期
function onOrderChange(orderId) {
  const o = orders.value.find(x => x.id === orderId)
  if (!o) return
  form.value.productId = o.productId || null
  form.value.spec = o.spec || ''
  if (o.unitId && units.value.some(u => u.id === o.unitId)) {
    form.value.unitId = o.unitId
  } else {
    form.value.unitId = null
    if (form.value.productId) onProductChange(form.value.productId)
  }
  form.value.qty = o.planQty ?? o.actualQty ?? 0
  if (o.warehouseId && warehouses.value.some(w => w.id === o.warehouseId)) {
    form.value.warehouseId = o.warehouseId
  }
  form.value.billDate = o.endDate || form.value.billDate
}

// 选中半成品 → 拉详情取规格 + 主单位
async function onProductChange(productId) {
  const p = products.value.find(x => x.id === productId)
  if (p && p.spec) form.value.spec = p.spec
  if (!productId) { form.value.unitId = null; return }
  try {
    const r = await productApi.detail(productId)
    const data = r.data || {}
    const prod = data.product || {}
    if (prod.spec) form.value.spec = prod.spec
    let mainUnitId = null
    const mainUnit = (data.units || []).find(x => (x.isMain === 1 || x.is_main === 1) && x.unitId > 0)
    if (mainUnit) mainUnitId = mainUnit.unitId
    else if (prod.mainUnitId && Number(prod.mainUnitId) > 0) mainUnitId = prod.mainUnitId
    else if (prod.unitId && Number(prod.unitId) > 0) mainUnitId = prod.unitId
    if (!mainUnitId && units.value.length) mainUnitId = units.value[0].id
    form.value.unitId = mainUnitId || null
  } catch (e) {
    if (import.meta.env.DEV) console.warn('[SI] onProductChange 拉详情失败', e)
    if (!form.value.unitId || form.value.unitId === 0) form.value.unitId = null
  }
}

async function onSubmit() {
  if (!form.value.productId) { ElMessage.warning('请选择半成品'); return }
  if (!form.value.qty || form.value.qty <= 0) { ElMessage.warning('请填写入库数量'); return }
  submitting.value = true
  try {
    const order = orders.value.find(x => x.id === form.value.prdOrderId)
    const product = products.value.find(x => x.id === form.value.productId)
    const payload = {
      ...form.value,
      prdOrderNo: order?.billNo || null,
      productCode: product?.productCode || null,
      productName: product?.productName || null,
      unitName: units.value.find(u => u.id === form.value.unitId)?.unitName || null
    }
    await prdSemiInApi.add(payload)
    ElMessage.success('保存成功, 审核前请先完成合格 FAI 检验')
    dialogVisible.value = false
    pageNum.value = 1
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadList)
</script>

<style scoped lang="scss">
.page { display: flex; flex-direction: column; gap: 16px; }
.search-bar { display: flex; gap: 8px; align-items: center; }
.pager { display: flex; justify-content: flex-end; }
.muted { color: var(--muted, #6f7d90); }
.si-form :deep(.el-form-item) { margin-bottom: 10px; }
.gate-hint { margin-top: 4px; }
</style>
