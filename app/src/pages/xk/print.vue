<template>
  <view class="container">
    <view class="xk-strip no-print">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录 · 单据打印</view>
        <view class="xk-sub">检验记录 / 环境检查 / 入库单 · 打印自动登记单号与操作人 · 留存 {{ retentionYears }} 年</view>
      </view>
    </view>

    <!-- 选单 -->
    <view class="setting-block no-print">
      <view class="setting-title">
        <text>选择单据</text>
        <text class="link" @click="reload">↻ 刷新</text>
      </view>

      <picker mode="selector" :range="typeOptions" :value="typeIndex" @change="onPickType">
        <view class="picker-row">
          <text class="picker-value">{{ currentType.label }}</text>
          <text class="picker-arrow">▾</text>
        </view>
      </picker>

      <view v-if="listLoading" class="hint">加载中...</view>
      <view v-else-if="listError" class="hint error">
        {{ listError }}
        <text class="link" @click="loadRecords">重试</text>
      </view>
      <view v-else-if="!records.length" class="hint">{{ currentType.emptyHint }}</view>
      <picker v-else mode="selector" :range="recordOptions" :value="recordIndex" @change="onPickRecord">
        <view class="picker-row">
          <text class="picker-value">{{ currentRecord ? currentRecord.label : '请选择单据' }}</text>
          <text class="picker-arrow">▾</text>
        </view>
      </picker>

      <view v-if="detailLoading" class="hint">单据明细加载中...</view>
      <view v-else-if="detailError" class="hint error">{{ detailError }}</view>
    </view>

    <!-- 打印设置 -->
    <view class="setting-block no-print">
      <view class="setting-title">打印设置</view>
      <view class="form-row">
        <text class="form-label">包含现场照片</text>
        <switch :checked="includePhotos" color="#1e6091" @change="includePhotos = $event.detail.value" />
      </view>
      <view class="form-row">
        <text class="form-label">显示签署栏</text>
        <switch :checked="includeSign" color="#1e6091" @change="includeSign = $event.detail.value" />
      </view>
      <view class="form-row">
        <text class="form-label">页脚服务器时间戳</text>
        <switch :checked="includeServerTime" color="#1e6091" @change="includeServerTime = $event.detail.value" />
      </view>
    </view>

    <!-- 单据预览 -->
    <view v-if="paperDoc" class="paper-preview no-print-bg">
      <view class="paper">
        <view class="paper-head">
          <view class="logo">
            <image :src="displayBrandLogo" mode="aspectFit" class="logo-img" />
            <view>
              <view class="logo-name">{{ displayBrandName }}</view>
              <view class="logo-en">{{ brandFactory || displayBrandEn }}</view>
            </view>
          </view>
          <text :class="['stamp', paperDoc.audited ? '' : 'stamp-gray']">{{ paperDoc.statusText }}</text>
        </view>

        <view class="paper-title">{{ paperDoc.title }}</view>
        <view class="paper-meta">
          唯一编号: {{ paperDoc.code }} · 生成时间: {{ generatedText }} · 操作人: {{ paperDoc.operatorText }}
        </view>

        <view class="paper-table">
          <view v-for="(pair, i) in metaPairs" :key="i" class="pt-row">
            <text class="pt-th">{{ pair[0][0] }}</text>
            <text class="pt-td">{{ pair[0][1] }}</text>
            <text class="pt-th">{{ pair[1] ? pair[1][0] : '' }}</text>
            <text class="pt-td">{{ pair[1] ? pair[1][1] : '' }}</text>
          </view>
        </view>

        <view v-if="paperDoc.materials && paperDoc.materials.length" class="materials-block">
          <view class="section-title">关联订单原料明细（共 {{ paperDoc.materials.length }} 项）</view>
          <view class="detail-table">
            <view class="dt-head">
              <text v-for="(h, j) in paperDoc.materialHeaders" :key="j" :class="headClass(j)">{{ h }}</text>
            </view>
            <view v-for="(row, i) in paperDoc.materials" :key="'mat' + i" class="dt-body">
              <text v-for="(c, j) in row" :key="j" :class="cellClass(j)">{{ c }}</text>
            </view>
          </view>
        </view>

        <view class="detail-table">
          <view class="dt-head">
            <text v-for="(h, j) in paperDoc.headers" :key="j" :class="headClass(j)">{{ h }}</text>
          </view>
          <view v-for="(row, i) in paperDoc.rows" :key="i" class="dt-body">
            <text v-for="(c, j) in row" :key="j" :class="cellClass(j)">{{ c }}</text>
          </view>
        </view>

        <view v-if="includePhotos" class="photos-section">
          <view class="section-title">📷 现场照片与业务附件</view>
          <view v-if="paperDoc.photos.length" class="photo-row">
            <view v-for="(p, i) in paperDoc.photos" :key="i" class="photo-cell">
              <image :src="p.url" mode="aspectFill" class="photo" />
              <view class="photo-cap">{{ p.caption }}</view>
            </view>
          </view>
          <view v-else class="photo-empty-note">该单据未上传照片附件</view>
        </view>

        <view v-if="paperDoc.remark" class="note-block">
          <text class="note-label">备注 / 处理结果: </text>{{ paperDoc.remark }}
        </view>

        <view v-if="includeSign && paperDoc.signatures.length" class="sign-table">
          <view class="sign-row">
            <text v-for="s in paperDoc.signatures" :key="s" class="sign-cell">{{ s }}</text>
          </view>
        </view>

        <view class="paper-footer">
          <text>{{ paperDoc.group }} · 系统生成 · 不可篡改 · 保存年限 {{ retentionYears }} 年</text>
          <text v-if="includeServerTime" class="block">服务器时间 {{ generatedText }}（XK16-204 R5）</text>
        </view>
      </view>
    </view>

    <view v-else-if="!listLoading && !detailLoading" class="paper-preview no-print-bg">
      <view class="paper-empty-note">{{ records.length ? '请选择一张单据' : '暂无可打印单据' }}</view>
    </view>

    <view class="bottom-actions no-print">
      <button class="btn-primary" :disabled="!paperDoc || printing" @click="onPrint">
        {{ printButtonText }}
      </button>
    </view>
    <view class="footer-space"></view>
  </view>
