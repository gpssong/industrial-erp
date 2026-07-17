<template>
  <div class="designer-wrap">
    <div class="designer-header">
      <el-button @click="goBack"><el-icon><ArrowLeft /></el-icon>返回列表</el-button>
      <div class="meta">
        <span class="title">{{ headerTitle }}</span>
        <el-tag v-if="meta.bizType" size="small">{{ BIZ_TYPE_LABEL[meta.bizType] || meta.bizType }}</el-tag>
        <el-tag v-if="meta.paperWidth" size="small" type="info">{{ meta.paperWidth }} × {{ meta.paperHeight }} {{ meta.pageUnit }}</el-tag>
      </div>
      <div class="actions">
        <el-button @click="onEditMeta" v-if="id !== 'new'"><el-icon><Edit /></el-icon>编辑属性</el-button>
      </div>
    </div>

    <div v-if="loading" class="loading-wrap">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>
    <div v-else-if="!meta.bizType" class="missing-meta">
      <el-alert type="warning" :closable="false" show-icon>
        请先填写模板业务类型和名称, 然后再进入设计器
      </el-alert>
    </div>

    <DesignPanel
      v-else
      :template="templateRef"
      :save-template="handleSaveTemplate"
      :module="moduleRef"
      height="calc(100vh - 56px)"
      @back="goBack"
    />

    <!-- 编辑元数据弹窗 -->
    <el-dialog v-model="metaDialogVisible" title="编辑模板属性" width="640px" destroy-on-close>
      <el-form :model="meta" label-width="100px" :rules="metaRules" ref="metaFormRef">
        <el-form-item label="模板名称" prop="name">
          <el-input v-model="meta.name" />
        </el-form-item>
        <el-form-item label="业务类型" prop="bizType">
          <el-select v-model="meta.bizType" style="width:100%" :disabled="!!meta.id">
            <el-option v-for="o in BIZ_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="宽度"><el-input-number v-model="meta.paperWidth" :min="10" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="高度"><el-input-number v-model="meta.paperHeight" :min="10" controls-position="right" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="单位"><el-select v-model="meta.pageUnit" style="width:160px"><el-option label="mm" value="mm" /><el-option label="cm" value="cm" /><el-option label="in" value="in" /><el-option label="px" value="px" /></el-select></el-form-item>
        <el-form-item label="备注"><el-input v-model="meta.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSaveMeta" :loading="metaSaving">保存属性</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DesignPanel } from 'myprint-design'
import { printTemplateApi } from '@/api/system'
import { clearTemplateCache, BIZ_TYPE_LABEL } from '@/composables/usePrint'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const id = String(route.params.id || 'new')

const BIZ_OPTIONS = [
  { value: 'SAL_DELIVERY', label: BIZ_TYPE_LABEL.SAL_DELIVERY },
  { value: 'PUR_RECEIPT',  label: BIZ_TYPE_LABEL.PUR_RECEIPT },
  { value: 'PUR_RETURN',   label: BIZ_TYPE_LABEL.PUR_RETURN },
  { value: 'SAL_RETURN',   label: BIZ_TYPE_LABEL.SAL_RETURN },
  { value: 'PRD_ORDER',    label: BIZ_TYPE_LABEL.PRD_ORDER }
]

const loading = ref(true)
const saving = ref(false)
const metaDialogVisible = ref(false)
const metaSaving = ref(false)
const metaFormRef = ref(null)
const metaRules = {
  name: [{ required: true, message: '请填写模板名称', trigger: 'blur' }],
  bizType: [{ required: true, message: '请选择业务类型', trigger: 'change' }]
}

const meta = ref({
  id: null, name: '', bizType: '',
  paperWidth: 210, paperHeight: 297, pageUnit: 'mm',
  status: 1, isDefault: 0, remark: ''
})

/**
 * 直接把后端存的 JSON 字符串交给 DesignPanel (myprint-design 期望的形状它自己最清楚)。
 * 旧的 "parse + 字段覆盖 + stringify" round-trip 已被证实会让 elementList 在重入设计器时
 * 显示为空 — 因为 myprint-design v6 的 Fabric.js 画布依赖原始 JSON 形状, 任何 round-trip
 * 都会破坏它内部对 elementList 元素的状态识别。
 * 只有 "新建空白模板" 这条路径需要构造一个干净的初始 JSON (没有 d.content 可用)。
 */
