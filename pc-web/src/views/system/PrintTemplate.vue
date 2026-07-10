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
            <el-option label="采购退货" value="PUR_RETURN" />
            <el-option label="销售退货" value="SAL_RETURN" />
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
                  <el-option label="采购退货" value="PUR_RETURN" />
                  <el-option label="销售退货" value="SAL_RETURN" />
                </el-select>
              </el-form-item>
              <el-form-item label="纸张" style="margin-bottom:0">
                <el-select v-model="cfg.paperSize" style="width:110px">
                  <el-option v-for="p in paperSizes" :key="p.value" :label="p.label" :value="p.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="标题文字" style="margin-bottom:0">
                <el-input v-model="cfg.title" style="width:110px" />
                <el-checkbox v-model="cfg.showTitle" style="margin-left:6px">显示</el-checkbox>
              </el-form-item>
              <el-form-item label="模板模式" style="margin-bottom:0">
                <el-radio-group v-model="cfg.mode" size="small">
                  <el-radio-button value="text">纯文本</el-radio-button>
                  <el-radio-button value="html">HTML</el-radio-button>
                </el-radio-group>
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
            <div class="editor-label">
              <span v-if="cfg.mode === 'html'">📝 模板内容（HTML 模式 - 可直接写 &lt;table&gt;/&lt;div&gt;/&lt;style&gt; 等标签, {{}} 仍可用)</span>
              <span v-else>📝 模板内容（用 {{}} 包裹字段名）</span>
            </div>
            <!-- HTML 模式工具栏 -->
            <div v-if="cfg.mode === 'html'" class="html-snippets">
              <el-button size="small" @click="insertSnippet('table')">插入表格</el-button>
              <el-button size="small" @click="insertSnippet('info')">信息行</el-button>
              <el-button size="small" @click="insertSnippet('details')">明细表</el-button>
              <el-button size="small" @click="insertSnippet('style')">样式</el-button>
              <el-button size="small" @click="insertSnippet('header')">表头模板</el-button>
            </div>
            <!-- HTML 模式: 使用 CodeMirror 代码编辑器 -->
            <CodeEditor
              v-if="cfg.mode === 'html'"
              v-model="cfg.template"
              :placeholder="htmlPlaceholder"
              height="100%"
              @change="updatePreview"
            />
            <!-- 纯文本模式: 使用普通文本框 -->
            <el-input
              v-else
              v-model="cfg.template"
              type="textarea"
              :rows="14"
              :placeholder="textPlaceholder"
              style="font-family:Consolas,monospace;font-size:13px"
              @input="updatePreview"
            />
          </div>

          <!-- 实时预览 -->
          <div class="editor-preview">
            <div class="editor-label">👁 打印预览</div>
            <div class="preview-page" :style="previewStyle">
              <div class="prev-title" v-if="cfg.showTitle">{{ cfg.title || '单据' }}</div>
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
            <div style="margin-top:8px;font-weight:600;color:#67c23a">商品字段 (插入到明细循环中)</div>
            <div><b>{{productName}}</b> 商品名 · <b>{{productCode}}</b> 编码 · <b>{{spec}}</b> 规格 · <b>{{model}}</b> 型号</div>
            <div><b>{{unitName}}</b> 单位 · <b>{{qty}}</b> 数量 · <b>{{price}}</b> 单价</div>
            <div><b>{{amount}}</b> 金额 · <b>{{batchNo}}</b> 批次 · <b>{{remark}}</b> 备注</div>
            <div style="margin-top:8px;font-weight:600;color:#67c23a">商品规格属性</div>
            <div><b>{{thickness}}</b> 长度 · <b>{{width}}</b> 宽度 · <b>{{density}}</b> 厚度</div>
            <div><b>{{gramWeight}}</b> 克重 · <b>{{material}}</b> 材质</div>
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
import { purReceiptApi, purReturnApi } from '@/api/purchase'
import { salDeliveryApi, salReturnApi } from '@/api/sales'
import { prdOrderApi } from '@/api/production'
import { ElMessage, ElMessageBox } from 'element-plus'
import CodeEditor from '@/components/CodeEditor.vue'
import { getPrintUrl } from '@/composables/usePrintUrl'