</template>

<script>
// v1.1.153: 单据打印从「只有已审核采购入库单」扩到 9 类受控记录
//   (IQC / 吹膜首检 / 制袋首检 / 吹膜巡检 / 制袋巡检 / 成品检 / 退货检 / 生产环境检查 / 采购入库单),
//   纸张版式与 PC「单据与打印」同源, 打印走 XK 审计留痕.
//   APK 里 window.print() 不弹系统面板 → 调原生 NativePrint 插件 (PrintManager).
import { registerPlugin } from '@capacitor/core'
import { printDocs, printDocDetail, printPreview, printAudit } from '@/api/xk'
import { isNativeApp } from '@/utils/runtime'
import { photosFromJson } from '@/utils/photo'

const NativePrint = registerPlugin('NativePrint')

const BILL_STATUS_TEXT = {
  DRAFT: '草稿', CHECKED: '已审核', FINISHED: '已完成', UNCHECKED: '已弃审',
  VOID: '已作废', PENDING: '待处理', IN_PROGRESS: '进行中', ACTIVE: '有效'
}
const RESULT_TEXT = {
  PASS: '合格', PASS_DISPOSITION: '合格 (让步放行)', FAIL: '不合格',
  FAIL_REJECT: '不合格 (拒收)', HOLD: '待定'
}
const DISPOSITION_TEXT = {
  RELEASE: '放行', FROZEN: '批次冻结', BATCH_FROZEN: '批次冻结', SCRAP: '报废',
  RETURN: '退货', REWORK: '返工', SORT: '挑选', CONCESSION: '让步接收'
}
const LEVEL_TEXT = { HIGH: '重大', MEDIUM: '一般', LOW: '轻微' }
const ENV_TYPE_TEXT = {
  TEMP_HUMIDITY: '温湿度', CLEANLINESS: '洁净度', FIRE_SAFETY: '消防安全',
  EMERGENCY_LIGHT: '应急照明', SAFETY_DOOR: '安全门', POWER: '供电系统',
  COMPRESSED_AIR: '压缩空气'
}
const RECTIFY_TEXT = { NONE: '无需整改', IN_PROGRESS: '整改中', DONE: '已完成' }

