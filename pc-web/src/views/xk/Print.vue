<template>
  <div class="xk-print">
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">经营支持 / 单据中心</div>
        <h1 class="xk-title">单据与打印</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button :disabled="!paperDoc" @click="onPdf"><el-icon><Download /></el-icon>导出 PDF</el-button>
        <el-button type="primary" :disabled="!paperDoc" :loading="printing" @click="onPrint"><el-icon><Printer /></el-icon>打印当前单据</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>打印/导出时登记单号、生成时间与操作人；受控记录以系统台账为准，导出文件请按留存年限归档</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <div class="print-workspace">
      <!-- 左: 单据选择与打印内容 -->
      <aside class="print-settings card">
        <div class="ps-title">选择业务单据</div>
        <div class="ps-intro">检验记录取自「质量合规 → 检验记录与报告」台账，业务单据取自各业务模块的真实单据</div>

        <div class="ps-field">
          <label>单据类型</label>
          <el-select v-model="docType" style="width:100%">
            <el-option-group v-for="g in docGroups" :key="g.label" :label="g.label">
              <el-option v-for="d in g.items" :key="d.value" :label="d.label" :value="d.value" />
            </el-option-group>
          </el-select>
        </div>

        <div class="ps-field">
          <label>
            业务单号
            <span v-if="records.length" class="ps-count">共 {{ records.length }} 张</span>
            <el-icon class="ps-refresh" :class="{ spinning: listLoading }" @click="reload"><Refresh /></el-icon>
          </label>
          <el-select
            v-model="recordId"
            style="width:100%"
            filterable
            :loading="listLoading"
            :placeholder="listLoading ? '加载中…' : (records.length ? '请选择单据' : '暂无可打印单据')">
            <el-option v-for="r in records" :key="r.id" :label="r.label" :value="r.id" />
          </el-select>
        </div>

        <div v-if="loadError" class="ps-error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ loadError }}</span>
          <el-button link type="primary" size="small" @click="reload">重试</el-button>
        </div>
        <div v-else-if="!listLoading && !records.length" class="ps-note" style="margin-top:0">
          <strong>暂无可打印单据</strong>
          <p>{{ emptyHint }}</p>
        </div>

        <div class="ps-section-title">自由选择打印内容</div>
        <div class="print-options">
          <label v-for="opt in sectionOpts" :key="opt.key">
            <el-checkbox v-model="sections[opt.key]" />
            <span>{{ opt.label }}</span>
          </label>
        </div>

        <div class="print-two-fields">
          <div class="ps-field">
            <label>纸张</label>
            <el-select v-model="paper" style="width:100%">
              <el-option label="A4 纵向" value="A4P" />
              <el-option label="A4 横向" value="A4L" />
            </el-select>
          </div>
          <div class="ps-field">
            <label>份数</label>
            <el-input-number v-model="copies" :min="1" :max="99" style="width:100%" />
          </div>
        </div>

        <div class="ps-note">
          <strong>所见即所得</strong>
          <p>右侧预览即实际打印版式；「导出 PDF」会在浏览器打印对话框中选择「另存为 PDF」。份数在打印对话框中设置。</p>
        </div>
      </aside>

      <!-- 右: 纸张预览 -->
      <div class="paper-wrap" :class="'size-' + paper">
        <article v-if="paperDoc" class="paper">
          <div class="paper-brand">
            <span class="brand-symbol"><i /><i /><i /></span>
            <div>
              <strong>{{ systemName }}</strong>
              <small>华塑包装 · 吹膜成膜</small>
            </div>
            <el-tag :type="paperDoc.audited ? 'success' : 'warning'" effect="light">{{ paperDoc.statusText }}</el-tag>
          </div>

          <div class="paper-title">{{ paperDoc.title }}</div>

          <div v-if="sections.audit" class="paper-code">
            唯一编号：{{ paperDoc.code }} · 生成时间：{{ paperDoc.createdText }} · 操作人：{{ paperDoc.operatorText }}
          </div>

          <div v-if="sections.basic" class="paper-info">
            <div v-for="m in paperDoc.meta" :key="m[0]">
              <small>{{ m[0] }}</small>
              <strong>{{ m[1] }}</strong>
            </div>
          </div>

          <div v-if="sections.details" class="paper-body">
            <table class="paper-table detail">
              <thead><tr><th v-for="h in paperDoc.headers" :key="h">{{ h }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, i) in paperDoc.rows" :key="i">
                  <td v-for="(c, j) in row" :key="j">{{ c }}</td>
                </tr>
              </tbody>
            </table>

            <div v-if="sections.photos && paperDoc.photos.length" class="paper-photo-block">
              <div class="paper-section-title">现场照片与业务附件</div>
              <div class="paper-photos">
                <figure v-for="(p, i) in paperDoc.photos" :key="i">
                  <img :src="p.url" :alt="p.caption" />
                  <figcaption>{{ p.caption }}</figcaption>
                </figure>
              </div>
            </div>
            <div v-else-if="sections.photos" class="paper-no-photo">
              <el-icon><Document /></el-icon><span>该单据无照片附件</span>
            </div>

            <div v-if="sections.remark" class="paper-remark"><strong>备注 / 处理结果：</strong>{{ paperDoc.remark || '无' }}</div>
          </div>

          <div v-if="sections.signatures && paperDoc.signatures.length" class="paper-sign">
            <span v-for="s in paperDoc.signatures" :key="s">{{ s }}</span>
          </div>

          <div v-if="sections.audit" class="paper-footer">
            {{ paperDoc.group }} · {{ systemName }} · 系统生成 · 打印时间 {{ printedAt }}
          </div>
        </article>

        <div v-else class="paper-empty">
          <el-icon><Document /></el-icon>
          <p>{{ loadError || (listLoading || detailLoading ? '正在加载单据…' : '暂无可预览的单据') }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Download, Printer, CircleCheckFilled, Document, WarningFilled, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useSystemName } from '@/composables/useSystemName'