const typeMap = { SAL_DELIVERY: '销售出库', PUR_RECEIPT: '采购入库', PRD_ORDER: '生产单', PUR_RETURN: '采购退货', SAL_RETURN: '销售退货' }
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
    { value: 'sourceDeliveryId', label: '原出库单号' },
  ],
  // 采购入库
  pur: [
    { value: 'supplierName', label: '供应商名称' }, { value: 'supplierCode', label: '供应商编码' },
    { value: 'warehouseName', label: '仓库' }, { value: 'buyerName', label: '采购员' },
    { value: 'orderNo', label: '采购订单号' }, { value: 'payType', label: '付款方式' },
    { value: 'sourceReceiptId', label: '原入库单号' },
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
    // 商品规格属性
    { value: 'thickness', label: '长度' }, { value: 'width', label: '宽度' }, { value: 'density', label: '厚度' },
    { value: 'gramWeight', label: '克重' }, { value: 'material', label: '材质' },
    // 备注
    { value: 'remark', label: '生产单备注' }, { value: 'bomRemark', label: 'BOM备注' },
  ],
  // 明细(通用)
  detail: [
    { value: 'lineNo', label: '行号' }, { value: 'productName', label: '商品名称' },
    { value: 'productCode', label: '商品编码' }, { value: 'spec', label: '规格' },
    { value: 'model', label: '型号' }, { value: 'unitName', label: '单位' },
    { value: 'qty', label: '数量' },
    { value: 'price', label: '单价(含税)' }, { value: 'priceEx', label: '不含税单价' },
    { value: 'amount', label: '金额' }, { value: 'amountTax', label: '含税金额' },
    { value: 'taxRate', label: '税率%' }, { value: 'taxAmount', label: '税额' },
    { value: 'batchNo', label: '批次' }, { value: 'locationName', label: '库位' },
    { value: 'snNo', label: '序列号' }, { value: 'remark', label: '备注' },
    // 商品规格属性
    { value: 'thickness', label: '长度' }, { value: 'width', label: '宽度' }, { value: 'density', label: '厚度' },
    { value: 'gramWeight', label: '克重' }, { value: 'material', label: '材质' },
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
  const g = (fields) => ({ name: fields, label: fields === 'common' ? '通用' : fields === 'detail' ? '明细' : fields === 'footer' ? '表尾' : (type === 'SAL_DELIVERY' || type === 'SAL_RETURN') ? '销售' : (type === 'PUR_RECEIPT' || type === 'PUR_RETURN') ? '采购' : '生产', fields: allFields[fields] })
  if (type === 'SAL_DELIVERY') return [g('common'), g('sal'), g('detail'), g('footer')]
  if (type === 'PUR_RECEIPT' || type === 'PUR_RETURN') return [g('common'), g('pur'), g('detail'), g('footer')]
  if (type === 'SAL_RETURN') return [g('common'), g('sal'), g('detail'), g('footer')]
  if (type === 'PRD_ORDER') return [g('common'), g('prd'), g('detail'), g('footer')]
  return [g('common'), g('prd')]
}

const fieldGroups = computed(() => getFieldGroups(form.value.templateType))

