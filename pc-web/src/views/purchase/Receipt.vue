<template>
  <div class="xk-receipt">
    <!-- ===== 顶部: 标题 + Tab + 操作按钮 ===== -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">供应链 / 采购入库</div>
        <h1 class="xk-title">采购入库</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button v-if="tab === 'form'" type="primary" plain @click="saveRecordOnly"><el-icon><Document /></el-icon>保存检验记录</el-button>
        <el-button v-if="tab === 'form'" type="primary" @click="onSignAndInbound" :loading="submitting"><el-icon><Check /></el-icon>生成验放单并入库</el-button>
        <el-button v-if="tab === 'list'" type="primary" @click="openForm"><el-icon><Plus /></el-icon>新增入库</el-button>
        <el-button @click="loadData">查询</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="xk-tabs">
      <!-- ============ Tab 1: XK 检验入库表单 (整页, 对齐 Figma) ============ -->
      <el-tab-pane label="新增采购入库" name="form" lazy>
        <div class="xk-layout">
          <!-- 左侧主列 -->
          <div class="xk-main">
            <!-- XK 合规说明条 -->
            <div class="xk-compliance-strip">
              <el-icon class="xk-cs-icon"><Promotion /></el-icon>
              <div class="xk-cs-body">
                <strong>{{ allLinesNonFood ? '非食品采购 (免检直通)' : 'XK 生产许可受控记录' }}</strong>
                <small v-if="allLinesNonFood">本单全部行非食品级 — 审核免 IQC 来料检验、免供应商合格名录门禁, 直接入库 (库存/应付照记)</small>
                <small v-else>采购必须来源于审批计划；原料到货后先检验并上传供应商报告，检验合格方可生成入库单 · 标签照片随批归档 ≥ 2 年</small>
              </div>
              <span class="xk-cs-badge" :class="{ 'xk-cs-badge-off': allLinesNonFood }">{{ allLinesNonFood ? '免检直通' : 'XK 受控' }}</span>
            </div>

            <!-- 顶部三步进度条 (视觉) -->
            <div class="xk-flow">
              <div class="xk-flow-item" :class="{ active: flowStep === 0, done: flowStep > 0 }">
                <div class="xk-flow-dot"><el-icon><Camera /></el-icon></div>
                <div class="xk-flow-txt"><b>到货验收</b><small>拍照记录到货原始标签</small></div>
              </div>
              <div class="xk-flow-line" :class="{ done: flowStep > 0 }"></div>
              <div class="xk-flow-item" :class="{ active: flowStep === 1, done: flowStep > 1 }">
                <div class="xk-flow-dot"><el-icon><Document /></el-icon></div>
                <div class="xk-flow-txt"><b>到货与原料检验</b><small>核对标签与检验数据</small></div>
              </div>
              <div class="xk-flow-line" :class="{ done: flowStep > 1 }"></div>
              <div class="xk-flow-item" :class="{ active: flowStep === 2, done: flowStep > 2 }">
                <div class="xk-flow-dot"><el-icon><CircleCheck /></el-icon></div>
                <div class="xk-flow-txt"><b>自动入库</b><small>生成验放单</small></div>
              </div>
            </div>

            <!-- LLDPE 7042 原料检验记录 合格记录卡 -->
            <div class="xk-record-card" v-if="form.orderNo || form.supplierName">
              <div class="xk-record-head">
                <div class="xk-record-title">
                  <b>采购单 / 到货检验记录</b>
                  <el-button v-if="form.orderNo || form.supplierName" link type="info" size="small" @click="clearRecord">清除</el-button>
                </div>
                <el-tag v-if="supplierQualified === false" type="danger" effect="plain">供应商暂停供货</el-tag>
                <el-tag v-else type="success" effect="plain">检验合格</el-tag>
              </div>
              <div class="xk-record-line">
                <span class="xk-record-name">LLDPE 7042</span>
                <span class="xk-record-sub">采购单号: {{ form.orderNo || '—' }}</span>
                <span class="xk-record-sub">交货批次: {{ batchNo }}</span>
                <span class="xk-record-sub">关联采购订单号: CCL-A-{{ form.supplierCode || '—' }}</span>
              </div>
            </div>

            <!-- 到货基本信息 -->
            <div class="xk-section">
              <div class="xk-section-title">到货基本信息</div>
              <el-form :model="form" label-width="140px" class="xk-form">
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="供应商 / 到货来源">
                      <el-select v-model="form.supplierId" filterable style="width:100%" @change="onSupplierChange">
                        <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="合同 / 采购单号">
                      <el-input v-model="form.orderNo" placeholder="CCL-A-2025-05-153" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="关联采购订单">
                      <el-select v-model="form.orderId" filterable clearable style="width:100%" placeholder="可选 — 带入供应商/单号/明细" @change="onOrderChange">
                        <el-option v-for="o in orders" :key="o.id" :label="`${o.billNo} ${o.supplierName||''}`" :value="o.id" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="仓库">
                      <el-select v-model="form.warehouseId" style="width:100%">
                        <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8"><el-form-item label="验收日期"><el-date-picker v-model="form.billDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
                  <el-col :span="8"><el-form-item label="到货数量"><el-input-number v-model="form.totalQty" :min="0" :precision="4" :step-strictly="false" style="width:100%" /></el-form-item></el-col>
                  <el-col :span="8"><el-form-item label="收货仓库"><el-input :model-value="warehouseName" readonly placeholder="自动带出" /></el-form-item></el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8"><el-form-item label="抽检数量"><el-input v-model="inspectForm.sampleQty" placeholder="5 袋 / 1 kg" /></el-form-item></el-col>
                  <el-col :span="8"><el-form-item label="检验报告号"><el-input v-model="inspectForm.reportNo" placeholder="BG-YL-0418-31" /></el-form-item></el-col>
                  <el-col :span="8"><el-form-item label="检验人员"><el-input v-model="inspectForm.inspector" placeholder="李敏" /></el-form-item></el-col>
                </el-row>
              </el-form>
            </div>

            <!-- 检验报告 · 采购合同 · 发票 -->
            <div class="xk-section">
              <div class="xk-section-title">
                检验报告、采购合同与发票
                <el-button type="primary" plain size="small" @click="triggerReportUpload"><el-icon><Plus /></el-icon>上传文件</el-button>
                <input ref="reportInput" type="file" multiple accept=".pdf,.jpg,.jpeg,.png" style="display:none" @change="onReportPick" />
              </div>
              <div class="xk-file-tip">支持 PDF、JPG、PNG；三类文件均与采购批次强绑定，提交后不可单独删除</div>
              <div class="xk-file-list">
                <div v-for="(f, i) in inspectForm.reportFiles" :key="i" class="xk-file-row">
                  <el-icon><Document /></el-icon>
                  <div class="xk-file-meta">
                    <div class="xk-file-name">{{ f.name }}</div>
                    <div class="xk-file-sub" v-if="f.url">已上传 · 点击查看</div>
                  </div>
                  <el-button v-if="f.url" link type="primary" @click="openFile(f.url)">查看</el-button>
                  <el-button v-if="!savingRecords" link type="danger" @click="inspectForm.reportFiles.splice(i,1)">删除</el-button>
                </div>
                <div v-if="!inspectForm.reportFiles.length" class="xk-empty">暂无文件，点击上方「上传文件」添加</div>
              </div>
            </div>

            <!-- 检验项目判定表 -->
            <div class="xk-section">
              <div class="xk-qa-table">
                <div class="xk-qa-head">
                  <span>检验项目</span><span>技术要求</span><span>检验方法</span><span>实际/检验结果</span><span>判定</span>
                </div>
                <div class="xk-qa-row" v-for="q in qaChecks" :key="q.item">
                  <span>{{ q.item }}</span><span>{{ q.req }}</span><span>{{ q.method }}</span><span>{{ q.result }}</span>
                  <el-check-tag :checked="q.ok" @change="v => toggleQaOk(q, v)" type="success">{{ q.ok ? '合格' : '不合格' }}</el-check-tag>
                </div>
              </div>
            </div>

            <!-- 原料标签 / 批次抽样照片 -->
            <div class="xk-section">
              <div class="xk-section-title">
                原料标签、批次抽样照片
                <el-tag size="small" type="info" effect="plain">{{ inspectForm.labelPhotos.length }} / 6</el-tag>
              </div>
              <div class="xk-photo-tip">至少拍摄成品到货包装标签 1 张，建议 6 张以上</div>
              <div class="xk-photo-row">
                <div class="xk-photo-item" v-for="(p, i) in inspectForm.labelPhotos" :key="i" @click="onPhotoPreview(p)">
                  <img :src="p.url" v-if="p.url" />
                  <span v-else class="xk-photo-badge">📷 {{ p.name }}</span>
                  <span class="xk-photo-x" v-if="!savingRecords" @click.stop="inspectForm.labelPhotos.splice(i,1)">×</span>
                </div>
                <div class="xk-photo-add" @click="triggerPhotoUpload">
                  <el-icon><Plus /></el-icon>
                  <div>拍照 / 上传<br><small>JPG / PNG</small></div>
                </div>
                <input ref="photoInput" type="file" accept="image/*" multiple style="display:none" @change="onPhotoPick" />
              </div>
              <div class="xk-photo-tip" v-if="!inspectForm.labelPhotos.length">📷 至少 1 张原始到货标签照片，不可仅用供应商报告替代</div>
            </div>
          </div>

          <!-- 右侧: 综合判定面板 -->
          <aside class="xk-side">
            <div class="xk-verdict-card">
              <div class="xk-verdict-icon" :style="{ color: allQaOk ? '#67c23a' : '#f56c6c' }"><el-icon :size="34"><CircleCheck v-if="allQaOk" /><CircleClose v-else /></el-icon></div>
              <div style="flex:1; min-width:0">
                <div class="xk-verdict-label">综合判定：
                  <el-check-tag :checked="allQaOk" @change="toggleAllQa" type="success">{{ allQaOk ? '合格' : '不合格' }}</el-check-tag>
                </div>
                <small>{{ allQaOk ? '综合外观、密度、熔融指数、杂质等指标与批次报告，可放行动入库' : '存在不合格项，按 XK 要求本批原料不予放行动入库, 需退供应商或处置' }}</small>
              </div>
            </div>
            <div class="xk-kv">
              <div><small>检验日期</small><b>{{ form.billDate || '—' }}</b></div>
              <div><small>判定人员</small><b>{{ inspectForm.inspector || '—' }}</b></div>
              <div><small>判定结论</small><b :class="allQaOk ? 'xk-verdict-ok' : 'xk-verdict-bad'">{{ allQaOk ? (qaChecks.length + ' 项关键项合格') : ((qaChecks.filter(q=>!q.ok).length) + ' 项关键项不合格') }}</b></div>
            </div>
            <div class="xk-note">综合外观、密度、熔融指数等指标，均可依据批次判定为合格。</div>
          </aside>
        </div>
      </el-tab-pane>

      <!-- ============ Tab 2: 单据列表 ============ -->
      <el-tab-pane label="单据列表" name="list" lazy>
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
            <el-table-column label="XK 判定" width="110" align="center">
              <template #default="{ row }">
                <el-tooltip :content="row.xkExempt === 1 ? '全部行非食品级 (is_food_grade≠1), 审核免 IQC 直接入库' : '含食品级行, 审核需合格 IQC 来料检验'" placement="top">
                  <el-tag v-if="row.xkExempt === 1" type="success" effect="plain" size="small">免检直通</el-tag>
                  <el-tag v-else-if="row.xkExempt === 0" type="warning" effect="plain" size="small">需 IQC</el-tag>
                  <el-tag v-else type="info" effect="plain" size="small">—</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="IQC 判定" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.inspectionResult === 'PASS' || row.inspectionResult === 'PASS_DISPOSITION'" type="success" size="small">合格</el-tag>
                <el-tag v-else-if="row.inspectionResult" type="danger" size="small">{{ row.inspectionResult }}</el-tag>
                <el-tag v-else type="info" size="small">未检验</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalQty" label="数量" width="100" align="right" />
            <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
            <el-table-column prop="createByName" label="操作员" width="100">
              <template #default="{ row }">{{ row.createByName || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }"><el-tag :type="row.billStatus==='CHECKED'?'success':'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('purchase:receipt:edit')" link type="primary" @click="onEdit(row)">编辑</el-button>
                <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('purchase:receipt:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
                <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('purchase:receipt:check')" link type="success" @click="onCheck(row)">审核</el-button>
                <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('purchase:receipt:uncheck')" link type="warning" @click="onUncheck(row)">反审核</el-button>
                <el-button link type="primary" @click="onAiAnalyze(row)">🤖 AI 解读</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
            :total="Number(data.total)" :current-page="query.pageNum" :page-size="query.pageSize"
            @current-change="queryOnPageChange" @size-change="queryOnSizeChange" :page-sizes="[10,20,50,100]" />
        </div>
      </el-tab-pane>
    </el-tabs>

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
    <!-- v1.1.75 AI 解读弹窗 -->
    <el-dialog v-model="aiDialogVisible" title="🤖 AI 单据解读" width="720px" destroy-on-close>
      <div class="ai-meta" v-if="aiBillNo || aiModel">
        <span v-if="aiBillNo">单号: <b>{{ aiBillNo }}</b></span>
        <span v-if="aiModel">模型: {{ aiModel }}</span>
      </div>
      <div v-loading="aiLoading" class="ai-body">
        <pre class="ai-content">{{ aiContent || (aiLoading ? '正在分析中，请稍候…' : '') }}</pre>
      </div>
      <template #footer>
        <el-button @click="aiDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { purReceiptApi, purOrderApi, aiApi } from '@/api/purchase'
import { supplierApi, warehouseApi, productApi } from '@/api/base'
import { inspectionApi } from '@/api/quality'
import { uploadFile } from '@/api/workflow'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { usePrint, BIZ_TYPES } from '@/composables/usePrint'
import { feiePrintApi } from '@/api/feie'
import { useUserStore } from '@/store/user'
import { ArrowDown, Plus, Promotion, Document, Check, Search, Camera } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()

// ===== 视图状态 =====
const tab = ref('form')
const suppliers = ref([])
const warehouses = ref([])
const orders = ref([])
const products = ref([])

// ===== XK 表单主数据 (保存为 PUR_RECEIPT) =====
const form = reactive({
  id: null, supplierId: null, supplierName: '', supplierCode: '', warehouseId: null,
  orderId: null, orderNo: '', billDate: new Date().toISOString().slice(0, 10),
  totalQty: 0, details: []
})
// ===== 检验/标签 (XK 受控) =====
const inspectForm = reactive({
  reportNo: '', sampleQty: '', inspector: '', reportFiles: [], labelPhotos: []
})
const savingRecords = ref(false)
const submitting = ref(false)
const receiptIdRef = ref(null)
const reportInput = ref(null)
const photoInput = ref(null)

// 顶部进度条视觉: 由表单填写程度驱动
const flowStep = computed(() => {
  if (form.supplierId && form.details.length && inspectForm.labelPhotos.length) return 1
  if (form.supplierId && form.details.length) return 1
  if (form.supplierId) return 0
  return 0
})

// 列表相关
const query = reactive({ pageNum: 1, pageSize: 20, billNo: '', supplierId: null, productName: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)

// 供应商合格状态 / 仓库名 / 批次号
const supplierObj = computed(() => suppliers.value.find(s => s.id === form.supplierId) || {})
const supplierQualified = computed(() => (supplierObj.value.status ?? 1) === 1)
const warehouseName = computed(() => warehouses.value.find(w => w.id === form.warehouseId)?.warehouseName || '')
const batchNo = computed(() => `CB-${(form.billDate || '').slice(0, 7)}-00${form.details.length || 1}`)

// v1.1.122: 当前草稿明细是否全非食品级 (is_food_grade≠1). 口径与后端 check() 的 anyFood 一致.
// true → 审核免 IQC / 免供应商门禁, 走「免检直通」; false (含食品级或无明细) → 走 XK 三步.
const allLinesNonFood = computed(() => {
  const ids = (form.details || []).map(d => d.productId).filter(Boolean)
  if (!ids.length) return false
  return ids.every(pid => {
    const p = products.value.find(x => String(x.id) === String(pid))
    return !p || p.isFoodGrade !== 1
  })
})

// ===== 检验项目判定表 (XK 受控展示, 判定可点击 合格↔不合格) =====
const qaChecks = reactive([
  { item: '外观与感官', req: '颗粒均匀、无杂质、无异物混入', method: '目测/感官', result: '符合', ok: true },
  { item: '熔指密度', req: '2.1 – 2.5 g/10min', method: 'MFR 密度测试', result: '2.18', ok: true },
  { item: '熔融质量流动速率', req: '2.0 ± 0.5 g/10min', method: '熔融指数仪', result: '2.18', ok: true },
  { item: '密度', req: '0.918 ± 0.003 g/cm³', method: '密度法', result: '0.919', ok: true },
  { item: '供应商检验报告', req: '批号一致，检验项目及结论完整', method: '文件核验', result: '已核验', ok: true },
  { item: '采购合同', req: '合同编号、供应商、原料及数量与订单一致', method: '文件核验', result: '已核验', ok: true },
  { item: '采购发票', req: '发票金额及货物信息与合同一致', method: '文件核验', result: '已核验', ok: true }
])
function toggleQaOk(q, checked) { q.ok = checked }
// 综合判定 = 所有关键项均合格
const allQaOk = computed(() => qaChecks.every(q => q.ok))
// 一键切换: 综合判定开关 (合格→全部合格, 不合格→全部不合格)
function toggleAllQa(v) { qaChecks.forEach(q => { q.ok = v }) }

// ===== 加载选项 =====
async function loadSuppliers() {
  if (!suppliers.value.length) suppliers.value = (await supplierApi.list()).data || []
}
async function loadOrders() {
  if (!orders.value.length) orders.value = (await purOrderApi.page({ pageNum: 1, pageSize: 100 })).data.records
}

// ===== 联动: 选采购订单 → 带入供应商/单号/仓库/明细 =====
async function onOrderChange(id) {
  if (!id) { form.orderId = null; return }
  const o = orders.value.find(x => x.id === id)
  if (!o) return
  form.supplierId = o.supplierId
  form.orderNo = o.billNo || form.orderNo
  if (o.warehouseId) form.warehouseId = o.warehouseId
  if (o.billDate) form.billDate = o.billDate
  form.totalQty = o.totalQty || 0
  try {
    const detail = await purOrderApi.detail(id)
    const dd = (detail.data && detail.data.details) || []
    form.details = dd.map(d => ({
      orderDetailId: d.id, productId: d.productId, productCode: d.productCode,
      productName: d.productName, spec: d.spec, unitId: d.unitId, unitName: d.unitName,
      qty: d.qty, price: d.price, batchNo: '', locationName: ''
    }))
    if (dd.length) form.totalQty = dd.reduce((s, d) => s + (+d.qty || 0), 0)
  } catch (e) { /* 明细展开失败不阻塞 */ }
}

function onSupplierChange(id) {
  const s = suppliers.value.find(x => x.id === id)
  form.supplierName = s ? s.supplierName : ''
  form.supplierCode = s ? s.supplierCode : ''
}

function clearRecord() {
  form.orderNo = ''; form.supplierName = ''; form.supplierCode = ''; form.orderId = null
}

// ===== 文件上传: 检验报告/合同/发票 =====
function triggerReportUpload() { reportInput.value && reportInput.value.click() }
async function onReportPick(e) {
  for (const raw of e.target.files || []) {
    try {
      const url = await uploadFile(raw)
      inspectForm.reportFiles.push({ name: raw.name, url, size: fmtSize(raw.size) })
    } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  }
  e.target.value = ''
}

// ===== 照片上传: 原料标签/批次抽样 =====
function triggerPhotoUpload() { photoInput.value && photoInput.value.click() }
async function onPhotoPick(e) {
  for (const raw of e.target.files || []) {
    try {
      const url = await uploadFile(raw)
      inspectForm.labelPhotos.push({ name: raw.name, url })
    } catch (err) { ElMessage.error('照片上传失败: ' + (err.message || err)) }
  }
  e.target.value = ''
}
function onPhotoPreview(p) { if (p.url) window.open(p.url, '_blank') }
function openFile(url) { window.open(url, '_blank') }
function fmtSize(n) { if (!n) return ''; return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.ceil(n / 1024) + ' KB' }

// ===== 组装入库 payload =====
function buildPayload() {
  const payload = { ...form, details: form.details }
  let totalAmount = 0
  payload.details.forEach(d => { totalAmount += (+d.qty || 0) * (+d.price || 0) })
  payload.totalAmount = totalAmount
  payload.taxAmount = 0
  payload.totalAmountTax = totalAmount
  payload.totalQty = form.totalQty || 0
  payload.reportFiles = JSON.stringify((inspectForm.reportFiles || []).map(f => f.url).filter(Boolean))
  payload.labelPhotos = JSON.stringify((inspectForm.labelPhotos || []).map(f => f.url).filter(Boolean))
  payload.inspectionReportNo = inspectForm.reportNo || null
  payload.sampleQty = inspectForm.sampleQty || null
  payload.inspectorName = inspectForm.inspector || null
  return payload
}

function validateBase() {
  if (!form.supplierId) return '请选择供应商'
  if (supplierQualified.value === false) return 'XK 合规: 当前供应商「暂停供货」, 不能采购入库; 请到合格供应商名录恢复供货'
  if (!form.warehouseId) return '请选择仓库'
  if (!form.details.length) return '请关联采购订单或添加入库明细'
  return null
}

/** 保存检验记录 (草稿 + IQC 草稿, 不入库) */
async function saveRecordOnly() {
  const err = validateBase()
  if (err) return ElMessage.error(err)
  if (!inspectForm.reportNo) return ElMessage.warning('请填写检验报告号')
  if (!inspectForm.inspector) return ElMessage.warning('请填写检验人员')
  savingRecords.value = true
  try {
    let rid = receiptIdRef.value
    if (form.id) { await purReceiptApi.update(buildPayload()); rid = form.id }
    else { const r = await purReceiptApi.add(buildPayload()); rid = r.data; form.id = rid }
    receiptIdRef.value = rid
    ElMessage.success('检验记录已保存 (草稿, 未入库)。可在「单据列表」审核, 或点击「生成验放单并入库」')
    nextTick()
  } catch (e) {
    ElMessage.error(e.message || '保存检验记录失败')
  } finally { savingRecords.value = false }
}

/** 生成验放单并入库 (签署合格检验 + check 入库, 扣库存/应付) */
async function onSignAndInbound() {
  const err = validateBase()
  if (err) return ElMessage.error(err)
  if (!inspectForm.reportNo) return ElMessage.warning('请填写检验报告号')
  if (!inspectForm.inspector) return ElMessage.warning('请填写检验人员')
  if (!allQaOk) return ElMessage.warning('XK 合规: 综合判定为「不合格」(存在不合格检验项), 不能签署合格并入库; 请退供应商或改判全部合格')
  if (!inspectForm.labelPhotos.length) return ElMessage.warning('请至少上传 1 张原始到货标签照片')
  submitting.value = true
  try {
    let rid = receiptIdRef.value
    if (form.id) { await purReceiptApi.update(buildPayload()); rid = form.id }
    else { const r = await purReceiptApi.add(buildPayload()); rid = r.data; form.id = rid }
    receiptIdRef.value = rid
    await inspectionApi.signReceiptIqc(rid, {
      reportNo: inspectForm.reportNo,
      sampleQty: inspectForm.sampleQty,
      inspector: inspectForm.inspector,
      photoUrls: JSON.stringify((inspectForm.labelPhotos || []).map(f => f.url).filter(Boolean))
    })
    await purReceiptApi.check(rid)
    ElMessage.success('XK 签署检验合格, 已生成验放单并自动入库 (库存 + 应付已更新)')
    tab.value = 'list'
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '生成验放单/入库失败')
  } finally { submitting.value = false }
}

// ===== 单据列表操作 =====
async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.billNo) delete params.billNo
    if (!params.productName) delete params.productName
    data.value = (await purReceiptApi.page(params)).data
  } finally { loading.value = false }
}
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }
function onReset() {
  query.billNo = ''; query.supplierId = null; query.productName = ''; query.pageNum = 1
  loadData()
}
async function openForm() {
  tab.value = 'form'
  await loadOrders()
}
async function onEdit(row) {
  receiptIdRef.value = null
  await loadSuppliers(); await loadOrders()
  warehouses.value = (await warehouseApi.list()).data || []
  products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data.records
  const detail = await purReceiptApi.detail(row.id)
  Object.assign(form, detail.data)
  try {
    const rf = detail.data.reportFiles ? JSON.parse(detail.data.reportFiles) : []
    inspectForm.reportFiles = (Array.isArray(rf) ? rf : [rf]).filter(Boolean).map(u => ({ name: u.split('/').pop() || u, url: u }))
    const lp = detail.data.labelPhotos ? JSON.parse(detail.data.labelPhotos) : []
    inspectForm.labelPhotos = (Array.isArray(lp) ? lp : [lp]).filter(Boolean).map(u => ({ name: u.split('/').pop() || u, url: u }))
  } catch (e) { inspectForm.reportFiles = []; inspectForm.labelPhotos = [] }
  inspectForm.reportNo = detail.data.inspectionReportNo || ''
  inspectForm.inspector = detail.data.inspectorName || ''
  inspectForm.sampleQty = detail.data.sampleQty != null ? String(detail.data.sampleQty) : ''
  tab.value = 'form'
}
async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除入库单 ${row.billNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try { await purReceiptApi.delete(row.id); ElMessage.success('删除成功'); loadData() }
  catch (e) { ElMessage.error(e.message || '删除失败') }
}
async function onCheck(row) {
  // v1.1.122: 全非食品单审核免 IQC, 确认框明说免检直通, 免得操作员以为漏了检验
  const msg = row.xkExempt === 1
    ? `确认审核采购入库单 ${row.billNo}?\n\n本单全部行非食品级 — 免 IQC 来料检验、免供应商合格名录门禁。\n审核后将:\n• 增加库存\n• 更新商品成本\n• 生成应付 (AP → 供应商)\n`
    : `确认审核采购入库单 ${row.billNo}?\n\n本单含食品级行 — 需已合格 IQC 来料检验方可放行 (XK 门禁)。\n审核后将:\n• 增加库存\n• 更新商品成本\n• 生成应付 (AP → 供应商)\n`
  try {
    await ElMessageBox.confirm(msg, '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' })
  } catch { return }
  try { await purReceiptApi.check(row.id); ElMessage.success('审核成功, 库存+成本已更新'); loadData() }
  catch (e) { ElMessage.error(e.message || '审核失败') }
}
async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(
      `确认反审核采购入库单 ${row.billNo}?\n\n此操作仅回退单据状态至「草稿」, 不会自动回退已增加的库存或应付账款.\n`,
      '反审核确认', { type: 'warning', confirmButtonText: '确认反审核', cancelButtonText: '取消' }
    )
  } catch { return }
  try { await purReceiptApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData() }
  catch (e) { ElMessage.error(e.message || '反审核失败') }
}