import { inspectionApi } from '@/api/quality'
import { envCheckApi, xkMock } from '@/api/xk'
import { purOrderApi } from '@/api/purchase'
import { prdOrderApi, prdRequisitionApi } from '@/api/production'
import { maintainRecordApi, maintainPlanApi } from '@/api/equipment'

const { systemName } = useSystemName()

const printing = ref(false)
const paper = ref('A4P')
const copies = ref(1)
const printedAt = ref('—')

// v1.1.147: 单据清单全部对齐系统真实单据 (检验 6 类走台账, 业务 6 类走各模块列表/详情)
// 删掉 v1.1.146 里台账/系统都不存在的「采购计划单」「生产记录单」— 没有对应表与接口, 印不出真单据
const docGroups = [
  { label: '检验记录与报告', items: [
    { value: 'iqc', label: '原料进货检验记录 (IQC)' },
    { value: 'fai', label: '半成品首检记录 (FAI)' },
    { value: 'pat', label: '过程巡检记录 (PAT)' },
    { value: 'oqc', label: '成品检验记录 (OQC)' },
    { value: 'oqcReturn', label: '退货检验记录 (OQC_RETURN)' },
    { value: 'env', label: '生产环境检查记录 (ENV)' } ] },
  { label: '业务单据', items: [
    { value: 'purchaseOrder', label: '采购订单' },
    { value: 'purchaseReceipt', label: '采购入库单' },
    { value: 'productionOrder', label: '生产工单' },
    { value: 'productionRequisition', label: '生产领料单' },
    { value: 'maintenanceRecord', label: '设备维保记录单' },
    { value: 'maintenancePlan', label: '设备维保计划单' } ] }
]

// 每类单据的数据来源口径: kind 决定列表与详情的接口, title/group 进纸张表头
const sources = {
  iqc: { kind: 'inspection', inspectionType: 'IQC', title: '原料进货检验记录 (IQC)', group: '质量检验', emptyHint: '台账里还没有 IQC 记录；采购入库审核会自动生成草稿，也可在「质量合规 → 检验记录与报告」手工建单。' },
  fai: { kind: 'inspection', inspectionType: 'FAI', title: '半成品首检记录 (FAI)', group: '质量检验', emptyHint: '台账里还没有 FAI 记录；吹膜/制袋开工后按工单做首检即生成。' },
  pat: { kind: 'inspection', inspectionType: 'PAT', title: '过程巡检记录 (PAT)', group: '质量检验', emptyHint: '台账里还没有 PAT 记录；工序报工或巡检时生成。' },
  oqc: { kind: 'inspection', inspectionType: 'OQC', title: '成品检验记录 (OQC)', group: '质量检验', emptyHint: '台账里还没有 OQC 记录；成品检完成后生成。' },
  oqcReturn: { kind: 'inspection', inspectionType: 'OQC_RETURN', title: '退货检验记录 (OQC_RETURN)', group: '质量检验', emptyHint: '台账里还没有退货检验记录。' },
  env: { kind: 'env', title: '生产环境检查记录 (ENV)', group: '环境卫生检查', emptyHint: '还没有环境检查单；可在「安全卫生 → 生产环境检查」新建并审核。' },
  purchaseOrder: { kind: 'purOrder', title: '采购订单', group: '采购管理', emptyHint: '还没有采购订单；可在「采购管理 → 采购订单」新建。' },
  purchaseReceipt: { kind: 'purReceipt', title: '采购入库单', group: '采购管理', emptyHint: '还没有采购入库单；可在「采购入库」新建。' },
  productionOrder: { kind: 'prdOrder', title: '生产工单', group: '生产管理', emptyHint: '还没有生产工单；可在「生产管理 → 生产工单」新建。' },
  productionRequisition: { kind: 'prdRequisition', title: '生产领料单', group: '生产管理', emptyHint: '还没有领料单；生产工单开工时会自动生成。' },
  maintenanceRecord: { kind: 'maintRecord', title: '设备维保记录单', group: '设备维保', emptyHint: '还没有维保记录；可在「设备维保」完成工单后生成。' },
  maintenancePlan: { kind: 'maintPlan', title: '设备维保计划单', group: '设备维保', emptyHint: '还没有维保计划；可在「设备维保 → 维保计划」新建。' }
}

