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
          <el-switch v-model="form.isDefault" active-text="是" inactive-text="否" />
        </el-form-item>
        <el-form-item label="内容" required>
          <div style="position:relative;">
            <el-button size="small" type="primary" @click="insertTag('<CB>标题</CB>')" style="position:absolute;z-index:1;top:4px;right:4px;">插入标签</el-button>
            <el-input v-model="form.content" type="textarea" :rows="18" placeholder="<CB>标题</CB><BR>单号: ${bill.billNo!''}<BR><BR>商品明细:<BR><#list bill.details as d>${d.productName!''}  数量:${d.qty!0}<BR></#list><CUT>" />
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
import { ref, reactive, onMounted } from 'vue'
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
    if (form.id) {
      await feiePrintApi.updateTemplate(form.id, form)
      ElMessage.success('更新成功')
    } else {
      await feiePrintApi.addTemplate(form)
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
  row.isDefault = 1
  await feiePrintApi.updateTemplate(row.id, row)
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

onMounted(() => { loadData(); loadPrinters() })
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.pager { margin-top: 12px; text-align: right; }
</style>