// ===== 打印 =====
const { doPrint } = usePrint()
const PUR_RECEIPT_HEADER_MAP = {
  billNo: 'billNo', billDate: 'billDate', supplierName: 'supplierName',
  warehouseName: 'warehouseName', totalQty: 'totalQty', totalAmount: 'totalAmount', remark: 'remark'
}
const PUR_RECEIPT_DETAIL_MAP = {
  productCode: 'productCode', productName: 'productName', model: 'model', colorNo: 'colorNo',
  spec: 'spec', unitName: 'unitName', qty: 'qty', price: 'price', amount: 'amount',
  taxRate: 'taxRate', batchNo: 'batchNo', locationName: 'locationName'
}
async function onPrint(row) {
  try {
    const r = await purReceiptApi.detail(row.id)
    await doPrint({
      bizType: BIZ_TYPES.PUR_RECEIPT,
      bill: r.data || {},
      fieldMap: PUR_RECEIPT_HEADER_MAP,
      detailsKey: 'details',
      detailFieldMap: PUR_RECEIPT_DETAIL_MAP,
      customerId: r.data?.supplierId || row.supplierId || undefined
    })
  } catch (e) { ElMessage.error(e.message || '打印失败') }
}

// ===== 飞鹅云打印 =====
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
  } finally { feiePreviewLoading.value = false }
}
async function feieConfirmPrint() {
  if (!feiePendingBillId) return
  feiePrinting.value = true
  try {
    const res = await feiePrintApi.print('PUR_RECEIPT', feiePendingBillId)
    ElMessage.success(res.msg || '打印成功')
    feiePreviewVisible.value = false
  } catch (e) { ElMessage.error('飞鹅打印失败: ' + (e.message || '未知错误')) }
  finally { feiePrinting.value = false }
}
function onPrintCommand(cmd, row) {
  if (cmd === 'browser') return onPrint(row)
  if (cmd === 'feie-preview' || cmd === 'feie-print') return feiePreview('PUR_RECEIPT', row)
}

