<template>
  <div class="page">
    <!-- 页面标题 (对齐 Figma) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产管理 / 成品入库</div>
        <h1 class="xk-title">成品入库</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="openAdd"><el-icon><Plus /></el-icon>新增入库</el-button>
        <el-button @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
        <!-- v1.1.102 打印标签 (多选, 飞鹅云) -->
        <el-button
          v-if="userStore.hasPerm('production:order:feie-print')"
          type="success"
          :loading="batchPrinting"
          :disabled="!selectedRows.length"
          @click="onPrintBatch"
        ><el-icon><Printer /></el-icon>打印标签 ({{ selectedRows.length }})</el-button>
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
    <el-table :data="records" border stripe v-loading="loading" empty-text="暂无成品入库记录" @selection-change="onSelectionChange">
      <!-- v1.1.102 多选列: 勾选后顶部「打印标签」批量发飞鹅 -->
      <el-table-column type="selection" width="45" align="center" />
      <el-table-column type="index" width="50" label="#" />
      <el-table-column prop="billNo" label="入库单号" width="160" />
      <el-table-column prop="billDate" label="入库日期" width="100" />
      <el-table-column prop="prdOrderNo" label="生产工单" width="150">
        <template #default="{ row }">{{ row.prdOrderNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="productName" label="成品" min-width="140">
        <template #default="{ row }">{{ row.productName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="spec" label="规格" width="120">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="100" align="right" />
      <el-table-column prop="batchNo" label="成品批号" width="130">
        <template #default="{ row }">{{ row.batchNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'" size="small" effect="light">
            {{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <template v-if="row.billStatus !== 'CHECKED'">
            <el-button
              v-if="userStore.hasPerm('production:finished-in:check')"
              link type="success" size="small" @click="onCheck(row)"
            >审核</el-button>
            <el-button
              v-if="userStore.hasPerm('production:finished-in:delete')"
              link type="danger" size="small" @click="onDelete(row)"
            >删除</el-button>
          </template>
          <el-button
            v-else-if="userStore.hasPerm('production:finished-in:uncheck')"
            link type="warning" size="small" @click="onUncheck(row)"
          >反审核</el-button>
          <span v-else class="muted">—</span>
          <!-- v1.1.102 单条打印 (飞鹅云, 品名/规格/数量/批号 标签) -->
          <el-button
            v-if="userStore.hasPerm('production:order:feie-print')"
            link type="primary" size="small" @click="onPrintOne(row)"
          >打印</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadList"
        @size-change="onPageSizeChange"
      />
    </div>

    <!-- 新增成品入库弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增成品入库" width="720px" destroy-on-close>
      <el-form :model="form" label-width="90px" class="fi-form">
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
              <el-select v-model="form.warehouseId" placeholder="成品仓" filterable style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName || ('仓#' + w.id)" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="成品">
              <el-select v-model="form.productId" placeholder="请选择成品" filterable style="width:100%" @change="onProductChange">
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
              <el-select v-model="form.unitId" placeholder="成品单位" filterable style="width:100%">
                <el-option v-for="u in units" :key="u.id" :label="u.unitName || ('单位#' + u.id)" :value="u.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <!-- v1.1.100 食品合规 (食品级成品 from base_product, readonly 回显 执行标准/备案/用途/限值) -->
        <el-alert v-if="form.isFoodGrade === 1" type="info" :closable="false" show-icon
          title="食品接触合规" style="margin-bottom:12px"
          description="本成品为食品级, 入库请确认以下合规字段; 关联 OQC 检验单将自动放行." />
        <el-row :gutter="12" v-if="form.isFoodGrade === 1">
          <el-col :span="8"><el-form-item label="执行标准号"><el-input :model-value="form.foodExecStd" readonly placeholder="未登记" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="备案编号"><el-input :model-value="form.foodFilingNo" readonly placeholder="未登记" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="食品级用途"><el-input :model-value="form.foodUseDesc" readonly placeholder="未登记" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12" v-if="form.isFoodGrade === 1">
          <el-col :span="8"><el-form-item label="总迁移量限值">
            <el-input :model-value="form.foodMigrationLimit != null ? (form.foodMigrationLimit + ' mg/dm²') : '未登记'" readonly />
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="入库数量">
              <el-input-number v-model="form.qty" :min="0" :precision="3" :step="1" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="成品批号">
              <el-input v-model="form.batchNo" placeholder="留空则自动生成" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存为草稿</el-button>
      </template>
    </el-dialog>

    <!-- 经营 AI 助手 -->
    <div class="ai-card">
      <div class="ai-left">
        <el-icon :size="22" color="var(--primary-color)"><ChatDotRound /></el-icon>
        <div>
          <b>经营 AI 助手</b>
          <small>可以询问「本月成品入库合计」「哪些批号已入库未审核」「食品袋产量趋势」</small>
        </div>
      </div>
      <el-button text type="primary" @click="router.push('/report/ai')">
        开始提问 <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Download, ChatDotRound, ArrowRight, Plus, Printer
} from '@element-plus/icons-vue'
import { prdFinishedInApi, prdOrderApi } from '@/api/production'
import { feiePrintApi } from '@/api/feie'
import { warehouseApi, unitApi, productApi } from '@/api/base'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const query = ref('')
// v1.1.102 打印标签 (飞鹅云, 品名/规格/数量/批号)
const selectedRows = ref([])
const batchPrinting = ref(false)
const printing = ref(false)

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 单条打印 (审核/反审核按钮旁)
async function onPrintOne(row) {
  printing.value = true
  try {
    await feiePrintApi.print('FINISHED_IN', row.id)
    ElMessage.success(`已打印 ${row.billNo}`)
  } catch (e) {
    ElMessage.error((e && e.msg) || '打印失败')
  } finally {
    printing.value = false
  }
}

// 批量打印 (勾选多行 → 顶部「打印标签」)
async function onPrintBatch() {
  if (!selectedRows.value.length) return
  batchPrinting.value = true
  try {
    const ids = selectedRows.value.map(r => r.id)
    const r = await feiePrintApi.printBatch('FINISHED_IN', ids)
    const data = (r && r.data) || {}
    const failed = data.failed || 0
    if (failed === 0) {
      ElMessage.success(`已打印 ${data.success ?? ids.length} 张标签`)
    } else {
      ElMessage.warning(`打印完成: 成功 ${data.success ?? 0}, 失败 ${failed}`)
    }
  } catch (e) {
    ElMessage.error((e && e.msg) || '批量打印失败')
  } finally {
    batchPrinting.value = false
  }
}

async function loadList() {
  loading.value = true
  try {
    const r = await prdFinishedInApi.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      billNo: query.value || undefined
    })
    if (r.code === 200) {
      records.value = r.data?.records || []
      total.value = Number(r.data?.total) || 0
    }
  } catch (e) {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.value = ''
  pageNum.value = 1
  loadList()
}