function buildInitialTemplateContent(metaValue) {
  return JSON.stringify({
    name: metaValue.name || '',
    bizType: metaValue.bizType || '',
    pageUnit: metaValue.pageUnit || 'mm',
    width: Number(metaValue.paperWidth) || 210,
    height: Number(metaValue.paperHeight) || 297,
    elementList: []
  })
}

/**
 * 按 biz_type 构造 provider.elementList (业务字段, 可拖拽到画布的"数据字段"元素)
 * 每个元素都有 field, 拖到画布后绑定 previewData 中对应字段
 */
function buildProviderElementList(bizType) {
  const t = String(bizType || '').toUpperCase()
  const list = []

  // 通用字段
  list.push(
    { type: 'Text', field: 'billNo', label: '单据编号', width: 50, height: 8 },
    { type: 'Text', field: 'billDate', label: '单据日期', width: 50, height: 8 },
    { type: 'Text', field: 'remark', label: '备注', width: 80, height: 8 }
  )

  // 通用: 型号 (所有业务类型都有)
  list.push(
    { type: 'Text', field: 'model', label: '型号', width: 40, height: 8 }
  )

  if (t === 'SAL_DELIVERY' || t === 'SAL_RETURN') {
    list.push(
      { type: 'Text', field: 'customerName', label: '客户名称', width: 60, height: 8 },
      { type: 'Text', field: 'customerPhone', label: '客户电话', width: 50, height: 8 }
    )
  }
  if (t === 'PUR_RECEIPT' || t === 'PUR_RETURN') {
    list.push(
      { type: 'Text', field: 'supplierName', label: '供应商', width: 60, height: 8 },
      { type: 'Text', field: 'supplierPhone', label: '供应商电话', width: 50, height: 8 }
    )
  }
  if (t === 'SAL_DELIVERY' || t === 'PUR_RECEIPT' || t === 'SAL_RETURN' || t === 'PUR_RETURN') {
    list.push(
      { type: 'Text', field: 'warehouseName', label: '仓库', width: 50, height: 8 },
      { type: 'Text', field: 'totalQty', label: '合计数量', width: 30, height: 8 },
      { type: 'Text', field: 'totalAmount', label: '合计金额', width: 30, height: 8 },
      { type: 'Text', field: 'totalAmountTax', label: '价税合计', width: 30, height: 8 },
      { type: 'DataTable', field: 'details', label: '商品明细表', width: 180, height: 100,
        option: { fontFamily: 'heiti', fontSize: 11 },
        columnList: [
          { type: 'Text', field: 'lineNo', label: '序号', width: 10, height: 8 },
          { type: 'Text', field: 'productCode', label: '商品编码', width: 30, height: 8 },
          { type: 'Text', field: 'productName', label: '商品名称', width: 40, height: 8 },
          { type: 'Text', field: 'model', label: '型号', width: 35, height: 8 },
          { type: 'Text', field: 'spec', label: '规格', width: 25, height: 8 },
          { type: 'Text', field: 'unitName', label: '单位', width: 15, height: 8 },
          { type: 'Text', field: 'qty', label: '数量', width: 20, height: 8 },
          { type: 'Text', field: 'price', label: '单价', width: 25, height: 8 },
          { type: 'Text', field: 'amount', label: '金额', width: 25, height: 8 },
          { type: 'Text', field: 'batchNo', label: '批次', width: 25, height: 8 }
        ] },
      // myprint v6 期望 Barcode/QRCode 的外层 type 是 'Text', contentType 子类型区分,
      // 否则 print 路径 `else if (previewWrapper.type == 'Text' || ...)` 直接跳过不渲染
      { type: 'Text', contentType: 'Barcode', field: 'billNo', label: '条形码(单据号)', width: 60, height: 20,
        option: { barcodeFormat: 'CODE128' } },
      { type: 'Text', contentType: 'QrCode', field: 'billNo', label: '二维码(单据号)', width: 30, height: 30 }
    )
  }
  if (t === 'PRD_ORDER') {
    list.push(
      { type: 'Text', field: 'billNo', label: '单号', width: 50, height: 8 },
      { type: 'Text', field: 'billDate', label: '日期', width: 50, height: 8 },
      { type: 'Text', field: 'bomCode', label: 'BOM 编码', width: 50, height: 8 },
      { type: 'Text', field: 'bomName', label: 'BOM 名称', width: 60, height: 8 },
      { type: 'Text', field: 'productCode', label: '成品编码', width: 50, height: 8 },
      { type: 'Text', field: 'productName', label: '成品名称', width: 60, height: 8 },
      { type: 'Text', field: 'spec', label: '规格', width: 40, height: 8 },
      { type: 'Text', field: 'thickness', label: '长度', width: 30, height: 8 },
      { type: 'Text', field: 'width', label: '宽度', width: 30, height: 8 },
      { type: 'Text', field: 'density', label: '厚度', width: 30, height: 8 },
      { type: 'Text', field: 'gramWeight', label: '克重', width: 30, height: 8 },
      { type: 'Text', field: 'material', label: '材质', width: 30, height: 8 },
      { type: 'Text', field: 'planQty', label: '计划数量', width: 30, height: 8 },
      { type: 'Text', field: 'actualQty', label: '实际数量', width: 30, height: 8 },
      { type: 'Text', field: 'goodQty', label: '良品数量', width: 30, height: 8 },
      { type: 'Text', field: 'lossQty', label: '损耗数量', width: 30, height: 8 },
      { type: 'Text', field: 'lossRate', label: '损耗率%', width: 30, height: 8 },
      { type: 'Text', field: 'workshop', label: '车间', width: 40, height: 8 },
      { type: 'Text', field: 'leader', label: '负责人', width: 30, height: 8 },
      { type: 'Text', field: 'startDate', label: '开工日期', width: 50, height: 8 },
      { type: 'Text', field: 'endDate', label: '结束日期', width: 50, height: 8 },
      { type: 'Text', field: 'remark', label: '备注', width: 80, height: 8 },
      { type: 'DataTable', field: 'requisitionDetails', label: '领料明细', width: 180, height: 80,
        option: { fontFamily: 'heiti', fontSize: 11 },
        columnList: [
          { type: 'Text', field: 'lineNo', label: '行号', width: 10, height: 8 },
          { type: 'Text', field: 'materialType', label: '类型', width: 15, height: 8 },
          { type: 'Text', field: 'productCode', label: '物料编码', width: 25, height: 8 },
          { type: 'Text', field: 'productName', label: '物料名称', width: 35, height: 8 },
          { type: 'Text', field: 'unitName', label: '单位', width: 12, height: 8 },
          { type: 'Text', field: 'qty', label: '数量', width: 18, height: 8 },
          { type: 'Text', field: 'price', label: '单价', width: 18, height: 8 },
          { type: 'Text', field: 'amount', label: '金额', width: 18, height: 8 },
          { type: 'Text', field: 'batchNo', label: '批次', width: 22, height: 8 },
          { type: 'Text', field: 'remark', label: '备注', width: 25, height: 8 }
        ] },
      { type: 'Text', contentType: 'Barcode', field: 'billNo', label: '条形码(单号)', width: 60, height: 20,
        option: { barcodeFormat: 'CODE128' } },
      { type: 'Text', contentType: 'QrCode', field: 'billNo', label: '二维码(单号)', width: 30, height: 30 }
    )
  }
  return list
}