// ===== v1.1.75 AI 解读 =====
const aiDialogVisible = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const aiModel = ref('')
const aiBillNo = ref('')
async function onAiAnalyze(row) {
  aiBillNo.value = row.billNo || ''
  aiModel.value = ''; aiContent.value = ''
  aiDialogVisible.value = true
  aiLoading.value = true
  try {
    const r = await aiApi.analyzePurReceipt(row.id)
    const d = (r && r.data) || {}
    aiModel.value = d.model || ''
    aiContent.value = d.content || '(模型未返回内容)'
  } catch (e) {
    aiContent.value = '解读失败: ' + ((e && (e.msg || e.message)) || '未知错误')
  } finally { aiLoading.value = false }
}

onMounted(async () => {
  await loadSuppliers()
  warehouses.value = (await warehouseApi.list()).data || []
  products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data.records
  await loadOrders()
  loadData()
})
</script>

<style scoped>
.xk-receipt { display: flex; flex-direction: column; }
.xk-page-heading { display: flex; align-items: flex-end; justify-content: space-between; }
.xk-eyebrow { font-size: 12px; color: var(--muted); margin-bottom: 2px; }
.xk-title { font-size: 22px; font-weight: 700; margin: 0; }
.xk-heading-actions { display: flex; gap: 10px; }
.xk-tabs { margin-top: 8px; }