function onPageSizeChange(s) { pageSize.value = s; pageNum.value = 1; loadList() }

async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核入库单 ${row.billNo}? 审核将同步成品入库并登记批次。`, '审核确认', {
      type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消'
    })
  } catch { return }
  try {
    await prdFinishedInApi.check(row.id)
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
    await prdFinishedInApi.uncheck(row.id)
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
    await prdFinishedInApi.delete(row.id)
    ElMessage.success('删除成功')
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function onExport() { ElMessage.info('导出功能接入中') }

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
    billDate: new Date().toISOString().slice(0, 10),  // 入库日期默认今日
    warehouseId: null,
    productId: null, spec: '', unitId: null,
    qty: 0, batchNo: '', remark: '',
    // v1.1.100 食品合规 (从 base_product 带出, readonly 仅食品级成品显示)
    isFoodGrade: 0, foodExecStd: '', foodFilingNo: '', foodUseDesc: '', foodMigrationLimit: null
  }
}

async function loadOptions() {
  const [o, w, p, u] = await Promise.all([
    prdOrderApi.page({ pageNum: 1, pageSize: 200 }),
    warehouseApi.list(),
    productApi.page({ pageNum: 1, pageSize: 200 }),
    unitApi.list()
  ])
  orders.value = o.data?.records || []
  // 全仓库可选 (成品入库可指定任意仓), 但默认仍选中成品仓 (warehouseType=FG)
  warehouses.value = (w.data || []).filter(x => x.status === 1)
  products.value = p.data?.records || []
  units.value = u.data || []
}

function openAdd() {
  form.value = emptyForm()
  loadOptions().then(() => {
    // 入库仓库默认选成品仓 (warehouseType=FG); 无成品仓则回退第一个
    const fg = warehouses.value.find(x => x.warehouseType === 'FG')
    if (!form.value.warehouseId) form.value.warehouseId = (fg || warehouses.value[0] || {}).id
  })
  dialogVisible.value = true
}

// 选中工单 → 带出 成品/规格/单位/数量/仓库/日期
function onOrderChange(orderId) {
  const o = orders.value.find(x => x.id === orderId)
  if (!o) return
  form.value.productId = o.productId || null
  form.value.spec = o.spec || ''
  // 单位: 工单带出的 unitId 必须是有效 id (>0 且在 units 列表) — 否则走主单位逻辑
  if (o.unitId && units.value.some(u => u.id === o.unitId)) {
    form.value.unitId = o.unitId
  } else {
    form.value.unitId = null
    if (form.value.productId) onProductChange(form.value.productId)  // 异步取主单位
  }
  form.value.qty = o.planQty ?? o.actualQty ?? 0
  // 仓库: 工单带出的 warehouseId 有效 (>0 且在仓库列表) 则采用 — 全仓库可选
  if (o.warehouseId && warehouses.value.some(w => w.id === o.warehouseId)) {
    form.value.warehouseId = o.warehouseId
  }
  form.value.billDate = o.endDate || form.value.billDate
}

// 选中成品 → 拉详情取规格 + 主单位 (与 Order.vue onProductChange 同模式)
async function onProductChange(productId) {
  const p = products.value.find(x => x.id === productId)
  if (p && p.spec) form.value.spec = p.spec
  if (!productId) { form.value.unitId = null; return }
  try {
    const r = await productApi.detail(productId)
    const data = r.data || {}
    const prod = data.product || {}
    if (prod.spec) form.value.spec = prod.spec
    // v1.1.100 食品合规 5 字段回填 (食品级成品入库需带出)
    form.value.isFoodGrade = prod.isFoodGrade || 0
    form.value.foodExecStd = prod.foodExecStd || ''
    form.value.foodFilingNo = prod.foodFilingNo || ''
    form.value.foodUseDesc = prod.foodUseDesc || ''
    form.value.foodMigrationLimit = prod.foodMigrationLimit != null ? prod.foodMigrationLimit : null
    // 取主单位 (下拉 option 的 :value = base_unit.id), 不是 base_product_unit.id
    // 优先级: 1) 关联记录 isMain=1 的 unitId; 2) prod.mainUnitId; 3) prod.unitId
    //
    // ⚠️⚠️ 别用 `x.unitId > 0` 过滤 (v1.1.117 hotfix-2 实测踩坑):
    //    base_product_unit 有 16/174 行 unit_id = **0** (不是 NULL) — 历史脏数据,
    //    unitName 是真实的 (如「公斤」), 只是 unit_id 指向一条不存在的 base_unit.
    //    `x.unitId > 0` 把这些行排掉 → find 落空 → 静默 fallback 到 `units.value[0].id`,
    //    那是 base_unit 全表第一条 (kg), 于是「带鱼袋」被默默选成 kg 而不是「公斤」——
    //    **不报错, 只是单位错了**, 比报错更难发现.
    //    判定只认 unitName 非空; unitId=0 与 null 同等对待 (都有值就用).
    let mainUnitId = null
    const usable = (data.units || []).filter(x => x.unitName && String(x.unitName).trim())
    const mainUnit = usable.find(x => x.isMain === 1 || x.is_main === 1)
    if (mainUnit) mainUnitId = mainUnit.unitId
    else if (usable.length) mainUnitId = usable[0].unitId
    else if (prod.mainUnitId && Number(prod.mainUnitId) > 0) mainUnitId = prod.mainUnitId
    else if (prod.unitId && Number(prod.unitId) > 0) mainUnitId = prod.unitId
    // 兜底: 全表 units 里第一条 (避免 0/undefined) — 商品彻底没配单位时才走到
    if (!mainUnitId && units.value.length) mainUnitId = units.value[0].id
    form.value.unitId = mainUnitId || null
    if (import.meta.env.DEV) {
      console.log('[FI] onProductChange', productId, 'isMainUnit=', mainUnit, 'mainUnitId=', mainUnitId)
    }
  } catch (e) {
    if (import.meta.env.DEV) console.warn('[FI] onProductChange 拉详情失败', e)
    if (!form.value.unitId || form.value.unitId === 0) form.value.unitId = null
  }
}

async function onSubmit() {
  if (!form.value.productId) { ElMessage.warning('请选择成品'); return }
  if (!form.value.qty || form.value.qty <= 0) { ElMessage.warning('请填写入库数量'); return }
  submitting.value = true
  try {
    // 带出关联展示字段 (后端 add 落库)
    const order = orders.value.find(x => x.id === form.value.prdOrderId)
    const product = products.value.find(x => x.id === form.value.productId)
    const payload = {
      ...form.value,
      prdOrderNo: order?.billNo || null,
      productCode: product?.productCode || null,
      productName: product?.productName || null,
      unitName: units.value.find(u => u.id === form.value.unitId)?.unitName || null
    }
    await prdFinishedInApi.add(payload)
    ElMessage.success('保存成功, 可在列表审核')
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
.fi-form :deep(.el-form-item) { margin-bottom: 10px; }

.ai-card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: linear-gradient(90deg, #eef6ff, #f8fbff);
  border: 1px solid #bfd8f2;
  border-radius: 14px;
  padding: 18px 20px;
}
.ai-left { display: flex; align-items: center; gap: 14px; }
.ai-left b { display: block; font-size: 14px; font-weight: 700; color: #245986; }
.ai-left small { display: block; font-size: 11px; color: #5c7690; margin-top: 3px; }
</style>