/**
 * 给设计器一份 bizType 对应的示例 previewData (修 #1).
 * 字段命名跟 usePrint.js 的 HEADER_MAP / DETAIL_MAP 完全一致, designer/preview/print 三方共享同一份词表.
 * 拖入的 Text 元素 (含合计数量 / 合计金额) 在画布里立刻能看到内容, 不用每次跑 doPrint 来验证.
 */
function buildSamplePreviewData(bizType) {
  const t = String(bizType || '').toUpperCase()
  const sampleDetail = (lineNo, opts = {}) => ({
    lineNo,
    productCode: opts.code || 'P0001',
    productName: opts.name || '示例塑料薄膜 28μ',
    model: opts.model || 'M-001',
    spec: opts.spec || '28*36*0.16',
    unitName: opts.unit || '卷',
    qty: opts.qty != null ? opts.qty : 50.5,
    price: opts.price != null ? opts.price : 25.00,
    amount: opts.amount != null ? opts.amount : 1262.50,
    taxRate: 13,
    batchNo: opts.batch || 'B2026-001',
    locationName: opts.location || 'A-01-01'
  })
  const salesPreview = {
    billNo: 'SAL-2026-0001',
    billDate: '2026-07-16',
    customerName: '示例客户有限公司',
    customerPhone: '021-12345678',
    warehouseName: '主仓',
    address: '上海市浦东新区张江路 100 号',
    phone: '021-12345678',
    totalQty: 100.50,
    totalAmount: 5025.00,
    totalAmountTax: 5025.00,
    remark: '含税价. 仅作设计器示例, 不影响实际打印.'
  }
  const map = {
    SAL_DELIVERY: {
      ...salesPreview,
      details: [
        sampleDetail(1, { amount: 1262.50 }),
        sampleDetail(2, { code: 'P0002', name: '示例涂层膜 50μ', model: 'M-002', spec: '50*0.20', qty: 50, price: 75.25, amount: 3762.50, batch: 'B2026-002', location: 'A-01-02' })
      ]
    },
    SAL_RETURN: {
      ...salesPreview, billNo: 'SRT-2026-0001',
      details: [sampleDetail(1, { code: 'P0003', name: '退货示例品', qty: -10, amount: -250 })]
    },
    PUR_RECEIPT: {
      ...salesPreview, billNo: 'PUR-2026-0001',
      supplierName: '示例供应商有限公司', supplierPhone: '0755-87654321',
      details: [sampleDetail(1, { name: '示例原材料卷 28μ', batch: 'B2026-001' })]
    },
    PUR_RETURN: {
      ...salesPreview, billNo: 'PRT-2026-0001',
      supplierName: '示例供应商有限公司', supplierPhone: '0755-87654321',
      details: [sampleDetail(1, { name: '退货示例材料', qty: -5, amount: -125 })]
    },
    PRD_ORDER: {
      billNo: 'PRD-2026-0001',
      billDate: '2026-07-16',
      bomCode: 'BOM-2026-001',
      bomName: '示例复合膜 BOM',
      productCode: 'FP-001',
      productName: '复合膜成品',
      spec: '28*36*0.16',
      thickness: 0.16,
      width: 36,
      density: 0.92,
      gramWeight: 28,
      material: 'PE',
      planQty: 1000,
      actualQty: 850,
      goodQty: 820,
      lossQty: 30,
      lossRate: 3.53,
      workshop: '一车间',
      leader: '张组长',
      startDate: '2026-07-10',
      endDate: '2026-07-15',
      remark: '示例生产单数据, 不影响实际打印.',
      requisitionDetails: [
        { lineNo: 1, materialType: '主料', productCode: 'ldpe2426h', productName: 'LDPE 2426H', unitName: 'kg', qty: 850, price: 8.50, amount: 7225, batchNo: 'B2026-001', remark: '' },
        { lineNo: 2, materialType: '主料', productCode: 'lldpe7050', productName: 'LLDPE 7050', unitName: 'kg', qty: 150, price: 9.20, amount: 1380, batchNo: 'B2026-001', remark: '' },
        { lineNo: 3, materialType: '辅料', productCode: 'add001', productName: '示例添加剂', unitName: 'kg', qty: 5, price: 25, amount: 125, batchNo: '', remark: '开口剂' }
      ]
    }
  }
  return map[t] || {}
}