const RESULT_TEXT = {
  PASS: '合格 (PASS)', PASS_DISPOSITION: '合格 (让步放行)',
  FAIL: '不合格 (FAIL)', FAIL_REJECT: '不合格 (拒收)'
}
const DISPOSITION_TEXT = {
  RELEASE: '放行', BATCH_FROZEN: '批次冻结', SCRAP: '报废', RETURN: '退货', REWORK: '返工', CONCESSION: '让步接收'
}
const BILL_STATUS_TEXT = {
  DRAFT: '草稿', CHECKED: '已审核', FINISHED: '已完成', UNCHECKED: '已弃审', VOID: '已作废',
  PENDING: '待处理', IN_PROGRESS: '进行中', DONE: '已完成', ACTIVE: '有效'
}
const LEVEL_TEXT = { HIGH: '重大', MEDIUM: '一般', LOW: '轻微' }
const RECTIFY_TEXT = { NONE: '无需整改', IN_PROGRESS: '整改中', DONE: '已完成' }

const isEmpty = v => v === null || v === undefined || v === ''
const S = v => (isEmpty(v) ? '-' : v)
const fmtDate = v => (isEmpty(v) ? '-' : String(v).replace('T', ' ').slice(0, 16))
const fmtNum = v => (isEmpty(v) ? '-' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 3 }))
const money = v => (isEmpty(v) ? '-' : '¥ ' + fmtNum(v))
const qtyText = (qty, unit) => (isEmpty(qty) ? '-' : `${fmtNum(qty)}${unit ? ' ' + unit : ''}`)

function parsePhotos(field) {
  if (!field) return []
  if (Array.isArray(field)) return field.filter(Boolean)
  try {
    const p = JSON.parse(field)
    return Array.isArray(p) ? p.filter(Boolean) : []
  } catch (e) { return [] }
}

const docType = ref('iqc')
const records = ref([])
const recordId = ref(null)
const listLoading = ref(false)
const detailLoading = ref(false)
const loadError = ref('')
const detail = ref(null)
const rawRow = ref(null)

const emptyHint = computed(() => sources[docType.value].emptyHint)

const sections = ref({ basic: true, details: true, photos: true, remark: true, signatures: true, audit: true })
const sectionOpts = [
  { key: 'basic', label: '基本信息' },
  { key: 'details', label: '业务明细' },
  { key: 'photos', label: '现场 / 关联照片' },
  { key: 'remark', label: '备注与处理结果' },
  { key: 'signatures', label: '审核签章' },
  { key: 'audit', label: '编号、生成时间与操作人' }
]

function errText(e) {
  if (e?.response?.status === 403) return '当前账号无该单据的查看权限'
  return e?.msg || e?.response?.data?.msg || e?.message || '加载失败'
}

