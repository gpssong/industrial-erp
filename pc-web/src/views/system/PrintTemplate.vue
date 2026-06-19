<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="模板名称"><el-input v-model="query.templateName" clearable /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.templateType" clearable style="width:120px">
            <el-option label="销售出库" value="SAL_DELIVERY" />
            <el-option label="采购入库" value="PUR_RECEIPT" />
            <el-option label="生产单" value="PRD_ORDER" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增模板</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="templateName" label="模板名称" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ typeMap[row.templateType] || row.templateType }}</template>
        </el-table-column>
        <el-table-column label="纸张" width="90">
          <template #default="{ row }">{{ getPaperLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="默认" width="70">
          <template #default="{ row }"><el-tag v-if="row.isDefault" size="small" type="success">默认</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="onPreview(row)">预览</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模板' : '新增模板'" width="1100px" top="3vh" destroy-on-close>
      <div class="dialog-body">
        <!-- 左侧：模板编辑 -->
        <div class="editor-area">
          <div class="editor-toolbar">
            <el-form :model="form" inline size="small" style="flex:1">
              <el-form-item label="模板名称" style="margin-bottom:0">
                <el-input v-model="form.templateName" style="width:140px" />
              </el-form-item>
              <el-form-item label="单据类型" style="margin-bottom:0">
                <el-select v-model="form.templateType" :disabled="!!form.id" style="width:130px" @change="onTypeChange">
                  <el-option label="销售出库" value="SAL_DELIVERY" />
                  <el-option label="采购入库" value="PUR_RECEIPT" />
                  <el-option label="生产单" value="PRD_ORDER" />
                </el-select>
              </el-form-item>
              <el-form-item label="纸张" style="margin-bottom:0">
                <el-select v-model="cfg.paperSize" style="width:110px">
                  <el-option v-for="p in paperSizes" :key="p.value" :label="p.label" :value="p.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="标题文字" style="margin-bottom:0">
                <el-input v-model="cfg.title" style="width:110px" />
              </el-form-item>
              <el-form-item style="margin-bottom:0">
                <el-checkbox v-model="cfg.showSignature">签字栏</el-checkbox>
              </el-form-item>
              <el-form-item label="设为默认" style="margin-bottom:0">
                <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
              </el-form-item>
            </el-form>
          </div>

          <!-- 模板文本区 -->
          <div class="template-editor">
            <div class="editor-label">📝 模板内容（用 {{}} 包裹字段名）</div>
            <el-input
              v-model="cfg.template"
              type="textarea"
              :rows="14"
              placeholder="示例:
=== 表头 ===
单号: {{billNo}}
日期: {{billDate}}
供应商: {{supplierName}}

=== 明细表头 ===
商品名 | 规格 | 数量 | 单价 | 金额