/**
 * 把老模板库里误存的 {type:'Barcode'/'QRCode'} (外层 type 但没 contentType) 归一化成
 * myprint v6 期望的 {type:'Text', contentType:'Barcode'/'QrCode'} (修 #3).
 * 与 usePrint.js 里的 normalizePanel 同样的递归结构, 用于设计器加载 / 保存路径.
 */
function normalizeElementsDeep(panel) {
  if (!panel || typeof panel !== 'object') return panel
  const walk = (el) => {
    if (!el || typeof el !== 'object') return
    if (el.type === 'Barcode' && !el.contentType) { el.type = 'Text'; el.contentType = 'Barcode' }
    else if ((el.type === 'QRCode' || el.type === 'QrCode') && !el.contentType) { el.type = 'Text'; el.contentType = 'QrCode' }
    for (const k of ['tableHeadList', 'tableBodyList', 'statisticsList', 'elementList']) {
      if (Array.isArray(el[k])) {
        for (const row of el[k]) {
          if (Array.isArray(row)) for (const c of row) walk(c)
          else if (row && typeof row === 'object') walk(row)
        }
      }
    }
  }
  if (Array.isArray(panel.elementList)) for (const el of panel.elementList) walk(el)
  if (panel.pageHeader) walk(panel.pageHeader)
  if (panel.pageFooter) walk(panel.pageFooter)
  return panel
}