// 可打印单据类型 (与后端 /xk/print/docs 的 docType 一一对应)
const DOC_TYPES = [
  { value: 'iqc', kind: 'inspection', label: '原料进货检验 (IQC)', title: '原料进货检验记录 (IQC)', group: '质量检验', basis: 'Q/ZPC 1-2017', emptyHint: '台账里还没有 IQC 记录（采购入库审核会自动生成草稿）' },
  { value: 'faiBlow', kind: 'inspection', label: '吹膜首检 (FAI)', title: '吹膜首检记录 (FAI)', group: '质量检验', basis: 'Q/ZPC 1-2017 表1~表3', emptyHint: '还没有吹膜首检记录（吹膜开工后按工单做首检即生成）' },
  { value: 'faiBag', kind: 'inspection', label: '制袋首检 (FAI)', title: '制袋首检记录 (FAI)', group: '质量检验', basis: 'Q/ZPC 1-2017 表1~表5', emptyHint: '还没有制袋首检记录（制袋开工后按工单做首检即生成）' },
  { value: 'patBlow', kind: 'inspection', label: '吹膜巡检 (PAT)', title: '吹膜巡检记录 (PAT)', group: '质量检验', basis: 'Q/ZPC 1-2017 表1~表3', emptyHint: '还没有吹膜巡检记录（吹膜工序报工或巡检时生成）' },
  { value: 'patBag', kind: 'inspection', label: '制袋巡检 (PAT)', title: '制袋巡检记录 (PAT)', group: '质量检验', basis: 'Q/ZPC 1-2017 表1~表5', emptyHint: '还没有制袋巡检记录（制袋工序报工或巡检时生成）' },
  { value: 'oqc', kind: 'inspection', label: '成品检验 (OQC)', title: '成品检验记录 (OQC)', group: '质量检验', basis: 'Q/ZPC 1-2017', emptyHint: '台账里还没有 OQC 记录（成品检完成后生成）' },
  { value: 'oqcReturn', kind: 'inspection', label: '退货检验 (OQC_RETURN)', title: '退货检验记录 (OQC_RETURN)', group: '质量检验', basis: 'Q/ZPC 1-2017', emptyHint: '台账里还没有退货检验记录' },
  { value: 'env', kind: 'env', label: '生产环境检查 (ENV)', title: '生产环境检查记录 (ENV)', group: '环境卫生检查', basis: 'GB 31603-2015', emptyHint: '还没有环境检查单（可在 PC「安全卫生 → 生产环境检查」新建）' },
  { value: 'purchaseReceipt', kind: 'receipt', label: '采购入库单', title: '采购入库单', group: '采购管理', basis: 'Q/ZPC 1-2017', emptyHint: '还没有采购入库单（采购入库审核后才算受控记录）' }
]

const isEmpty = v => v === null || v === undefined || v === ''
const S = v => (isEmpty(v) ? '-' : v)
const fmtDate = v => (isEmpty(v) ? '-' : String(v).replace('T', ' ').slice(0, 16))
const fmtNum = v => (isEmpty(v) ? '-' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 3 }))
const qtyText = (qty, unit) => (isEmpty(qty) ? '-' : `${fmtNum(qty)}${unit ? ' ' + unit : ''}`)

function parsePhotos(field) {
  if (!field) return []
  if (Array.isArray(field)) return field.filter(Boolean)
  return photosFromJson(field)
}

function resultText(r) {
  return RESULT_TEXT[r] || S(r)
}