// ===== 列表: 每类单据一条真实查询 =====
async function fetchRecords(kind) {
  if (kind === 'inspection') {
    const s = sources[docType.value]
    const r = await inspectionApi.page({ pageNum: 1, pageSize: 50, inspectionType: s.inspectionType })
    return (r.data?.records || []).map(x => ({
      id: x.id,
      raw: x,
      label: [x.billNo, x.billDate, x.productName, x.batchNo, RESULT_TEXT[x.result] || BILL_STATUS_TEXT[x.status]].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'env') {
    const r = await envCheckApi.page({ pageNum: 1, pageSize: 50 })
    return (r.data?.records || []).map(x => ({
      id: x.id,
      raw: x,
      label: [x.checkNo, x.plannedAt, x.workshop, x.checkTitle || x.envType, BILL_STATUS_TEXT[x.status]].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'purOrder') {
    const r = await purOrderApi.page({ pageNum: 1, pageSize: 50 })
    return (r.data?.records || []).map(x => ({
      id: x.id, raw: x,
      label: [x.billNo, x.billDate, x.supplierName, BILL_STATUS_TEXT[x.billStatus] || x.billStatus].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'purReceipt') {
    const r = await xkMock.receiptList({ pageNum: 1, pageSize: 50 })
    return (r.data || []).map(x => ({
      id: x.id, raw: x,
      label: [x.billNo, x.billDate, x.supplierName, x.firstProductName, BILL_STATUS_TEXT[x.billStatus] || x.billStatus].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'prdOrder') {
    const r = await prdOrderApi.page({ pageNum: 1, pageSize: 50 })
    return (r.data?.records || []).map(x => ({
      id: x.id, raw: x,
      label: [x.billNo, x.billDate, x.productName, BILL_STATUS_TEXT[x.billStatus] || x.billStatus].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'prdRequisition') {
    const r = await prdRequisitionApi.page({ pageNum: 1, pageSize: 50 })
    return (r.data?.records || []).map(x => ({
      id: x.id, raw: x,
      label: [x.billNo, x.billDate, x.prdOrderNo, BILL_STATUS_TEXT[x.billStatus] || x.billStatus].filter(Boolean).join(' · ')
    }))
  }
  if (kind === 'maintRecord') {
    const r = await maintainRecordApi.page({ pageNum: 1, pageSize: 50 })
    return (r.data?.records || []).map(x => ({
      id: x.id, raw: x,
      label: [x.recordNo, x.equipmentName, fmtDate(x.startTime), x.result].filter(Boolean).join(' · ')
    }))
  }
  // maintPlan: 后端只有分页接口 (无 detail), 列表行本身就是完整数据
  const r = await maintainPlanApi.page({ pageNum: 1, pageSize: 50 })
  return (r.data?.records || []).map(x => ({
    id: x.id, raw: x,
    label: [x.planNo, x.planName, x.equipmentName, x.status].filter(Boolean).join(' · ')
  }))
}

async function loadList() {
  listLoading.value = true
  loadError.value = ''
  records.value = []
  recordId.value = null
  detail.value = null
  rawRow.value = null
  try {
    const list = await fetchRecords(sources[docType.value].kind)
    records.value = list
    if (list.length) {
      recordId.value = list[0].id
      rawRow.value = list[0].raw
    }
  } catch (e) {
    loadError.value = errText(e)
  } finally {
    listLoading.value = false
  }
}

// ===== 详情: 检验单补检验项 (读接口, 不落写) =====
async function loadDetail() {
  const s = sources[docType.value]
  const id = recordId.value
  if (!id) { detail.value = null; return }
  detailLoading.value = true
  loadError.value = ''
  // 详情接口普遍不回「制单人姓名」「关联单号」这类列表派生字段,
  // 受控记录必须打出操作人, 所以详情铺在列表行之上 (详情优先, 列表兜底)。
  const withRow = (data) => ({ ...(rawRow.value || {}), ...(data || {}) })
  try {
    if (s.kind === 'inspection') {
      const r = await inspectionApi.detail(id)
      const head = r.data?.head || r.data || {}
      let items = r.data?.items || []
      if (!items.length) {
        try {
          const ir = await inspectionApi.iqcItems(id)
          items = ir.code === 200 ? (ir.data || []) : []
        } catch (e) { items = [] }
      }
      detail.value = { head, items, defects: r.data?.defects || [] }
    } else if (s.kind === 'env') {
      const r = await envCheckApi.detail(id)
      detail.value = { head: withRow(r.data) }
    } else if (s.kind === 'purOrder') {
      const r = await purOrderApi.detail(id)
      detail.value = { head: withRow(r.data) }
    } else if (s.kind === 'purReceipt') {
      const r = await xkMock.receiptDetail(id)
      detail.value = { head: withRow(r.data) }
    } else if (s.kind === 'prdOrder') {
      const r = await prdOrderApi.detail(id)
      detail.value = { head: withRow(r.data) }
    } else if (s.kind === 'prdRequisition') {
      const r = await prdRequisitionApi.detail(id)
      detail.value = { head: withRow(r.data) }
    } else if (s.kind === 'maintRecord') {
      const r = await maintainRecordApi.detail(id)
      detail.value = { head: withRow(r.data) }
    } else {
      // 维保计划无详情接口, 直接用列表行
      detail.value = { head: rawRow.value || {} }
    }
  } catch (e) {
    loadError.value = errText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

// ===== 统一纸张模型 (模板只认这一份) =====
function buildInspection(s, d) {
  const head = d.head || {}
  const meta = [
    ['产品 / 物料', S(head.productName)],
    ['规格型号', S(head.spec || head.model)],
    ['批次 / 批号', S(head.batchNo)],
    ['关联单据', S(head.sourceBillNo)],
    ['抽样 / 合格', `${S(head.sampleQty)} / ${S(head.qualifiedQty)}`],
    ['检验结论', RESULT_TEXT[head.result] || S(head.result)],
    ['批次处置', DISPOSITION_TEXT[head.disposition] || S(head.disposition)],
    ['检验依据', 'Q/ZPC 1-2017']
  ]
  if (head.workshop) meta.splice(4, 0, ['车间 / 工序', S(head.workshop)])
  if (head.unqualifiedQty != null) meta[4] = ['抽样 / 合格 / 不良', `${S(head.sampleQty)} / ${S(head.qualifiedQty)} / ${S(head.unqualifiedQty)}`]
  if (head.retainNo) meta.push(['留样编号', S(head.retainNo)])

  const itemRows = (d.items || []).map((it, i) => [
    i + 1,
    S(it.itemName || it.itemCode),
    S(it.requirement),
    S(it.method),
    `${S(it.resultValue)}${it.unit ? ' ' + it.unit : ''}`,
    it.passFlag === 0 ? '不合格' : it.passFlag === 1 ? '合格' : '—'
  ])
  const defectRows = (d.defects || []).map((x, i) => [
    `缺陷 ${i + 1}`, S(x.defectType), S(x.severity), S(x.qty), S(x.disposal)
  ])
  const useItems = itemRows.length > 0

  const photos = []
  parsePhotos(head.photoUrls).forEach((url, i) => photos.push({ url, caption: '检验照片 ' + (i + 1) }))
  ;(d.defects || []).forEach(x => parsePhotos(x.photoUrl).forEach(url => photos.push({ url, caption: '不良品照片' })))

  return {
    title: s.title,
    code: S(head.billNo),
    group: s.group,
    statusText: head.status === 'CHECKED' ? '已审核 · 只读' : (BILL_STATUS_TEXT[head.status] || S(head.status)),
    audited: head.status === 'CHECKED',
    meta,
    headers: useItems ? ['序号', '检验项目', '技术要求', '检验方法', '实测值', '单项判定'] : ['缺陷', '类型', '严重度', '数量', '处置'],
    rows: useItems ? itemRows : (defectRows.length ? defectRows : [['—', '暂无检验项明细', '—', '—', '—']]),
    photos,
    remark: head.remark || head.dispositionReason || '',
    signatures: [
      head.inspectorName ? `检验员：${head.inspectorName}` : '',
      head.reviewerName ? `复核人：${head.reviewerName}` : '',
      head.approverName ? `批准人：${head.approverName}` : ''
    ].filter(Boolean),
    createdText: fmtDate(head.checkTime || head.createTime),
    operatorText: S(head.inspectorName || head.createByName)
  }
}

function buildEnv(s, head) {
  return {
    title: s.title,
    code: S(head.checkNo),
    group: s.group,
    statusText: head.status === 'CHECKED' ? '已审核 · 只读' : (BILL_STATUS_TEXT[head.status] || S(head.status)),
    audited: head.status === 'CHECKED',
    meta: [
      ['检查标题', S(head.checkTitle || head.envType)],
      ['车间 / 区域', S(head.workshop)],
      ['环境类型', S(head.envType)],
      ['计划时间', fmtDate(head.plannedAt)],
      ['检查人', S(head.inspector)],
      ['问题等级', LEVEL_TEXT[head.level] || S(head.level)],
      ['整改负责人', S(head.rectifyOwner)],
      ['整改期限 / 状态', `${fmtDate(head.rectifyDeadline)} / ${RECTIFY_TEXT[head.rectifyStatus] || S(head.rectifyStatus)}`]
    ],
    headers: ['检查内容', '整改说明', '问题等级', '整改状态'],
    rows: [[S(head.checkContent), S(head.rectifyDesc), LEVEL_TEXT[head.level] || S(head.level), RECTIFY_TEXT[head.rectifyStatus] || S(head.rectifyStatus)]],
    photos: [
      ...parsePhotos(head.beforePhotos).map((url, i) => ({ url, caption: '整改前 ' + (i + 1) })),
      ...parsePhotos(head.afterPhotos).map((url, i) => ({ url, caption: '整改后 ' + (i + 1) }))
    ],
    remark: head.remark || '',
    signatures: [
      head.inspector ? `检查人：${head.inspector}` : '',
      head.rectifyOwner ? `整改人：${head.rectifyOwner}` : ''
    ].filter(Boolean),
    createdText: fmtDate(head.createTime),
    operatorText: S(head.inspector || head.createByName)
  }
}

function buildPurOrder(s, o) {
  const details = o.details || []
  return {
    title: s.title,
    code: S(o.billNo),
    group: s.group,
    statusText: BILL_STATUS_TEXT[o.billStatus] || S(o.billStatus),
    audited: o.billStatus === 'CHECKED' || o.billStatus === 'FINISHED',
    meta: [
      ['供应商', S(o.supplierName)],
      ['单据日期', fmtDate(o.billDate)],
      ['采购员', S(o.buyerName)],
      ['付款方式', S(o.payType)],
      ['合计数量', fmtNum(o.totalQty)],
      ['含税金额', money(o.totalAmountTax ?? o.totalAmount)]
    ],
    headers: ['商品名称', '规格 / 型号', '数量', '单价', '金额'],
    rows: details.length
      ? details.map(d => [S(d.productName), S(d.spec || d.pModel), qtyText(d.qty, d.unitName), money(d.price), money(d.amountTax ?? d.amount)])
      : [['—', '—', '—', '—', '—']],
    photos: [],
    remark: o.remark || '',
    signatures: [o.buyerName ? `采购员：${o.buyerName}` : '', o.createByName ? `制单：${o.createByName}` : ''].filter(Boolean),
    createdText: fmtDate(o.createTime),
    operatorText: S(o.createByName || o.buyerName)
  }
}

function buildPurReceipt(s, r) {
  const details = r.details || []
  return {
    title: s.title,
    code: S(r.billNo),
    group: s.group,
    statusText: BILL_STATUS_TEXT[r.billStatus] || S(r.billStatus),
    audited: r.billStatus === 'CHECKED' || r.billStatus === 'FINISHED',
    meta: [
      ['供应商', S(r.supplierName)],
      ['入库日期', fmtDate(r.billDate)],
      ['仓库', S(r.warehouseName)],
      ['关联订单', S(r.orderNo)],
      ['合计数量', fmtNum(r.totalQty)],
      ['检验结果', S(r.inspectionResult)]
    ],
    headers: ['商品名称', '批号', '数量', '库位', '质检'],
    rows: details.length
      ? details.map(d => [S(d.productName), S(d.batchNo), qtyText(d.qty, d.unitName), S(d.locationName), S(d.iqcResult || r.inspectionResult)])
      : [['—', '—', '—', '—', '—']],
    photos: parsePhotos(r.labelPhotos).map((url, i) => ({ url, caption: '原料标签 / 到货照片 ' + (i + 1) })),
    remark: r.remark || '',
    signatures: [
      r.createByName ? `制单：${r.createByName}` : '',
      r.inspectorName ? `检验员：${r.inspectorName}` : '',
      r.warehouseName ? `仓库：${r.warehouseName}` : ''
    ].filter(Boolean),
    createdText: fmtDate(r.createTime),
    operatorText: S(r.createByName)
  }
}

function buildPrdOrder(s, o) {
  const reqDetails = o.requisitionDetails || []
  return {
    title: s.title,
    code: S(o.billNo),
    group: s.group,
    statusText: BILL_STATUS_TEXT[o.billStatus] || S(o.billStatus),
    audited: o.billStatus === 'CHECKED' || o.billStatus === 'FINISHED' || o.billStatus === 'RELEASED' || o.billStatus === 'PRODUCING',
    meta: [
      ['产品名称', S(o.productName)],
      ['规格 / 型号', S(o.spec || o.model)],
      ['计划数量', qtyText(o.planQty, o.unitName)],
      ['合格 / 已入库', `${S(o.goodQty)} / ${S(o.actualQty)}`],
      ['总重量 (kg)', fmtNum(o.totalWeightKg)],
      ['车间', S(o.workshop)],
      ['关联 BOM', S(o.bomNo || o.bomName)],
      ['计划起止', `${fmtDate(o.startDate)} ~ ${fmtDate(o.endDate)}`]
    ],
    headers: ['物料名称', '批号', '用量', '单位'],
    rows: reqDetails.length
      ? reqDetails.map(d => [S(d.productName), S(d.batchNo), fmtNum(d.qty), S(d.unitName)])
      : [[S(o.productName), S(o.batchNo), fmtNum(o.planQty), S(o.unitName)]],
    photos: [],
    remark: o.bomRemark || o.remark || '',
    signatures: [o.leader ? `班组长：${o.leader}` : '', o.createByName ? `计划员：${o.createByName}` : ''].filter(Boolean),
    createdText: fmtDate(o.createTime),
    operatorText: S(o.createByName || o.leader)
  }
}

function buildPrdRequisition(s, r) {
  const details = r.details || []
  return {
    title: s.title,
    code: S(r.billNo),
    group: s.group,
    statusText: BILL_STATUS_TEXT[r.billStatus] || S(r.billStatus),
    audited: r.billStatus === 'CHECKED' || r.billStatus === 'FINISHED',
    meta: [
      ['领料日期', fmtDate(r.billDate)],
      ['关联工单', S(r.prdOrderNo)],
      ['车间', S(r.workshop)],
      ['单据类型', r.billType === 'RETURN' ? '退料' : '领料'],
      ['明细行数', details.length],
      ['状态', BILL_STATUS_TEXT[r.billStatus] || S(r.billStatus)]
    ],
    headers: ['物料名称', '物料类型', '数量', '批号'],
    rows: details.length
      ? details.map(d => [S(d.productName), S(d.materialType), qtyText(d.qty, d.unitName), S(d.batchNo)])
      : [['—', '—', '—', '—']],
    photos: [],
    remark: r.remark || '',
    signatures: [r.createByName ? `制单：${r.createByName}` : ''].filter(Boolean),
    createdText: fmtDate(r.createTime),
    operatorText: S(r.createByName)
  }
}

function buildMaintRecord(s, r) {
  return {
    title: s.title,
    code: S(r.recordNo),
    group: s.group,
    statusText: r.reviewTime ? '已复核' : (r.completeTime ? '已完成' : '进行中'),
    audited: !!r.reviewTime,
    meta: [
      ['设备编码', S(r.equipmentCode)],
      ['设备名称', S(r.equipmentName)],
      ['维保内容', S(r.maintContent)],
      ['开始时间', fmtDate(r.startTime)],
      ['完成时间', fmtDate(r.completeTime)],
      ['维保结果', S(r.result)],
      ['复核人', S(r.reviewer)],
      ['复核时间', fmtDate(r.reviewTime)]
    ],
    headers: ['维保内容', '开始时间', '完成时间', '维保结果', '复核人'],
    rows: [[S(r.maintContent), fmtDate(r.startTime), fmtDate(r.completeTime), S(r.result), S(r.reviewer)]],
    photos: parsePhotos(r.photoUrls).map((url, i) => ({ url, caption: '维保照片 ' + (i + 1) })),
    remark: r.remark || '',
    signatures: [r.operator ? `维保人：${r.operator}` : '', r.reviewer ? `复核人：${r.reviewer}` : ''].filter(Boolean),
    createdText: fmtDate(r.createTime),
    operatorText: S(r.operator || r.createByName)
  }
}

function buildMaintPlan(s, p) {
  const cycleText = p.cycleType === 'DAY' || p.cycleDays
    ? `每 ${S(p.cycleDays)} 天`
    : S(p.cycleType)
  return {
    title: s.title,
    code: S(p.planNo),
    group: s.group,
    statusText: BILL_STATUS_TEXT[p.status] || S(p.status),
    audited: p.status === 'ACTIVE' || p.status === 'CHECKED',
    meta: [
      ['计划名称', S(p.planName)],
      ['设备编码', S(p.equipmentCode)],
      ['设备名称', S(p.equipmentName)],
      ['维保周期', cycleText],
      ['上次执行', fmtDate(p.lastRunDate)],
      ['下次执行', fmtDate(p.nextRunDate)],
      ['负责人', S(p.owner)],
      ['状态', BILL_STATUS_TEXT[p.status] || S(p.status)]
    ],
    headers: ['计划名称', '维保内容', '周期', '下次执行', '负责人'],
    rows: [[S(p.planName), S(p.maintContent), cycleText, fmtDate(p.nextRunDate), S(p.owner)]],
    photos: [],
    remark: p.remark || '',
    signatures: [p.owner ? `负责人：${p.owner}` : '', p.createByName ? `制单：${p.createByName}` : ''].filter(Boolean),
    createdText: fmtDate(p.createTime),
    operatorText: S(p.createByName || p.owner)
  }
}

const paperDoc = computed(() => {
  const s = sources[docType.value]
  const d = detail.value
  if (!d) return null
  const head = d.head || {}
  switch (s.kind) {
    case 'inspection': return buildInspection(s, d)
    case 'env': return buildEnv(s, head)
    case 'purOrder': return buildPurOrder(s, head)
    case 'purReceipt': return buildPurReceipt(s, head)
    case 'prdOrder': return buildPrdOrder(s, head)
    case 'prdRequisition': return buildPrdRequisition(s, head)
    case 'maintRecord': return buildMaintRecord(s, head)
    case 'maintPlan': return buildMaintPlan(s, head)
    default: return null
  }
})

// ===== 打印 / 导出 =====
// 纸张方向靠动态 @page 规则 (scoped CSS 里的 @page 无法随选择切换)
const PAGE_STYLE_ID = 'xk-print-page-rule'
function applyPageRule() {
  removePageRule()
  const el = document.createElement('style')
  el.id = PAGE_STYLE_ID
  el.textContent = `@page { size: A4 ${paper.value === 'A4L' ? 'landscape' : 'portrait'}; margin: 10mm; }`
  document.head.appendChild(el)
}
function removePageRule() {
  const el = document.getElementById(PAGE_STYLE_ID)
  if (el) el.remove()
}

// 打印审计: 真实调 /xk/print/audit 落 xk_audit_log (需 xk:audit:archive; 无权限静默跳过)
async function writePrintAudit(code) {
  if (!code) return
  try {
    await xkMock.printAudit({ billNo: code })
  } catch (e) { /* 无 xk:audit:archive 权限时不阻塞打印 */ }
}

function doPrint() {
  if (!paperDoc.value) { ElMessage.warning('请先选择一张单据'); return }
  printing.value = true
  printedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
  writePrintAudit(paperDoc.value.code)
  applyPageRule()
  setTimeout(() => {
    window.print()
    printing.value = false
  }, 60)
}

function onPrint() {
  doPrint()
}

function onPdf() {
  if (!paperDoc.value) { ElMessage.warning('请先选择一张单据'); return }
  ElMessage.info('在打印对话框的「目标」中选择「另存为 PDF」即可导出')
  doPrint()
}

function reload() {
  loadList()
}

watch(docType, () => { loadList() })
watch(recordId, (v, old) => {
  if (v && v !== old) {
    const hit = records.value.find(r => r.id === v)
    if (hit) rawRow.value = hit.raw
    loadDetail()
  }
})

onMounted(() => { loadList() })
onBeforeUnmount(() => {
  removePageRule()
  window.removeEventListener('afterprint', removePageRule)
})
window.addEventListener('afterprint', removePageRule)
</script>

<style scoped lang="scss">
.xk-print { display: flex; flex-direction: column; gap: 16px; }

// Figma .print-workspace
.print-workspace { display: grid; grid-template-columns: 260px 1fr; gap: 18px; align-items: start; }
@media (max-width: 1050px) { .print-workspace { grid-template-columns: 1fr; } }

.print-settings {
  padding: 20px; position: sticky; top: 88px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.ps-title { font-size: 15px; font-weight: 730; color: var(--text); }
.ps-intro { font-size: 12px; color: var(--muted); line-height: 1.55; margin: 6px 0 18px; }
.ps-field { margin-bottom: 14px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.ps-field > label { font-size: 12px; color: #526174; font-weight: 650; display: flex; align-items: center; gap: 6px; }
.ps-count { font-weight: 500; color: #8a97a8; }
.ps-refresh {
  margin-left: auto; cursor: pointer; color: #8a97a8; font-size: 13px;
  &:hover { color: var(--primary-color); }
  &.spinning { animation: ps-spin 1s linear infinite; }
}
@keyframes ps-spin { to { transform: rotate(360deg); } }
.ps-error {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  font-size: 11px; color: #b4544a; background: #fdf3f2; border-radius: 8px; padding: 9px 10px;
  .el-icon { font-size: 14px; }
}
.ps-section-title {
  padding-top: 13px; margin: 2px 0 7px; border-top: 1px solid var(--line);
  color: #4c5a6c; font-size: 11px; font-weight: 700;
}
.print-options { display: flex; flex-direction: column; margin-bottom: 15px; }
.print-options label {
  min-height: 35px; display: flex; align-items: center; gap: 8px;
  border-bottom: 1px solid #edf1f4; color: #59687a; font-size: 12px; cursor: pointer;
  &:last-child { border-bottom: 0; }
}
.print-two-fields { display: grid; grid-template-columns: 1fr 72px; gap: 8px; }
.ps-note {
  margin-top: 14px; background: #f5f8fb; border-radius: 9px; padding: 13px;
  strong { font-size: 12px; color: #526275; font-weight: 700; display: block; }
  p { font-size: 11px; color: var(--muted); line-height: 1.6; margin: 5px 0 0; }
}

// Figma .paper-wrap + .paper
.paper-wrap {
  padding: 35px; background: #dce3ea; border-radius: 14px; overflow: auto;
  display: flex; justify-content: center; min-height: 420px;
}
.paper {
  width: 680px; min-height: 900px; margin: auto; padding: 44px 48px 30px;
  background: #fff; box-shadow: 0 10px 30px rgba(28, 48, 69, .14); color: #182331;
  font-family: -apple-system, "PingFang SC", sans-serif; font-size: 13px; position: relative;
}
.size-A4L .paper { width: 900px; min-height: 620px; }

.paper-empty {
  margin: auto; display: flex; flex-direction: column; align-items: center; gap: 10px;
  color: #7c8a9b; font-size: 13px; text-align: center; padding: 60px 20px;
  .el-icon { font-size: 34px; color: #a9b5c3; }
  p { margin: 0; max-width: 320px; line-height: 1.7; }
}

.paper-brand { display: flex; align-items: center; gap: 10px; }
.brand-symbol {
  display: inline-flex; width: 34px; height: 34px; padding: 7px; gap: 3px;
  align-items: flex-end; border-radius: 9px; background: var(--primary-color);
  i { flex: 1; background: #fff; border-radius: 2px 2px 0 0; transform: skewY(-18deg); }
  i:nth-child(1) { height: 50%; } i:nth-child(2) { height: 72%; } i:nth-child(3) { height: 94%; }
}
.paper-brand > div { flex: 1; }
.paper-brand strong { display: block; font-size: 14px; font-weight: 750; color: var(--text); }
.paper-brand small { display: block; font-size: 8px; letter-spacing: .13em; color: #748194; }

.paper-title {
  text-align: center; font-size: 22px; font-weight: 800; letter-spacing: .1em; margin: 30px 0 8px; color: var(--text);
}
.paper-code { text-align: center; color: #687588; font-size: 11px; margin: 0 0 24px; }

.paper-info {
  border: 1px solid #cfd6dd; display: grid; grid-template-columns: repeat(4, 1fr); margin-bottom: 15px;
  > div { padding: 9px 10px; border-right: 1px solid #cfd6dd; border-bottom: 1px solid #e8edf2; &:last-child { border-right: 0; } }
  small { color: #758092; font-size: 11px; display: block; margin-bottom: 5px; }
  strong { font-size: 13px; color: var(--text); display: block; word-break: break-word; }
}

.paper-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.paper-body th, .paper-body td { border: 1px solid #cfd6dd; padding: 9px; text-align: left; vertical-align: top; }
.paper-body th { background: #f0f3f6; font-weight: 600; }

.paper-section-title {
  font-size: 12px; font-weight: 750; margin: 18px 0 10px; padding-left: 8px; border-left: 3px solid var(--primary-color);
}
.paper-photo-block { margin-top: 18px; }
.paper-photos { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.paper-photos figure { margin: 0; border: 1px solid #d3dae1; padding: 6px; }
.paper-photos img { width: 100%; height: 145px; object-fit: cover; display: block; }
.paper-photos figcaption { color: #687588; text-align: center; font-size: 11px; margin: 5px 0 1px; }
.paper-no-photo {
  min-height: 58px; margin-top: 18px; display: flex; align-items: center; justify-content: center; gap: 7px;
  border: 1px dashed #ccd5df; color: #84909f; background: #f8fafc; font-size: 12px;
  .el-icon { font-size: 18px; }
}

.paper-remark {
  background: #f4f6f8; border: 1px solid #d8dee5; padding: 12px; margin-top: 18px;
  font-size: 12px; line-height: 1.7;
  strong { color: var(--primary-color); }
}
.paper-sign {
  display: flex; justify-content: space-between; padding: 30px 5px; font-size: 12px; color: var(--text); flex-wrap: wrap; gap: 10px;
}
.paper-footer {
  border-top: 1px solid #cfd6dd; text-align: center; color: #8792a0; font-size: 10px; padding-top: 9px; margin-top: 18px;
}

// 打印时隐藏壳、只留纸张
@media print {
  .xk-page-heading, .xk-compliance-strip, .print-settings { display: none !important; }
  .print-workspace { display: block; }
  .paper-wrap { background: #fff; padding: 0; border-radius: 0; display: block; overflow: visible; min-height: 0; }
  .paper { box-shadow: none; margin: 0 auto; width: 100% !important; min-height: 0; padding: 0; }
  .paper-body tr, .paper-photos figure { page-break-inside: avoid; }
  .paper-photos img { height: auto; max-height: 150px; object-fit: contain; }
}
</style>