/** 加载到的 content 是 JSON string. 解析后归一化老元素 shape, 再 stringify 回. */
function normalizeLoadedContent(content) {
  if (!content) return buildInitialTemplateContent(meta.value)
  let parsed
  try { parsed = JSON.parse(content) } catch (e) { return content }
  normalizeElementsDeep(parsed)
  return JSON.stringify(parsed)
}

/** 保存时归一化一次. 老元素已经含 type/contentType 的, 是 no-op. */
function normalizeForSave(content) {
  if (!content) return content
  let parsed
  try { parsed = JSON.parse(content) } catch (e) { return content }
  normalizeElementsDeep(parsed)
  return JSON.stringify(parsed)
}

const templateRef = ref({ name: '', content: buildInitialTemplateContent(meta.value) })
function buildModuleRef(metaValue) {
  return {
    provider: JSON.stringify({
      width: Number(metaValue.paperWidth) || 210,
      height: Number(metaValue.paperHeight) || 297,
      pageUnit: metaValue.pageUnit || 'mm',
      elementList: buildProviderElementList(metaValue.bizType)
    }),
    // 修 #1: 给设计器一份 bizType 对应的示例 previewData, 让拖入的字段立刻有内容可预览
    previewData: JSON.stringify([buildSamplePreviewData(metaValue.bizType)])
  }
}
const moduleRef = ref(buildModuleRef(meta.value))

const headerTitle = computed(() => meta.value.name || (id === 'new' ? '新模板' : '加载中'))

function goBack() {
  router.push('/system/print-template')
}

/**
 * 同步 meta 到 designer 的两个 ref:
 * - templateRef.content 直接用 d.content (不再 round-trip), 保证重入时不丢 element
 * - moduleRef 只在 bizType / 纸张相关字段变化时重建, 避免无谓触发 myprint-design 的
 *   props.module 监听导致 panel.elementList 被间接 reset
 */
function applyToDesigner({ content, meta: m }) {
  templateRef.value = { name: m.name, content: normalizeLoadedContent(content) }
}

// 监听"会影响画布尺寸 / palette"的字段, 重建 moduleRef
// 只在保存元数据或纸张变更时才有意义 — 加载阶段 loadTemplate 自己负责同步
watch(() => [
  meta.value.bizType,
  meta.value.paperWidth,
  meta.value.paperHeight,
  meta.value.pageUnit
], () => {
  moduleRef.value = buildModuleRef(meta.value)
})