=== 明细循环 ===
{{#details}}
{{productName}} | {{spec}} | {{qty}} | {{price}} | {{amount}}
{{/details}}

=== 表尾 ===
合计: {{totalAmount}}"
              style="font-family:Consolas,monospace;font-size:13px"
              @input="updatePreview"
            />
          </div>

          <!-- 实时预览 -->
          <div class="editor-preview">
            <div class="editor-label">👁 打印预览</div>
            <div class="preview-page" :style="previewStyle">
              <div class="prev-title">{{ cfg.title || '单据' }}</div>
              <div class="prev-content" v-html="previewHtml" />
              <div class="prev-sign" v-if="cfg.showSignature">仓管签字:_______________</div>
            </div>
          </div>
        </div>

        <!-- 右侧：字段参考 -->
        <div class="field-ref">
          <div class="ref-title">📋 插入字段 · {{ typeMap[form.templateType] }}</div>
          <el-tabs v-model="refTab" size="small" class="ref-tabs">
            <el-tab-pane v-for="grp in fieldGroups" :key="grp.name" :label="grp.label" :name="grp.name">
              <div class="field-list">
                <div v-for="f in grp.fields" :key="f.value" class="field-item" @click="insertField(f.value)">
                  <span class="field-name">{{ f.value }}</span>
                  <span class="field-label">{{ f.label }}</span>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
          <div class="ref-hint">
            <div style="font-weight:600;margin-bottom:6px;color:#e6a23c">使用说明</div>
            <div><b v-pre>{{fieldName}}</b> — 插入字段值</div>
            <div><b v-pre>{{#details}}</b> — 开始明细循环</div>
            <div><b v-pre>{{/details}}</b> — 结束明细循环</div>
            <div style="margin-top:6px;color:#909399;font-size:11px">点击字段名即可插入到模板中</div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 预览弹窗 -->
    <el-dialog v-model="previewVisible" title="模板预览" width="600px">
      <iframe v-if="previewUrl" :src="previewUrl" style="width:100%;height:600px;border:0" />
      <div v-else style="text-align:center;color:#999;padding:40px">请先选择一条单据进行预览</div>
      <template #footer><el-button @click="previewVisible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, computed } from 'vue'
import { printApi } from '@/api/system'
import { purReceiptApi } from '@/api/purchase'
import { salDeliveryApi } from '@/api/sales'
import { prdOrderApi } from '@/api/production'
import { ElMessage, ElMessageBox } from 'element-plus'

const typeMap = { SAL_DELIVERY: '销售出库', PUR_RECEIPT: '采购入库', PRD_ORDER: '生产单' }
const paperSizes = [
  { value: 'P76', label: '76mm小票' },
  { value: 'P80', label: '80mm小票' },
  { value: 'P241', label: '241mm三等分针式' },
  { value: 'A5', label: 'A5纸' },
  { value: 'A4', label: 'A4纸' },
]

// ========== 字段定义 ==========
const allFields = {
  // 通用
  common: [
    { value: 'billNo', label: '单号' }, { value: 'billDate', label: '单据日期' },
    { value: 'remark', label: '备注' },
  ],
  // 销售出库
  sal: [
    { value: 'customerName', label: '客户名称' }, { value: 'customerCode', label: '客户编码' },
    { value: 'warehouseName', label: '仓库' }, { value: 'salesmanName', label: '业务员' },
    { value: 'address', label: '收货地址' }, { value: 'phone', label: '收货电话' },
    { value: 'deliveryDate', label: '交货日期' }, { value: 'payType', label: '付款方式' },
    { value: 'discountAmount', label: '整单折扣' }, { value: 'tailAmount', label: '抹零' },
  ],
  // 采购入库
  pur: [
    { value: 'supplierName', label: '供应商名称' }, { value: 'supplierCode', label: '供应商编码' },
    { value: 'warehouseName', label: '仓库' }, { value: 'buyerName', label: '采购员' },
    { value: 'orderNo', label: '采购订单号' }, { value: 'payType', label: '付款方式' },
  ],
  // 生产单
  prd: [
    { value: 'bomNo', label: 'BOM编号' }, { value: 'productName', label: '产品名称' },
    { value: 'productCode', label: '产品编码' }, { value: 'spec', label: '规格' },
    { value: 'unitName', label: '单位' }, { value: 'planQty', label: '计划数量' },
    { value: 'actualQty', label: '实际数量' }, { value: 'goodQty', label: '合格数量' },
    { value: 'lossQty', label: '损耗数量' }, { value: 'lossRate', label: '损耗率' },
    { value: 'workshop', label: '车间' }, { value: 'leader', label: '负责人' },
    { value: 'startDate', label: '开工日期' }, { value: 'endDate', label: '完工日期' },
    { value: 'billStatus', label: '单据状态' },
  ],
  // 明细（通用）
  detail: [
    { value: 'lineNo', label: '行号' }, { value: 'productName', label: '商品名称' },
    { value: 'productCode', label: '商品编码' }, { value: 'spec', label: '规格' },
    { value: 'unitName', label: '单位' }, { value: 'qty', label: '数量' },
    { value: 'price', label: '单价(含税)' }, { value: 'priceEx', label: '不含税单价' },
    { value: 'amount', label: '金额' }, { value: 'amountTax', label: '含税金额' },
    { value: 'taxRate', label: '税率%' }, { value: 'taxAmount', label: '税额' },
    { value: 'batchNo', label: '批次' }, { value: 'locationName', label: '库位' },
    { value: 'snNo', label: '序列号' }, { value: 'remark', label: '备注' },
  ],
  // 表尾
  footer: [
    { value: 'totalQty', label: '合计数量' }, { value: 'totalAmount', label: '不含税金额' },
    { value: 'taxAmount', label: '税额' }, { value: 'totalAmountTax', label: '价税合计' },
    { value: 'costAmount', label: '成本金额' }, { value: 'profitAmount', label: '毛利' },
    { value: 'discountAmount', label: '整单折扣' }, { value: 'tailAmount', label: '抹零' },
  ],
}

function getFieldGroups(type) {
  const g = (fields) => ({ name: fields, label: fields === 'common' ? '通用' : fields === 'detail' ? '明细' : fields === 'footer' ? '表尾' : type === 'SAL_DELIVERY' ? '销售' : type === 'PUR_RECEIPT' ? '采购' : '生产', fields: allFields[fields] })
  if (type === 'SAL_DELIVERY') return [g('common'), g('sal'), g('detail'), g('footer')]
  if (type === 'PUR_RECEIPT') return [g('common'), g('pur'), g('detail'), g('footer')]
  return [g('common'), g('prd')]
}

const fieldGroups = computed(() => getFieldGroups(form.value.templateType))

// ========== 模板配置 ==========
const cfg = ref({
  paperSize: 'P76',
  title: '单据',
  showSignature: true,
  template: `单号: {{billNo}}
日期: {{billDate}}
供应商: {{supplierName}}
仓库: {{warehouseName}}

=== 商品明细 ===
商品名 | 规格 | 数量 | 单价 | 金额
{{#details}}
{{productName}} | {{spec}} | {{qty}} | {{price}} | {{amount}}
{{/details}}

合计数量: {{totalQty}}
合计金额: {{totalAmount}}`,
})

// ========== 预览 ==========
const previewHtml = ref('')

const previewWidth = computed(() => ({ P76: 76, P80: 80, P241: 241, A5: 148, A4: 210 }[cfg.value.paperSize] || 76))

const previewStyle = computed(() => {
  const mm = previewWidth.value
  const maxPx = 260
  const scale = Math.min(1, maxPx / mm)
  return {
    width: mm + 'mm',
    transform: `scale(${scale})`,
    transformOrigin: 'top center',
    marginBottom: `${(1 - scale) * 40}px`,
  }
})

function updatePreview() {
  const tpl = cfg.value.template || ''
  let html = escHtml(cfg.value.title || '单据')
  // 简单解析：{{#details}}...{{/details}} 循环
  const detailMatch = tpl.match(/\{\{#details\}\}([\s\S]*?)\{\{\/details\}\}/)
  let detailRows = ''
  if (detailMatch) {
    const rowTpl = detailMatch[1]
    const demoDetail = [
      { lineNo: 1, productName: '商品A', spec: '规格A', qty: '10.0000', price: '128.0000', amount: '1280.0000' },
      { lineNo: 2, productName: '商品B', spec: '规格B', qty: '5.0000', price: '88.0000', amount: '440.0000' },
    ]
    demoDetail.forEach(d => {
      let row = rowTpl
      Object.keys(d).forEach(k => { row = row.replace(new RegExp(`\\{\\{${k}\\}\\}`, 'g'), d[k]) })
      detailRows += row
    })
  }
  let content = tpl
    .replace(/\{\{#details\}\}[\s\S]*?\{\{\/details\}\}/g, detailRows)
    .replace(/\{\{([^}]+)\}\}/g, (_, k) => demoVal(k.trim()))
    .replace(/===.*?===/g, '')
  previewHtml.value = content.split('\n').map(l => `<div>${escHtml(l) || '&nbsp;'}</div>`).join('')
}

function demoVal(k) {
  const m = {
    billNo: 'CG-20240618-001', billDate: '2024-06-18', supplierName: '示例供应商',
    customerName: '示例客户', warehouseName: '中心仓库', address: '北京市朝阳区',
    phone: '13800138000', remark: '备注', salesmanName: '张三', buyerName: '李四',
    deliveryDate: '2024-06-25', payType: '月结', discountAmount: '0.00', tailAmount: '0.00',
    orderNo: 'CG-20240601-001', bomNo: 'BOM-001', productName: '产品A',
    productCode: 'P001', spec: '规格A', unitName: '台', planQty: '100.0000',
    actualQty: '98.0000', goodQty: '96.0000', lossQty: '2.0000', lossRate: '2.00',
    workshop: '一车间', leader: '王五', startDate: '2024-06-18', endDate: '2024-06-28',
    billStatus: '生产中', totalQty: '15.0000', totalAmount: '1720.0000',
    taxAmount: '223.60', totalAmountTax: '1943.60', costAmount: '1500.00', profitAmount: '220.00',
  }
  return m[k] || '—'
}

function escHtml(s) {
  return (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// ========== 插入字段 ==========
const refTab = ref('common')
let textareaEl = null

function insertField(fieldName) {
  const ta = document.querySelector('.template-editor .el-textarea__inner')
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const before = cfg.value.template.slice(0, start)
  const after = cfg.value.template.slice(end)
  cfg.value.template = before + '{{' + fieldName + '}}' + after
  updatePreview()
}

// ========== 数据 ==========
const query = reactive({ pageNum: 1, pageSize: 20, templateName: '', templateType: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const previewVisible = ref(false)
const submitting = ref(false)
const previewUrl = ref('')
const form = ref({ id: null, templateName: '', templateType: 'SAL_DELIVERY', paperWidth: 76, paperHeight: 120, content: '', isDefault: 0 })

function getPaperLabel(row) {
  const w = row.paperWidth || 76
  if (w <= 76) return '76mm小票'
  if (w <= 80) return '80mm小票'
  if (w <= 148) return 'A5纸'
  if (w >= 241) return '241mm三等分针式'
  return 'A4纸'
}

function loadCfgFromContent(content) {
  if (!content || !content.trim().startsWith('{')) {
    cfg.value = {
      paperSize: 'P76', title: '单据', showSignature: true,
      template: getDefaultTemplate(form.value.templateType),
    }
    return
  }
  try {
    cfg.value = JSON.parse(content)
  } catch {
    cfg.value = { paperSize: 'P76', title: '单据', showSignature: true, template: getDefaultTemplate(form.value.templateType) }
  }
}

function getDefaultTemplate(type) {
  if (type === 'SAL_DELIVERY') return `单号: {{billNo}}
日期: {{billDate}}
客户: {{customerName}}
仓库: {{warehouseName}}
地址: {{address}}
电话: {{phone}}

=== 商品明细 ===
商品名 | 规格 | 数量 | 单价 | 金额
{{#details}}
{{productName}} | {{spec}} | {{qty}} | {{price}} | {{amount}}
{{/details}}

不含税金额: {{totalAmount}}
税额: {{taxAmount}}
价税合计: {{totalAmountTax}}`
  if (type === 'PUR_RECEIPT') return `单号: {{billNo}}
日期: {{billDate}}
供应商: {{supplierName}}
仓库: {{warehouseName}}
采购员: {{buyerName}}

=== 商品明细 ===
商品名 | 规格 | 数量 | 单价 | 金额
{{#details}}
{{productName}} | {{spec}} | {{qty}} | {{price}} | {{amount}}
{{/details}}

不含税金额: {{totalAmount}}
税额: {{taxAmount}}
价税合计: {{totalAmountTax}}`
  return `单号: {{billNo}}
日期: {{billDate}}
产品: {{productName}}
BOM: {{bomNo}}
车间: {{workshop}} | 负责人: {{leader}}

计划数量: {{planQty}} | 实际: {{actualQty}}
合格数量: {{goodQty}} | 损耗: {{lossQty}}`
}

function contentFromCfg() {
  return JSON.stringify({ paperSize: cfg.value.paperSize, title: cfg.value.title, showSignature: cfg.value.showSignature, template: cfg.value.template })
}

async function loadData() {
  loading.value = true
  try { data.value = (await printApi.page(query)).data } finally { loading.value = false }
}

function onAdd() {
  form.value = { id: null, templateName: '', templateType: 'SAL_DELIVERY', paperWidth: 76, paperHeight: 120, content: '', isDefault: 0 }
  cfg.value = { paperSize: 'P76', title: '销售出库单', showSignature: true, template: getDefaultTemplate('SAL_DELIVERY') }
  updatePreview()
  dialogVisible.value = true
}

function onEdit(row) {
  form.value = { ...row }
  loadCfgFromContent(row.content)
  updatePreview()
  dialogVisible.value = true
}

function onTypeChange(type) {
  cfg.value.title = typeMap[type] || ''
  cfg.value.template = getDefaultTemplate(type)
  updatePreview()
}

async function onPreview(row) {
  // 获取真实单据ID
  let realId = null
  try {
    if (row.templateType === 'PUR_RECEIPT') {
      const res = await purReceiptApi.page({ pageNum: 1, pageSize: 1 })
      realId = res.data?.records?.[0]?.id
    } else if (row.templateType === 'SAL_DELIVERY') {
      const res = await salDeliveryApi.page({ pageNum: 1, pageSize: 1 })
      realId = res.data?.records?.[0]?.id
    } else if (row.templateType === 'PRD_ORDER') {
      const res = await prdOrderApi.page({ pageNum: 1, pageSize: 1 })
      realId = res.data?.records?.[0]?.id
    }
  } catch (e) { /* ignore */ }

  if (!realId) { ElMessage.warning('暂无单据数据，请先创建后再预览'); return }
  const typeToPath = { SAL_DELIVERY: 'sales-delivery', PUR_RECEIPT: 'purchase-receipt', PRD_ORDER: 'prd-order' }
  const path = typeToPath[row.templateType] || row.templateType.toLowerCase().replaceAll('_', '-')
  previewUrl.value = `http://localhost:8080/api/print/${path}/${realId}.html?token=${localStorage.getItem('erp_token')}&_t=${Date.now()}`
  previewVisible.value = true
}

async function onSubmit() {
  if (!form.value.templateName) { ElMessage.warning('请填写模板名称'); return }
  submitting.value = true
  try {
    form.value.content = contentFromCfg()
    form.value.paperWidth = { P76: 76, P80: 80, P241: 241, A5: 148, A4: 210 }[cfg.value.paperSize] || 76
    form.value.paperHeight = 0
    if (form.value.id) await printApi.update(form.value)
    else await printApi.add(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { submitting.value = false }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除 "${row.templateName}"?`, '提示', { type: 'warning' })
  await printApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }

.dialog-body { display: flex; gap: 12px; height: 75vh; }

/* 左侧编辑区 */
.editor-area { flex: 1; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.editor-toolbar { display: flex; align-items: center; gap: 12px; padding: 8px 12px; background: #f5f7fa; border-radius: 4px; }
.template-editor { flex: 1; display: flex; flex-direction: column; }
.editor-label { font-size: 12px; color: #606266; margin-bottom: 4px; font-weight: 600; }
:deep(.template-editor .el-textarea__inner) { resize: none; }

.editor-preview { height: 200px; display: flex; flex-direction: column; }
:deep(.editor-preview .el-textarea__inner) { height: 100% !important; }

.preview-page {
  flex: 1;
  overflow: auto;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 8px;
  font-family: "SimHei", "Microsoft YaHei";
  font-size: 10px;
  line-height: 1.7;
}
.prev-title { text-align: center; font-size: 13px; font-weight: bold; border-bottom: 1px solid #000; padding-bottom: 3px; margin-bottom: 4px; }
.prev-content div { white-space: pre-wrap; }

/* 右侧字段参考 */
.field-ref {
  width: 240px;
  flex-shrink: 0;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ref-title { padding: 8px 12px; background: #f5f7fa; border-bottom: 1px solid #e4e7ed; font-size: 13px; font-weight: 600; color: #303133; }
.ref-tabs { flex: 1; overflow: hidden; }
:deep(.ref-tabs .el-tabs__content) { overflow-y: auto; max-height: calc(75vh - 180px); padding: 4px 8px; }
.field-list { display: flex; flex-direction: column; gap: 2px; }
.field-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 8px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
  border: 1px solid transparent;
}
.field-item:hover { background: #ecf5ff; border-color: #409eff; }
.field-name { font-family: Consolas, monospace; color: #409eff; font-size: 11px; }
.field-label { color: #909399; font-size: 11px; }
.ref-hint {
  padding: 10px 12px;
  background: #fffbe6;
  border-top: 1px solid #f0e6c8;
  font-size: 11.5px;
  line-height: 1.8;
  color: #606266;
}
</style>