// ===== 统一纸张模型 (三类数据源 → 同一份版式) =====
function buildInspection(s, d) {
  const head = d.head || {}
  const meta = [
    ['产品 / 物料', S(head.productName)],
    ['规格型号', S(head.spec || head.model)],
    ['批次 / 批号', S(head.batchNo)],
    ['关联单据', S(head.sourceBillNo)],
    ['抽样 / 合格', `${S(head.sampleQty)} / ${S(head.qualifiedQty)}`],
    ['检验结论', resultText(head.result)],
    ['批次处置', DISPOSITION_TEXT[head.disposition] || S(head.disposition)],
    ['检验依据', s.basis]
  ]
  const workshopProcess = [head.workshop, d.processName].filter(Boolean).join(' · ')
  if (workshopProcess) meta.splice(4, 0, ['车间 / 工序', workshopProcess])
  if (head.unqualifiedQty != null) {
    meta[4] = ['抽样 / 合格 / 不良', `${S(head.sampleQty)} / ${S(head.qualifiedQty)} / ${S(head.unqualifiedQty)}`]
  }
  if (head.retainNo) meta.push(['留样编号', S(head.retainNo)])

  const items = d.items || []
  const defects = d.defects || []
  const attachments = d.attachments || []

  const itemRows = items.map((it, i) => [
    i + 1,
    S(it.itemName || it.itemCode),
    S(it.requirement),
    S(it.method),
    `${S(it.resultValue)}${it.unit ? ' ' + it.unit : ''}`,
    it.passFlag === 0 ? '不合格' : it.passFlag === 1 ? '合格' : Number(it.passFlag) === 2 ? '不适用' : '—'
  ])
  const defectRows = defects.map((x, i) => [
    `缺陷 ${i + 1}`, S(x.defectType), S(x.severity), S(x.qty), S(x.disposal)
  ])
  const useItems = itemRows.length > 0

  const photos = []
  parsePhotos(head.photoUrls).forEach((url, i) => photos.push({ url, caption: '检验照片 ' + (i + 1) }))
  defects.forEach(x => parsePhotos(x.photoUrl).forEach(url => photos.push({ url, caption: '不良品照片' })))
  const photoAtts = attachments.filter(a => /PHOTO/i.test(a.attachType || '') && a.fileUrl)
  photoAtts.forEach((a, i) => photos.push({ url: a.fileUrl, caption: a.attachName || ('现场照片 ' + (i + 1)) }))
  const otherAtts = attachments.filter(a => !/PHOTO/i.test(a.attachType || '') && a.attachName)
  if (otherAtts.length) meta.push(['检验附件', otherAtts.map(a => a.attachName).join('、')])

  const orderMaterials = d.orderMaterials || []
  return {
    title: s.title,
    code: S(head.billNo),
    group: s.group,
    statusText: head.status === 'CHECKED' ? '已审核 · 只读' : (BILL_STATUS_TEXT[head.status] || S(head.status)),
    audited: head.status === 'CHECKED',
    meta,
    materialHeaders: ['序号', '原料名称', '规格型号', '数量'],
    materials: orderMaterials.map((m, i) => [i + 1, S(m.productName), S(m.spec), qtyText(m.qty, m.unitName)]),
    headers: useItems
      ? ['序号', '检验项目', '技术要求', '检验方法', '实测值', '判定']
      : ['缺陷', '类型', '严重度', '数量', '处置'],
    rows: useItems ? itemRows : (defectRows.length ? defectRows : [['—', '暂无检验项明细', '—', '—', '—']]),
    photos,
    remark: head.remark || head.dispositionReason || '',
    signatures: [
      head.inspectorName ? `检验员：${head.inspectorName}` : '',
      head.reviewerName ? `复核人：${head.reviewerName}` : '',
      head.approverName ? `批准人：${head.approverName}` : ''
    ].filter(Boolean),
    createdText: fmtDate(head.checkTime || head.createTime),
    operatorText: S(head.inspectorName)
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
      ['环境类型', ENV_TYPE_TEXT[head.envType] || S(head.envType)],
      ['计划时间', fmtDate(head.plannedAt)],
      ['检查人', S(head.inspector)],
      ['问题等级', LEVEL_TEXT[head.level] || S(head.level)],
      ['整改负责人', S(head.rectifyOwner)],
      ['整改期限 / 状态', `${fmtDate(head.rectifyDeadline)} / ${RECTIFY_TEXT[head.rectifyStatus] || S(head.rectifyStatus)}`]
    ],
    headers: ['检查内容', '整改说明', '问题等级', '整改状态'],
    rows: [[S(head.checkContent), S(head.rectifyDesc),
      LEVEL_TEXT[head.level] || S(head.level),
      RECTIFY_TEXT[head.rectifyStatus] || S(head.rectifyStatus)]],
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

function buildReceipt(s, head) {
  const details = head.details || []
  return {
    title: s.title,
    code: S(head.billNo),
    group: s.group,
    statusText: head.billStatus === 'CHECKED' ? '已审核 · 只读' : (BILL_STATUS_TEXT[head.billStatus] || S(head.billStatus)),
    audited: head.billStatus === 'CHECKED' || head.billStatus === 'FINISHED',
    meta: [
      ['供应商', S(head.supplierName)],
      ['入库日期', fmtDate(head.billDate)],
      ['仓库', S(head.warehouseName)],
      ['关联订单', S(head.orderNo)],
      ['合计数量', fmtNum(head.totalQty)],
      ['检验结果', resultText(head.inspectionResult)]
    ],
    headers: ['商品名称', '批号', '数量', '库位', '质检'],
    rows: details.length
      ? details.map(d => [
        S(d.productName),
        S(d.batchNo),
        qtyText(d.qty, d.unitName),
        S(d.locationName || d.location),
        S(d.iqcResult || head.inspectionResult)
      ])
      : [['—', '—', '—', '—', '—']],
    photos: parsePhotos(head.labelPhotos).map((url, i) => ({ url, caption: '原料标签 / 到货照片 ' + (i + 1) })),
    remark: head.remark || '',
    signatures: [
      head.createByName ? `制单：${head.createByName}` : '',
      head.inspectorName ? `检验员：${head.inspectorName}` : '',
      head.warehouseName ? `仓库：${head.warehouseName}` : ''
    ].filter(Boolean),
    createdText: fmtDate(head.createTime),
    operatorText: S(head.createByName)
  }
}

export default {
  data() {
    return {
      types: DOC_TYPES,
      typeIndex: 0,
      records: [],
      recordIndex: 0,
      listLoading: false,
      listError: '',
      detailLoading: false,
      detailError: '',
      detail: null,
      retentionYears: '2',
      serverTimeText: '',
      brandName: '',
      brandEn: '',
      brandFactory: '',
      brandLogo: '',
      includePhotos: true,
      includeSign: true,
      includeServerTime: true,
      printing: false
    }
  },
  computed: {
    typeOptions() { return this.types.map(t => t.label) },
    currentType() { return this.types[this.typeIndex] },
    recordOptions() { return this.records.map(r => r.label) },
    currentRecord() { return this.records[this.recordIndex] || null },
    generatedText() { return this.serverTimeText || new Date().toLocaleString('zh-CN', { hour12: false }) },
    displayBrandName() { return this.brandName || '工业ERP' },
    displayBrandEn() { return this.brandEn || '' },
    displayBrandLogo() { return this.brandLogo || '/static/icons/icon-192.png' },
    printButtonText() {
      if (this.printing) return '正在调起打印...'
      return isNativeApp() ? '🖨 打印单据（系统打印）' : '🖨 打印单据'
    },
    paperDoc() {
      if (!this.detail) return null
      const s = this.currentType
      if (s.kind === 'inspection') return buildInspection(s, this.detail)
      if (s.kind === 'env') return buildEnv(s, this.detail.head || {})
      return buildReceipt(s, this.detail.head || {})
    },
    metaPairs() {
      if (!this.paperDoc) return []
      const out = []
      const meta = this.paperDoc.meta || []
      for (let i = 0; i < meta.length; i += 2) out.push([meta[i], meta[i + 1] || null])
      return out
    }
  },
  onShow() {
    if (!this.records.length) this.loadAll()
  },
  methods: {
    headClass(j) {
      return ['dt-th', j === 0 ? 'first' : '']
    },
    cellClass(j) {
      return ['dt-td', j === 0 ? 'first' : '']
    },
    async loadAll() {
      const p = await printPreview()
      if (p && p.code === 200) {
        this.retentionYears = p.data.retentionYears || '2'
        this.serverTimeText = p.data.serverTime ? String(p.data.serverTime).replace('T', ' ').slice(0, 16) : ''
        const b = p.data.brand || {}
        this.brandName = b.name || ''
        this.brandEn = b.enName || ''
        this.brandFactory = b.factory || ''
        this.brandLogo = b.logo || ''
      }
      await this.loadRecords()
    },
    async loadRecords() {
      this.listLoading = true
      this.listError = ''
      this.detail = null
      this.detailError = ''
      const r = await printDocs(this.currentType.value)
      if (r && r.code === 200) {
        this.records = (r.data && r.data.records) || []
        this.recordIndex = 0
        if (this.records.length) await this.loadDetail()
      } else {
        this.records = []
        this.listError = (r && r.msg) || '单据清单加载失败'
      }
      this.listLoading = false
    },
    async loadDetail() {
      const cur = this.currentRecord
      if (!cur) { this.detail = null; return }
      this.detailLoading = true
      this.detailError = ''
      const r = await printDocDetail(cur.docType || this.currentType.value, cur.id)
      if (r && r.code === 200) this.detail = r.data
      else { this.detail = null; this.detailError = (r && r.msg) || '单据明细加载失败' }
      this.detailLoading = false
    },
    onPickType(e) {
      const idx = Number(e.detail.value)
      if (isNaN(idx) || idx === this.typeIndex) return
      this.typeIndex = idx
      this.records = []
      this.recordIndex = 0
      this.detail = null
      this.loadRecords()
    },
    onPickRecord(e) {
      const idx = Number(e.detail.value)
      if (isNaN(idx) || idx === this.recordIndex) return
      this.recordIndex = idx
      this.loadDetail()
    },
    reload() {
      this.loadAll()
    },
    async printAuditLog() {
      if (!this.paperDoc || !this.paperDoc.code) return
      await printAudit(this.paperDoc.code)   // 无 xk:audit:archive 时静默失败, 不挡打印
    },
    async onPrint() {
      if (!this.paperDoc) {
        uni.showToast({ title: '请先选择一张单据', icon: 'none' })
        return
      }
      if (this.printing) return
      this.printing = true
      this.printAuditLog()
      try {
        if (isNativeApp()) {
          await NativePrint.printPage({ jobName: `${this.paperDoc.title} ${this.paperDoc.code}` })
        } else {
          await new Promise(resolve => setTimeout(resolve, 80))
          window.print()
        }
      } catch (e) {
        const msg = (e && (e.message || e.errMsg)) || '调起打印失败'
        uni.showToast({ title: msg, icon: 'none', duration: 2500 })
      } finally {
        this.printing = false
      }
    }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 100px; background: #f0f2f5; min-height: 100vh; }
.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.setting-block { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.setting-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; }
.link { color: #1e6091; font-size: 12px; }
.form-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed #eee; }
.form-row:last-child { border-bottom: none; }
.form-label { font-size: 13px; color: #555; }

.hint { font-size: 12px; color: #999; padding: 14px 0; text-align: center; line-height: 1.6; }
.hint.error { color: #c0392b; }

.picker-row { display: flex; align-items: center; justify-content: space-between; padding: 10px 2px 2px; border-top: 1px dashed #eee; }
.picker-value { font-size: 13px; color: #1e6091; flex: 1; word-break: break-all; }
.picker-arrow { font-size: 14px; color: #999; margin-left: 8px; }

.paper-preview { background: #e0e6ed; padding: 12px; border-radius: 6px; margin-bottom: 12px; }
.paper { background: #fff; padding: 20px 16px; border-radius: 4px; box-shadow: 0 2px 6px rgba(0,0,0,0.06); }
.paper-empty-note { font-size: 12px; color: #999; padding: 24px; text-align: center; background: #fff; border-radius: 4px; }
.paper-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.logo { display: flex; align-items: center; gap: 8px; }
.logo-icon { width: 32px; height: 32px; background: linear-gradient(135deg, #1e6091, #27ae60); border-radius: 4px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 16px; }
.logo-img { width: 32px; height: 32px; border-radius: 4px; }
.logo-name { font-size: 14px; font-weight: 700; color: #1e6091; }
.logo-en { font-size: 8px; color: #999; letter-spacing: 1px; }
.stamp { background: #27ae60; color: #fff; padding: 2px 8px; border-radius: 3px; font-size: 11px; }
.stamp-gray { background: #9aa5b1; }
.paper-title { text-align: center; font-size: 18px; font-weight: 700; color: #1e6091; margin: 4px 0; }
.paper-meta { text-align: center; font-size: 10px; color: #666; margin-bottom: 10px; }

.paper-table { background: #fafafa; border-radius: 4px; padding: 4px; margin-bottom: 8px; }
.pt-row { display: flex; padding: 4px 6px; border-bottom: 1px solid #eee; }
.pt-row:last-child { border-bottom: none; }
.pt-th { width: 20%; font-size: 11px; color: #888; }
.pt-td { width: 30%; font-size: 12px; color: #222; word-break: break-all; }

.detail-table { border: 1px solid #1e6091; border-radius: 4px; margin-bottom: 10px; overflow: hidden; }
.dt-head { display: flex; background: #1e6091; color: #fff; }
.dt-th { flex: 1; text-align: center; padding: 6px 4px; font-size: 11px; }
.dt-th.first { flex: 1.5; text-align: left; }
.dt-body { display: flex; background: #fff; border-top: 1px solid #eee; }
.dt-td { flex: 1; text-align: center; padding: 6px 4px; font-size: 11px; word-break: break-all; }
.dt-td.first { flex: 1.5; text-align: left; }

.photos-section { margin-bottom: 10px; }
.section-title { font-size: 12px; font-weight: 600; color: #1e6091; margin-bottom: 6px; }
.photo-row { display: flex; gap: 6px; flex-wrap: wrap; }
.photo-cell { flex: 1; min-width: 45%; border: 1px solid #e0e0e0; }
.photo { width: 100%; height: 120px; display: block; }
.photo-cap { text-align: center; font-size: 10px; color: #666; padding: 3px 0; background: #fafafa; }
.photo-empty-note { font-size: 11px; color: #999; padding: 10px; text-align: center; border: 1px dashed #ddd; border-radius: 4px; }

.note-block { background: #f9f9f9; padding: 6px 8px; border-radius: 4px; font-size: 11px; color: #555; margin-bottom: 10px; line-height: 1.5; }
.note-label { color: #1e6091; font-weight: 600; }

.sign-table { border-top: 1px solid #e0e0e0; padding-top: 8px; }
.sign-row { display: flex; flex-wrap: wrap; }
.sign-cell { flex: 1; min-width: 33%; text-align: center; font-size: 12px; color: #222; padding: 2px 0; }

.paper-footer { margin-top: 10px; padding-top: 6px; border-top: 1px solid #eee; font-size: 9px; color: #999; text-align: center; }
.paper-footer .block { display: block; margin-top: 2px; }

.bottom-actions { position: fixed; bottom: 60px; left: 0; right: 0; padding: 8px 12px; background: #fff; border-top: 1px solid #eee; z-index: 9; }
.btn-primary { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 10px; font-size: 14px; font-weight: 500; }
.btn-primary[disabled] { background: #9aa5b1; color: #fff; }
.footer-space { height: 20px; }

/* 打印: 只留纸面, 去掉操作区与 App 外壳 */
@page { size: A4 portrait; margin: 10mm; }
@media print {
  .no-print, uni-tabbar, uni-page-head { display: none !important; }
  .no-print-bg { background: #fff !important; padding: 0 !important; margin: 0 !important; }
  .container { background: #fff !important; padding: 0 !important; min-height: auto !important; }
  .paper { box-shadow: none !important; border-radius: 0 !important; padding: 0 !important; }
  .dt-head, .stamp { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .photos-section { page-break-inside: avoid; }
  .photo { max-height: 140px; }
  .paper-head, .dt-body, .pt-row { page-break-inside: avoid; }
}
</style>