async function loadTemplate() {
  loading.value = true
  try {
    if (id === 'new') {
      // 新模板: 自动创建一份"未命名模板"在 DB, 然后 replace 到新 ID
      // (需要 ID 才能在浏览器刷新后保留; 不创建的话保存会一直走 add 分支覆盖历史)
      meta.value = {
        id: null, name: '未命名模板', bizType: 'SAL_DELIVERY',
        paperWidth: 210, paperHeight: 297, pageUnit: 'mm',
        status: 1, isDefault: 0, remark: ''
      }
      // 直接保存一份空模板 (用最小 panel JSON) 然后跳到带 ID 的 URL
      const r = await printTemplateApi.add({
        name: meta.value.name, bizType: meta.value.bizType,
        paperWidth: meta.value.paperWidth, paperHeight: meta.value.paperHeight,
        pageUnit: meta.value.pageUnit, status: 1, isDefault: 0,
        content: buildInitialTemplateContent(meta.value)
      })
      const newId = r && r.data ? r.data : null
      if (newId) {
        router.replace(`/system/print-template/designer/${newId}`)
        return // 走加载分支重新加载
      }
      // 后端返回失败, 退化为 local mode — 用初始内容, 不重建 moduleRef (避免空 bizType 干扰 palette)
      applyToDesigner({ content: buildInitialTemplateContent(meta.value), meta: meta.value })
      return
    }
    const r = await printTemplateApi.detail(id)
    if (!r || !r.data) {
      ElMessage.error('模板不存在')
      goBack()
      return
    }
    const d = r.data
    // meta 先 set (会触发画布尺寸 watcher 重建 moduleRef, 此时 panel 还空)
    meta.value = {
      id: d.id,
      name: d.name || '未命名模板',
      bizType: d.bizType,
      paperWidth: Number(d.paperWidth) || 210,
      paperHeight: Number(d.paperHeight) || 297,
      pageUnit: d.pageUnit || 'mm',
      status: d.status ?? 1,
      isDefault: d.isDefault ?? 0,
      remark: d.remark || ''
    }
    // 直接把 d.content 写回去 — 不再做 JSON.parse + 字段覆盖 + stringify 的 round-trip,
    // myprint-design 自己 JSON.parse(props.template.content) 之后 Object.assign 到 panel
    applyToDesigner({ content: d.content || buildInitialTemplateContent(meta.value), meta: meta.value })
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onEditMeta() {
  metaDialogVisible.value = true
}

async function onSaveMeta() {
  await metaFormRef.value.validate()
  metaSaving.value = true
  try {
    // 元数据保存: 把当前模板内容也带上 (覆盖)
    const payload = {
      id: meta.value.id,
      name: meta.value.name,
      bizType: meta.value.bizType,
      paperWidth: meta.value.paperWidth,
      paperHeight: meta.value.paperHeight,
      pageUnit: meta.value.pageUnit,
      status: meta.value.status,
      isDefault: meta.value.isDefault,
      remark: meta.value.remark,
      content: normalizeForSave(templateRef.value.content)
    }
    await printTemplateApi.update(payload)
    ElMessage.success('属性已保存')
    metaDialogVisible.value = false
    clearTemplateCache(payload.bizType)
    // 属性保存后, 重建一次 moduleRef 让 palette 刷新 (画布尺寸变化), 不动 templateRef
    moduleRef.value = buildModuleRef(meta.value)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    metaSaving.value = false
  }
}

/**
 * DesignPanel 调用: saveTemplate(template) -> 必须返回 Promise (resolve=SUCCESS, reject=ERROR)
 * template = { name, content: JSONString }
 */
async function handleSaveTemplate(tpl) {
  if (!meta.value.id) {
    ElMessage.error('模板未初始化, 请刷新页面重试')
    return Promise.reject({ msg: '模板ID为空' })
  }
  if (!meta.value.name || !meta.value.bizType) {
    ElMessage.warning('请先填写模板名称和业务类型')
    return Promise.reject({ msg: '模板名称或业务类型为空' })
  }
  saving.value = true
  try {
    const payload = {
      id: meta.value.id,
      name: meta.value.name,
      bizType: meta.value.bizType,
      paperWidth: meta.value.paperWidth,
      paperHeight: meta.value.paperHeight,
      pageUnit: meta.value.pageUnit,
      status: meta.value.status,
      isDefault: meta.value.isDefault,
      remark: meta.value.remark,
      content: tpl.content
    }
    await printTemplateApi.update(payload)
    clearTemplateCache(payload.bizType)
    ElMessage.success('保存成功')
    return Promise.resolve({ status: 'SUCCESS' })
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
    return Promise.reject({ status: 'ERROR', msg: e.message || '保存失败' })
  } finally {
    saving.value = false
  }
}

onMounted(loadTemplate)
</script>
<style scoped>
.designer-wrap { display: flex; flex-direction: column; height: 100vh; background: #fff; }
.designer-header {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 16px; border-bottom: 1px solid #ebeef5; background: #fafbfc;
}
.designer-header .meta { display: flex; align-items: center; gap: 8px; flex: 1; }
.designer-header .title { font-weight: 600; font-size: 15px; color: #303133; }
.designer-header .actions { display: flex; gap: 8px; }
.loading-wrap, .missing-meta {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.missing-meta { display: block; }
</style>