/* XK 布局 */
.xk-layout { display: flex; gap: 16px; align-items: flex-start; }
.xk-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 14px; }
.xk-side { width: 300px; flex-shrink: 0; position: sticky; top: 8px; display: flex; flex-direction: column; gap: 12px; }

/* XK 合规条 */
.xk-compliance-strip { display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  background: linear-gradient(90deg, var(--primary-soft), #f7fbff);
  border: 1px solid var(--line); border-radius: var(--radius, 10px); }
.xk-cs-icon { color: var(--primary-color); font-size: 22px; }
.xk-cs-body { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.xk-cs-body strong { font-size: 14px; color: var(--text); }
.xk-cs-body small { font-size: 12px; color: var(--muted); line-height: 1.5; }
.xk-cs-badge { font-size: 12px; font-weight: 600; color: var(--primary-color);
  border: 1px solid var(--primary-color); border-radius: 20px; padding: 2px 12px; }
/* v1.1.122: 全非食品采购 — 免检直通态 (绿色, 与「XK 受控」蓝区分) */
.xk-cs-badge-off { color: #67c23a; border-color: #67c23a; }

/* 流程条 */
.xk-flow { display: flex; align-items: center; padding: 14px 20px; background: #fff;
  border: 1px solid var(--line,#eef2f7); border-radius: 12px; }
.xk-flow-item { display: flex; align-items: center; gap: 10px; }
.xk-flow-dot { width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center;
  background: var(--primary-soft,#eef2f8); color: var(--muted); font-size: 18px; transition: .2s; }
.xk-flow-item.active .xk-flow-dot { background: var(--primary-color); color: #fff;
  box-shadow: 0 4px 12px var(--card-shadow,rgba(64,120,255,.35)); }
.xk-flow-item.done .xk-flow-dot { background: #67c23a; color: #fff; }
.xk-flow-txt { display: flex; flex-direction: column; }
.xk-flow-txt b { font-size: 14px; color: var(--text); }
.xk-flow-txt small { font-size: 11px; color: var(--muted); }
.xk-flow-line { flex: 1; height: 2px; background: var(--line,#e6ecf4); margin: 0 14px; border-radius: 2px; }
.xk-flow-line.done { background: #67c23a; }

/* 合格记录横条 */
.xk-record-card { background: #fff; border: 1px solid var(--line,#eef2f7); border-radius: 12px; padding: 14px 18px; }
.xk-record-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.xk-record-title { font-size: 13px; color: var(--muted); display: flex; align-items: center; gap: 8px; }
.xk-record-title b { color: var(--text); font-size: 14px; }
.xk-record-line { display: flex; flex-wrap: wrap; gap: 8px 24px; align-items: center; }
.xk-record-name { font-size: 18px; font-weight: 700; color: var(--primary-color); }
.xk-record-sub { font-size: 12px; color: var(--muted); }

/* section */
.xk-section { background: #fff; border: 1px solid var(--line,#eef2f7); border-radius: 12px; padding: 16px 18px; }
.xk-section-title { font-size: 14px; font-weight: 600; color: var(--text); margin-bottom: 12px;
  display: flex; align-items: center; gap: 10px; }
.xk-form .el-form-item { margin-bottom: 12px; }

/* 文件 */
.xk-file-tip { font-size: 12px; color: var(--muted); margin-bottom: 10px; }
.xk-file-list { display: flex; flex-direction: column; gap: 8px; }
.xk-file-row { display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  background: var(--primary-soft,#f7f9fc); border: 1px solid var(--line,#eef2f7); border-radius: 8px; font-size: 13px; }
.xk-file-row .el-icon { color: var(--primary-color); font-size: 20px; }
.xk-file-meta { flex: 1; min-width: 0; }
.xk-file-name { font-weight: 600; color: var(--text); }
.xk-file-sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
.xk-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }

/* 检验项目判定表 */
.xk-qa-table { border: 1px solid var(--line,#eef2f7); border-radius: 10px; overflow: hidden; }
.xk-qa-head, .xk-qa-row { display: grid; grid-template-columns: 1.1fr 1.6fr 1.1fr 1.1fr 90px;
  gap: 8px; padding: 10px 14px; font-size: 13px; align-items: center; }
.xk-qa-head { background: var(--primary-soft,#f5f8fc); color: var(--muted); font-weight: 600; font-size: 12px; }
.xk-qa-row { border-top: 1px solid var(--line-soft,#f2f5f9); }
.xk-qa-row:nth-child(even) { background: var(--primary-soft,#fbfcfe); }

/* 照片 */
.xk-photo-tip { font-size: 12px; color: var(--muted); margin-bottom: 10px; }
.xk-photo-row { display: flex; flex-wrap: wrap; gap: 10px; }
.xk-photo-item { width: 92px; height: 92px; border-radius: 8px; overflow: hidden; position: relative;
  border: 1px solid var(--line,#eef2f7); background: var(--primary-soft,#f7f9fc); cursor: pointer; }
.xk-photo-item img { width: 100%; height: 100%; object-fit: cover; }
.xk-photo-badge { position: absolute; inset: 0; display: grid; place-items: center; font-size: 12px;
  color: var(--muted); text-align: center; padding: 6px; }
.xk-photo-x { position: absolute; top: 2px; right: 4px; width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; display: grid; place-items: center; font-size: 12px; line-height: 1; }
.xk-photo-add { width: 92px; height: 92px; border-radius: 8px; border: 1px dashed var(--primary-color,#4a6df0);
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
  color: var(--primary-color); cursor: pointer; font-size: 20px; background: var(--primary-soft,#f0f7ff); }
.xk-photo-add div { font-size: 12px; color: var(--muted); text-align: center; line-height: 1.4; }
.xk-photo-add div small { color: #b0b8c4; font-size: 11px; }

/* 右侧综合判定 */
.xk-verdict-card { background: #fff; border: 1px solid var(--line,#eef2f7); border-radius: 12px; padding: 16px;
  display: flex; gap: 12px; align-items: flex-start; }
.xk-verdict-icon { color: #67c23a; flex-shrink: 0; }
.xk-verdict-label { font-size: 14px; color: var(--text); margin-bottom: 4px; display: flex; align-items: center; gap: 8px; }
.xk-verdict-ok { color: #67c23a; }
.xk-verdict-bad { color: #f56c6c; }
.xk-verdict-card small { font-size: 12px; color: var(--muted); line-height: 1.5; display: block; }
.xk-kv { background: #fff; border: 1px solid var(--line,#eef2f7); border-radius: 12px; padding: 6px 16px;
  display: flex; flex-direction: column; }
.xk-kv div { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid var(--line-soft,#f4f6f9); }
.xk-kv div:last-child { border-bottom: none; }
.xk-kv small { color: var(--muted); font-size: 12px; }
.xk-kv b { font-size: 13px; color: var(--text); }
.xk-note { font-size: 12px; color: var(--muted); line-height: 1.6; padding: 0 4px; }

/* 列表 */
.pager { margin-top: 12px; text-align: right; }

/* AI 弹窗 */
.ai-meta { padding: 8px 12px; background: #f5f7fa; border-radius: 4px; font-size: 13px;
  color: #606266; margin-bottom: 10px; display: flex; gap: 16px; }
.ai-meta b { color: #303133; }
.ai-body { min-height: 120px; max-height: 520px; overflow: auto; }
.ai-content { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 14px;
  line-height: 1.7; color: #303133; background: #fafafa; padding: 12px 14px; border-radius: 4px; }
</style>

<!-- v1.1.95 XK 采购入库: 整页 XK 表单 (对齐 Figma) + 单据列表 Tab -->
