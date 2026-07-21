<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新建模板</el-button>
      <el-select v-model="filterBizType" placeholder="按单据筛选" clearable style="width:160px" @change="loadData">
        <el-option v-for="t in BIZ_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="filterPrinterId" placeholder="按打印机筛选" clearable style="width:160px" @change="loadData">
        <el-option v-for="p in printers" :key="p.id" :label="p.printerName" :value="p.id" />
      </el-select>
    </div>

    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column type="index" width="50" />
      <el-table-column prop="name" label="模板名称" width="160" />
      <el-table-column prop="bizType" label="单据类型" width="140">
        <template #default="{ row }">{{ BIZ_TYPE_MAP[row.bizType] || row.bizType }}</template>
      </el-table-column>
      <el-table-column prop="printerConfigId" label="打印机" width="140">
        <template #default="{ row }">{{ printerName(row.printerConfigId) }}</template>
      </el-table-column>
      <el-table-column prop="paperWidth" label="纸宽(mm)" width="80" />
      <el-table-column prop="status" label="状态" width="70">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="isDefault" label="默认" width="60">
        <template #default="{ row }">
          <el-tag v-if="row.isDefault === 1" type="warning" size="small">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="onPreview(row)">预览</el-button>
          <el-button link type="success" @click="onSetDefault(row)" v-if="row.isDefault !== 1">设为默认</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模板' : '新建模板'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="模板名称" required>
              <el-input v-model="form.name" placeholder="如 58mm车间模板" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="单据类型" required>
              <el-select v-model="form.bizType" placeholder="选择单据" style="width:100%">
                <el-option v-for="t in BIZ_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="打印机" required>
              <el-select v-model="form.printerConfigId" placeholder="选择打印机" style="width:100%">
                <el-option v-for="p in printers" :key="p.id" :label="p.printerName" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="纸宽(mm)">
              <el-input-number v-model="form.paperWidth" :min="58" :max="110" :step="1" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="默认模板">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" active-text="是" inactive-text="否" />
        </el-form-item>
        <el-form-item label="内容" required>
          <div style="display:flex;gap:8px;">
            <div style="flex:1;position:relative;">
              <el-button size="small" type="primary" @click="insertTag('<CB>标题</CB>')" style="position:absolute;z-index:1;top:4px;right:4px;">插入标签</el-button>
              <el-input v-model="form.content" type="textarea" :rows="18" placeholder="<CB>标题</CB><BR>单号: ${bill.billNo!''}<BR><BR>商品明细:<BR><#list bill.details as d>${d.productName!''}  数量:${d.qty!0}<BR></#list><CUT>" />
            </div>
            <div style="width:280px;border:1px solid #e4e7ed;border-radius:4px;background:#fafbfc;overflow:auto;max-height:380px;">
              <div style="padding:8px 10px;background:#f0f2f5;font-weight:600;font-size:12px;border-bottom:1px solid #e4e7ed;">
                📋 字段说明 ({{ BIZ_TYPE_MAP[form.bizType] || '请先选单据' }})
              </div>
              <div v-if="!form.bizType" style="padding:10px;color:#999;font-size:12px;">选择单据后显示字段</div>
              <div v-else>
                <div style="padding:6px 10px;background:#f0f9eb;color:#67c23a;font-size:11px;font-weight:600;">📌 主表字段 (${'${bill.xxx}'})</div>
                <div v-for="f in (FIELD_DOC[form.bizType]?.main || [])" :key="'m-'+f.name" class="field-row" @click="insertField(bizPrefix + f.name)">
                  <span class="field-name">{{ bizPrefix }}{{ f.name }}<span v-if="!form.bizType || form.bizType !== 'PRD_ORDER'">!''</span></span>
                  <span class="field-desc">{{ f.desc }}</span>
                </div>
                <div v-if="form.bizType !== 'INV_CHECK'" style="padding:6px 10px;background:#fdf6ec;color:#e6a23c;font-size:11px;font-weight:600;">🔁 明细循环 (&lt;#list bill.details as d&gt;...)</div>
                <div v-for="f in (FIELD_DOC[form.bizType]?.detail || [])" :key="'d-'+f.name" class="field-row" @click="insertField('d.' + f.name)">
                  <span class="field-name">d.{{ f.name }}<span v-if="f.name === 'qty'">!0</span></span>
                  <span class="field-desc">{{ f.desc }}</span>
                </div>
                <div style="padding:6px 10px;background:#f4f4f5;color:#909399;font-size:11px;">⏰ 系统字段</div>
                <div class="field-row" @click="insertField(nowFieldExpr)">
                  <span class="field-name">.now?string(...)</span>
                  <span class="field-desc">当前时间</span>
                </div>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="标签速查">
          <div style="font-size:12px;color:#999;line-height:1.8;">
            <span v-for="tag in TAG_EXAMPLES" :key="tag.label" style="margin-right:12px;">
              <el-tag size="small" @click="insertTag(tag.code)" style="cursor:pointer;">{{ tag.label }}</el-tag>
            </span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预览弹窗 -->
    <el-dialog v-model="previewVisible" :title="'预览: ' + (previewForm.name || '')" width="420px" destroy-on-close>
      <pre style="white-space:pre-wrap;font-family:SimSun,monospace;font-size:12px;background:#fafafa;padding:12px;border-radius:4px;max-height:500px;overflow:auto;">{{ previewContent }}</pre>
      <template #footer>
        <el-button @click="previewVisible=false">关闭</el-button>
        <el-button type="primary" :loading="previewLoading" @click="doPreviewRender" :disabled="!previewForm.content">渲染预览</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { feiePrintApi } from '@/api/feie'

// 单据类型选项
const BIZ_TYPE_OPTIONS = [
  { label: '生产加工单', value: 'PRD_ORDER' },
  { label: '销售出库单', value: 'SAL_DELIVERY' },
  { label: '销售退货单', value: 'SAL_RETURN' },
  { label: '采购入库单', value: 'PUR_RECEIPT' },
  { label: '采购退货单', value: 'PUR_RETURN' },
  { label: '库存盘点单', value: 'INV_CHECK' }
]
const BIZ_TYPE_MAP = {
  PRD_ORDER: '生产加工单', SAL_DELIVERY: '销售出库单', SAL_RETURN: '销售退货单',
  PUR_RECEIPT: '采购入库单', PUR_RETURN: '采购退货单', INV_CHECK: '库存盘点单'
}

// 字段说明: 按单据类型分组, 用于编辑器右侧字段树
// 字段引用规则: 主表 ${bill.fieldName}, 明细 <#list bill.details as d>${d.fieldName}</#list>
// PRD_ORDER 主表用 ${order.xxx}, INV_CHECK 主表用 ${bill.xxx} 也有
const FIELD_DOC = {
  PRD_ORDER: {
    main: [
      { name: 'billNo', desc: '生产单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'productName', desc: '成品名称' },
      { name: 'productCode', desc: '成品编码' },
      { name: 'spec', desc: '规格' },
      { name: 'model', desc: '型号' },
      { name: 'colorNo', desc: '色号' },
      { name: 'thickness', desc: '长度' },
      { name: 'width', desc: '宽度' },
      { name: 'density', desc: '厚度' },
      { name: 'gramWeight', desc: '克重' },
      { name: 'material', desc: '材质' },
      { name: 'unitName', desc: '单位' },
      { name: 'planQty', desc: '计划数量' },
      { name: 'goodQty', desc: '良品数' },
      { name: 'lossQty', desc: '损耗数' },
      { name: 'lossRate', desc: '损耗率(%)' },
      { name: 'workshop', desc: '车间' },
      { name: 'leader', desc: '负责人' },
      { name: 'startDate', desc: '开工日期' },
      { name: 'endDate', desc: '完工日期' },
      { name: 'bomName', desc: 'BOM名称' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '物料名称' },
      { name: 'productCode', desc: '物料编码' },
      { name: 'qty', desc: '领料数量' },
      { name: 'unitName', desc: '单位' },
      { name: 'lineNo', desc: '行号' },
      { name: 'batchNo', desc: '批次' }
    ]
  },
  SAL_DELIVERY: {
    main: [
      { name: 'billNo', desc: '出库单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'customerName', desc: '客户名称' },
      { name: 'customerPhone', desc: '客户电话' },
      { name: 'warehouseName', desc: '仓库' },
      { name: 'salesUser', desc: '业务员' },
      { name: 'totalQty', desc: '总数量' },
      { name: 'totalAmount', desc: '总金额' },
      { name: 'totalAmountTax', desc: '含税总额' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '商品名称' },
      { name: 'productCode', desc: '商品编码' },
      { name: 'spec', desc: '规格' },
      { name: 'colorNo', desc: '色号' },
      { name: 'unitName', desc: '单位' },
      { name: 'qty', desc: '数量' },
      { name: 'price', desc: '单价' },
      { name: 'amount', desc: '金额' },
      { name: 'batchNo', desc: '批次' },
      { name: 'locationName', desc: '货位' }
    ]
  },
  SAL_RETURN: {
    main: [
      { name: 'billNo', desc: '退货单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'customerName', desc: '客户名称' },
      { name: 'warehouseName', desc: '仓库' },
      { name: 'totalQty', desc: '总数量' },
      { name: 'totalAmount', desc: '总金额' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '商品名称' },
      { name: 'productCode', desc: '商品编码' },
      { name: 'qty', desc: '退货数量' },
      { name: 'price', desc: '单价' },
      { name: 'amount', desc: '金额' },
      { name: 'batchNo', desc: '批次' }
    ]
  },
  PUR_RECEIPT: {
    main: [
      { name: 'billNo', desc: '入库单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'supplierName', desc: '供应商' },
      { name: 'supplierPhone', desc: '供应商电话' },
      { name: 'warehouseName', desc: '仓库' },
      { name: 'purchaseUser', desc: '采购员' },
      { name: 'totalQty', desc: '总数量' },
      { name: 'totalAmount', desc: '总金额' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '商品名称' },
      { name: 'productCode', desc: '商品编码' },
      { name: 'spec', desc: '规格' },
      { name: 'colorNo', desc: '色号' },
      { name: 'unitName', desc: '单位' },
      { name: 'qty', desc: '入库数量' },
      { name: 'price', desc: '单价' },
      { name: 'amount', desc: '金额' },
      { name: 'batchNo', desc: '批次' },
      { name: 'productionDate', desc: '生产日期' },
      { name: 'expireDate', desc: '过期日期' }
    ]
  },
  PUR_RETURN: {
    main: [
      { name: 'billNo', desc: '退货单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'supplierName', desc: '供应商' },
      { name: 'warehouseName', desc: '仓库' },
      { name: 'totalQty', desc: '总数量' },
      { name: 'totalAmount', desc: '总金额' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '商品名称' },
      { name: 'productCode', desc: '商品编码' },
      { name: 'qty', desc: '退货数量' },
      { name: 'price', desc: '单价' },
      { name: 'amount', desc: '金额' },
      { name: 'batchNo', desc: '批次' }
    ]
  },
  INV_CHECK: {
    main: [
      { name: 'billNo', desc: '盘点单号' },
      { name: 'billDate', desc: '单据日期' },
      { name: 'warehouseName', desc: '仓库' },
      { name: 'checkType', desc: '盘点类型' },
      { name: 'totalDiffAmount', desc: '差异金额合计' },
      { name: 'remark', desc: '备注' }
    ],
    detail: [
      { name: 'productName', desc: '商品名称' },
      { name: 'productCode', desc: '商品编码' },
      { name: 'bookQty', desc: '账面数量' },
      { name: 'actualQty', desc: '实盘数量' },
      { name: 'diffQty', desc: '差异数量' },
      { name: 'diffAmount', desc: '差异金额' }
    ]
  }
}

// 标签速查
const TAG_EXAMPLES = [
  { label: '居中放大', code: '<CB>内容</CB>' },
  { label: '居中', code: '<C>内容</C>' },
  { label: '放大', code: '<B>内容</B>' },
  { label: '加粗', code: '<BOLD>内容</BOLD>' },
  { label: '换行', code: '<BR>' },
  { label: '右对齐', code: '<RIGHT>内容</RIGHT>' },
  { label: '二维码', code: '<QR>${bill.billNo!\'\'}</QR>' },
  { label: '切纸', code: '<CUT>' },
  { label: 'LOGO', code: '<LOGO>' },
  { label: '变量', code: '${bill.billNo!\'\'}' },
  { label: '循环', code: '<#list bill.details as d>${d.productName!\'\'}<BR></#list>' }
]

const list = ref([])
const loading = ref(false)
const printers = ref([])
const dialogVisible = ref(false)
const submitting = ref(false)
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewContent = ref('')
const previewForm = ref({})
const filterBizType = ref('')
const filterPrinterId = ref(null)

// 预定义 .now 时间戳表达式 (避免在模板属性里写引号转义)
const nowFieldExpr = '${.now?string("yyyy-MM-dd HH:mm:ss")}'

// PRD_ORDER 主表变量是 order.xxx, 其余是 bill.xxx; 用于字段插入
const bizPrefix = computed(() => {
  return form.bizType === 'PRD_ORDER' ? 'order.' : 'bill.'
})

const form = reactive({
  id: null, name: '', bizType: '', printerConfigId: null,
  content: '', paperWidth: 58, status: 1, isDefault: 0, remark: ''
})

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: 1, pageSize: 9999 }
    if (filterBizType.value) params.bizType = filterBizType.value
    if (filterPrinterId.value) params.printerConfigId = filterPrinterId.value
    const r = await feiePrintApi.templatePage(params)
    list.value = r.data?.records || []
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

async function loadPrinters() {
  try {
    printers.value = (await feiePrintApi.listPrinters()).data || []
  } catch (e) { printers.value = [] }
}

function printerName(id) {
  const p = printers.value.find(x => x.id === id)
  return p ? p.printerName : '-'
}

function onAdd() {
  Object.assign(form, { id: null, name: '', bizType: '', printerConfigId: null, content: '', paperWidth: 58, status: 1, isDefault: 0, remark: '' })
  dialogVisible.value = true
}

function onEdit(row) {
  Object.assign(form, { ...row })
  dialogVisible.value = true
}

async function onSubmit() {
  if (!form.name || !form.bizType || !form.printerConfigId || !form.content) {
    ElMessage.warning('请填写必填项')
    return
  }
  submitting.value = true
  try {
    // el-switch 返回 boolean, 后端 isDefault 期望 Integer (0/1)
    const payload = { ...form, isDefault: form.isDefault ? 1 : 0, status: form.status ?? 1 }
    if (form.id) {
      await feiePrintApi.updateTemplate(form.id, payload)
      ElMessage.success('更新成功')
    } else {
      await feiePrintApi.addTemplate(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除模板 "${row.name}"?`, '删除确认', { type: 'warning' })
  await feiePrintApi.deleteTemplate(row.id)
  ElMessage.success('删除成功')
  loadData()
}

async function onSetDefault(row) {
  await feiePrintApi.updateTemplate(row.id, { ...row, isDefault: 1 })
  ElMessage.success('已设为默认')
  loadData()
}

function onPreview(row) {
  previewForm.value = { ...row }
  previewContent.value = row.content
  previewVisible.value = true
}

async function doPreviewRender() {
  previewLoading.value = true
  try {
    const r = await feiePrintApi.previewTemplate(previewForm.value.id)
    previewContent.value = r.data || '(无预览数据)'
  } catch (e) {
    previewContent.value = '渲染失败: ' + (e.message || '未知错误')
  } finally {
    previewLoading.value = false
  }
}

function insertTag(tag) {
  const textarea = document.querySelector('textarea[placeholder*="<CB>"]')
  if (textarea) {
    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    const val = textarea.value
    textarea.value = val.slice(0, start) + tag + val.slice(end)
    textarea.selectionStart = textarea.selectionEnd = start + tag.length
    textarea.focus()
  }
}

// 把字段插入到光标位置 (字段名带 . 后缀)
function insertField(name) {
  // 已是完整 Freemarker 表达式 (含 ${}) 直接插入
  const text = name.startsWith('${') ? name : '${' + name + "!''}"
  insertTag(text)
}

onMounted(() => { loadData(); loadPrinters() })
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.pager { margin-top: 12px; text-align: right; }
.field-row {
  padding: 6px 10px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.field-row:hover { background: #ecf5ff; }
.field-name { color: #409eff; font-family: monospace; }
.field-desc { color: #999; }
</style>