// ========== 模板配置 ==========
const cfg = ref({
  paperSize: 'P76',
  title: '单据',
  showTitle: true,
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
  const isHtml = cfg.value.mode === 'html'
  // 演示数据 (用于字段替换预览)
  const demoDetail = [
    { lineNo: 1, productName: '商品A', spec: '规格A', qty: '10.0000', price: '128.0000', amount: '1280.0000' },
    { lineNo: 2, productName: '商品B', spec: '规格B', qty: '5.0000', price: '88.0000', amount: '440.0000' },
  ]
  // 替换 {{#details}} 块为所有数据行 (支持 {{#details}} 在 <tbody> 内或独立使用)
  let content = tpl.replace(/\{\{#details\}\}([\s\S]*?)\{\{\/details\}\}/, (_, block) => {
    return demoDetail.map(d => {
      let row = block
      Object.keys(d).forEach(k => { row = row.replace(new RegExp(`\\{\\{${k}\\}\\}`, 'g'), d[k]) })
      return row
    }).join('\n')
  })
  // 替换 {{field}}
  content = content.replace(/\{\{([^}]+)\}\}/g, (_, k) => demoVal(k.trim()))
  // 移除 === 注释行
  content = content.replace(/^===.*?===\s*$/gm, '')

  if (isHtml) {
    // HTML 模式: 如果有 <th> 但没有数据行, 自动填充演示数据
    content = autoFillHtmlTables(content, demoDetail)
    previewHtml.value = content
  } else {
    // 文本模式: 按行转义为 div, 安全预览
    previewHtml.value = content.split('\n').map(l => `<div>${escHtml(l) || '&nbsp;'}</div>`).join('')
  }
}

/** 检测 HTML 中有 <th> 表头但无 <td> 数据行的表格, 自动填充演示数据 */
function autoFillHtmlTables(html, demoData) {
  return html.replace(/<table[^>]*>([\s\S]*?)<\/table>/gi, (tableFull, inner) => {
    // 已有 <td> 数据行, 不处理
    if (/<td[\s>]/i.test(inner)) return tableFull
    // 提取 <th> 列名
    const ths = [...inner.matchAll(/<th[^>]*>([\s\S]*?)<\/th>/gi)]
    if (!ths.length) return tableFull
    const colNames = ths.map(m => m[1].replace(/<[^>]+>/g, '').trim())
    // 根据列名猜测字段
    const fieldMap = { '商品': 'productName', '商品名': 'productName', '商品编码': 'productCode', '编码': 'productCode',
      '规格': 'spec', '单位': 'unitName', '数量': 'qty', '单价': 'price', '单价(含税)': 'price',
      '金额': 'amount', '含税金额': 'amountTax', '不含税单价': 'priceEx', '税率': 'taxRate',
      '税额': 'taxAmount', '备注': 'remark', '批次': 'batchNo', '库位': 'locationName', '行号': 'lineNo',
      '序号': 'lineNo', '商品名称': 'productName' }
    const fields = colNames.map(n => fieldMap[n] || null)
    // 生成 <tbody> 数据行
    const rows = demoData.map(d => {
      const tds = fields.map((f, i) => {
        const val = f ? (d[f] || '—') : '—'
        const isNum = f && /^(qty|price|amount|priceEx|amountTax|taxRate|taxAmount|lineNo)$/.test(f)
        const align = isNum ? 'text-align:right;' : ''
        return `<td style="border:1px solid #333;padding:4px;${align}">${val}</td>`
      }).join('')
      return `<tr>${tds}</tr>`
    }).join('')
    // 插入到 </thead> 之后, 或 </tr>(最后一个表头行) 之后
    const headEnd = inner.search(/<\/thead>/i)
    if (headEnd >= 0) {
      const pos = headEnd + inner.slice(headEnd).indexOf('>') + 1
      return tableFull.replace(inner, inner.slice(0, pos) + rows + inner.slice(pos))
    }
    // 没有 <thead>, 在最后一个 </tr> 后插入
    const lastTr = inner.lastIndexOf('</tr>')
    if (lastTr >= 0) {
      const pos = lastTr + 5
      return tableFull.replace(inner, inner.slice(0, pos) + rows + inner.slice(pos))
    }
    return tableFull
  })
}

// HTML 模式占位符示例
const htmlPlaceholder = `<style>
  .my-table { border-collapse: collapse; width: 100%; }
  .my-table th { background: #333; color: #fff; padding: 6px; }
  .my-table td { border: 1px solid #ccc; padding: 4px; }
</style>

<h2 style="text-align:center;color:#1e6091">销售出库单</h2>

<table class="my-table">
  <tr><th>单号</th><td>{{billNo}}</td><th>日期</th><td>{{billDate}}</td></tr>
  <tr><th>客户</th><td>{{customerName}}</td><th>仓库</th><td>{{warehouseName}}</td></tr>
</table>

<h3 style="margin-top:10px">商品明细</h3>
<table class="my-table">
  <thead><tr><th>商品</th><th>规格</th><th>数量</th><th>单价</th><th>金额</th></tr></thead>
  <tbody>
  {{#details}}
  <tr><td>{{productName}}</td><td>{{spec}}</td><td style="text-align:right">{{qty}}</td><td style="text-align:right">{{price}}</td><td style="text-align:right">{{amount}}</td></tr>
  {{/details}}
  </tbody>
</table>

<div style="text-align:right;font-size:14px;font-weight:bold;margin-top:10px">
  价税合计: ¥{{totalAmountTax}}
</div>`

const textPlaceholder = `=== 表头 ===
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
合计: {{totalAmount}}`

function demoVal(k) {
  const m = {
    billNo: 'CG-20240618-001', billDate: '2024-06-18', supplierName: '示例供应商',
    customerName: '示例客户', warehouseName: '中心仓库', address: '北京市朝阳区',
    phone: '13800138000', remark: '备注', salesmanName: '张三', buyerName: '李四',
    deliveryDate: '2024-06-25', payType: '月结', discountAmount: '0.00', tailAmount: '0.00',
    orderNo: 'CG-20240601-001', sourceReceiptId: 'RK-20240610-001', sourceDeliveryId: 'CK-20240610-001',
    bomNo: 'BOM-001', productName: '产品A',
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
  // HTML 模式: 追加到模板末尾 (CodeEditor 不支持光标定位 API)
  if (cfg.value.mode === 'html') {
    cfg.value.template += '{{' + fieldName + '}}'
    updatePreview()
    return
  }
  // 纯文本模式: 插入到光标位置
  const ta = document.querySelector('.template-editor .el-textarea__inner')
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const before = cfg.value.template.slice(0, start)
  const after = cfg.value.template.slice(end)
  cfg.value.template = before + '{{' + fieldName + '}}' + after
  updatePreview()
}

// ========== HTML 代码片段插入 ==========
const snippets = {
  table: `
<table style="width:100%;border-collapse:collapse;">
  <tr><th style="border:1px solid #333;padding:4px;background:#f0f0f0;">标题</th><td style="border:1px solid #333;padding:4px;">{{billNo}}</td></tr>
  <tr><th style="border:1px solid #333;padding:4px;background:#f0f0f0;">日期</th><td style="border:1px solid #333;padding:4px;">{{billDate}}</td></tr>
</table>`,
  info: `<div style="display:flex;justify-content:space-between;padding:2px 0;">
  <span>单号: {{billNo}}</span>
  <span>日期: {{billDate}}</span>
</div>`,
  details: `
<table style="width:100%;border-collapse:collapse;">
  <thead>
    <tr>
      <th style="border:1px solid #333;padding:4px;background:#f0f0f0;">商品</th>
      <th style="border:1px solid #333;padding:4px;background:#f0f0f0;">规格</th>
      <th style="border:1px solid #333;padding:4px;background:#f0f0f0;">数量</th>
      <th style="border:1px solid #333;padding:4px;background:#f0f0f0;">单价</th>
      <th style="border:1px solid #333;padding:4px;background:#f0f0f0;">金额</th>
    </tr>
  </thead>
  <tbody>
    {{#details}}
    <tr>
      <td style="border:1px solid #333;padding:4px;">{{productName}}</td>
      <td style="border:1px solid #333;padding:4px;">{{spec}}</td>
      <td style="border:1px solid #333;padding:4px;text-align:right;">{{qty}}</td>
      <td style="border:1px solid #333;padding:4px;text-align:right;">{{price}}</td>
      <td style="border:1px solid #333;padding:4px;text-align:right;">{{amount}}</td>
    </tr>
    {{/details}}
  </tbody>
</table>`,
  style: `<style>
  .print-page { font-family: SimHei, Microsoft YaHei; font-size: 11px; }
  .print-table { width: 100%; border-collapse: collapse; }
  .print-table th { background: #333; color: #fff; padding: 6px; }
  .print-table td { border: 1px solid #ccc; padding: 4px; }
  .print-title { text-align: center; font-size: 14px; margin: 4px 0; }
  .print-total { text-align: right; font-weight: bold; margin-top: 8px; }
</style>`,
  header: `<div style="text-align:center;margin-bottom:10px;">
  <h2 style="margin:0;font-size:16px;">销售出库单</h2>
  <div style="display:flex;justify-content:space-between;font-size:11px;margin-top:4px;">
    <span>单号: {{billNo}}</span>
    <span>日期: {{billDate}}</span>
  </div>
</div>`,
}

function insertSnippet(type) {
  const snippet = snippets[type]
  if (snippet) {
    cfg.value.template += snippet
    updatePreview()
  }
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
      paperSize: 'P76', title: '单据', showTitle: true, showSignature: true, mode: 'text',
      template: getDefaultTemplate(form.value.templateType),
    }
    return
  }
  try {
    const parsed = JSON.parse(content)
    if (!parsed.mode) parsed.mode = 'text'
    if (parsed.showTitle === undefined) parsed.showTitle = true
    cfg.value = parsed
  } catch {
    cfg.value = { paperSize: 'P76', title: '单据', showTitle: true, showSignature: true, mode: 'text', template: getDefaultTemplate(form.value.templateType) }
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
  if (type === 'PUR_RETURN') return `单号: {{billNo}}
日期: {{billDate}}
供应商: {{supplierName}}
仓库: {{warehouseName}}

=== 商品明细 ===
商品名 | 规格 | 数量 | 单价 | 金额
{{#details}}
{{productName}} | {{spec}} | {{qty}} | {{price}} | {{amount}}
{{/details}}

不含税金额: {{totalAmount}}
税额: {{taxAmount}}
价税合计: {{totalAmountTax}}`
  if (type === 'SAL_RETURN') return `单号: {{billNo}}
日期: {{billDate}}
客户: {{customerName}}
仓库: {{warehouseName}}

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
  return JSON.stringify({ paperSize: cfg.value.paperSize, title: cfg.value.title, showTitle: cfg.value.showTitle, showSignature: cfg.value.showSignature, mode: cfg.value.mode, template: cfg.value.template })
}

async function loadData() {
  loading.value = true
  try { data.value = (await printApi.page(query)).data } finally { loading.value = false }
}

function onAdd() {
  form.value = { id: null, templateName: '', templateType: 'SAL_DELIVERY', paperWidth: 76, paperHeight: 120, content: '', isDefault: 0 }
  cfg.value = { paperSize: 'P76', title: '销售出库单', showTitle: true, showSignature: true, template: getDefaultTemplate('SAL_DELIVERY') }
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
    } else if (row.templateType === 'PUR_RETURN') {
      const res = await purReturnApi.page({ pageNum: 1, pageSize: 1 })
      realId = res.data?.records?.[0]?.id
    } else if (row.templateType === 'SAL_RETURN') {
      const res = await salReturnApi.page({ pageNum: 1, pageSize: 1 })
      realId = res.data?.records?.[0]?.id
    }
  } catch (e) { /* ignore */ }

  if (!realId) { ElMessage.warning('暂无单据数据，请先创建后再预览'); return }
  const typeToPath = { SAL_DELIVERY: 'sales-delivery', PUR_RECEIPT: 'purchase-receipt', PRD_ORDER: 'prd-order', PUR_RETURN: 'purchase-return', SAL_RETURN: 'sales-return' }
  const path = typeToPath[row.templateType] || row.templateType.toLowerCase().replaceAll('_', '-')
  previewUrl.value = getPrintUrl(`/api/print/${path}`, realId)
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
.html-snippets { display: flex; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
.html-snippets .el-button { font-size: 12px; }

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
.prev-content table { width: 100%; border-collapse: collapse; margin: 4px 0; }
.prev-content th, .prev-content td { border: 1px solid #333; padding: 3px 6px; font-size: 10px; }
.prev-content th { background: #f0f0f0; text-align: center; font-weight: bold; }
.prev-content p { margin: 4px 0; }

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
