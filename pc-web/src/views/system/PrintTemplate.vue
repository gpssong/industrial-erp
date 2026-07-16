<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="模板名称"><el-input v-model="query.name" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="业务类型">
          <el-select v-model="query.bizType" clearable style="width:160px">
            <el-option v-for="o in BIZ_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable style="width:120px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增模板</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="name" label="模板名称" min-width="160" />
        <el-table-column label="业务类型" width="140">
          <template #default="{ row }">
            {{ BIZ_TYPE_LABEL[row.bizType] || row.bizType }}
          </template>
        </el-table-column>
        <el-table-column label="纸张" width="120">
          <template #default="{ row }">
            {{ row.paperWidth }} × {{ row.paperHeight }} {{ row.pageUnit }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="warning" effect="plain">默认</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onDesign(row)">设计</el-button>
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 元数据编辑弹窗 (不含设计器, 设计器是独立路由) -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模板' : '新增模板'" width="640px" destroy-on-close>
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item label="模板名称" prop="name">
          <el-input v-model="form.name" placeholder="如: 销售出库单-A4标准" />
        </el-form-item>
        <el-form-item label="业务类型" prop="bizType">
          <el-select v-model="form.bizType" style="width:100%" :disabled="!!form.id">
            <el-option v-for="o in BIZ_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="纸张宽度">
              <el-input-number v-model="form.paperWidth" :min="10" :step="1" controls-position="right" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="纸张高度">
              <el-input-number v-model="form.paperHeight" :min="10" :step="1" controls-position="right" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="单位">
          <el-select v-model="form.pageUnit" style="width:160px">
            <el-option label="mm" value="mm" />
            <el-option label="cm" value="cm" />
            <el-option label="in" value="in" />
            <el-option label="px" value="px" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefaultFlag" :active-value="1" :inactive-value="0" />
          <span class="muted" style="margin-left:8px;font-size:12px">同业务类型启用唯一默认模板</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-alert v-if="!form.id" type="info" :closable="false" show-icon>
          保存后跳转到设计器, 可拖拽布局并预览效果
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">保存并{{ form.id ? '返回' : '去设计' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { printTemplateApi } from '@/api/system'
import { clearTemplateCache, BIZ_TYPE_LABEL } from '@/composables/usePrint'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const BIZ_OPTIONS = [
  { value: 'SAL_DELIVERY', label: BIZ_TYPE_LABEL.SAL_DELIVERY },
  { value: 'PUR_RECEIPT',  label: BIZ_TYPE_LABEL.PUR_RECEIPT },
  { value: 'PUR_RETURN',   label: BIZ_TYPE_LABEL.PUR_RETURN },
  { value: 'SAL_RETURN',   label: BIZ_TYPE_LABEL.SAL_RETURN },
  { value: 'PRD_ORDER',    label: BIZ_TYPE_LABEL.PRD_ORDER }
]

const query = reactive({ pageNum: 1, pageSize: 20, name: '', bizType: '', status: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const rules = {
  name: [{ required: true, message: '请填写模板名称', trigger: 'blur' }],
  bizType: [{ required: true, message: '请选择业务类型', trigger: 'change' }]
}
const form = ref({
  id: null, name: '', bizType: 'SAL_DELIVERY',
  paperWidth: 210, paperHeight: 297, pageUnit: 'mm',
  status: 1, isDefaultFlag: 0, remark: '',
  content: ''
})

/**
 * 根据当前表单的 name/bizType/paperWidth/paperHeight/pageUnit 构造一份
 * 完整 panel JSON 字符串 (含 name 字段, 这是 DesignPanel 工具栏"保存"按钮
 * 解除 disabled 的关键)
 */
function buildContent(name, bizType, w, h, unit) {
  return JSON.stringify({
    name: name || '',
    bizType: bizType || 'SAL_DELIVERY',
    pageUnit: unit || 'mm',
    width: Number(w) || 210,
    height: Number(h) || 297,
    elementList: []
  })
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.name) delete params.name
    if (!params.bizType) delete params.bizType
    if (params.status === '' || params.status == null) delete params.status
    data.value = (await printTemplateApi.page(params)).data || { records: [], total: 0 }
  } finally { loading.value = false }
}
function onReset() {
  query.name = ''; query.bizType = ''; query.status = ''
  query.pageNum = 1
  loadData()
}

function onAdd() {
  form.value = {
    id: null, name: '', bizType: 'SAL_DELIVERY',
    paperWidth: 210, paperHeight: 297, pageUnit: 'mm',
    status: 1, isDefaultFlag: 0, remark: '',
    content: ''
  }
  dialogVisible.value = true
}
function onEdit(row) {
  form.value = {
    id: row.id,
    name: row.name,
    bizType: row.bizType,
    paperWidth: Number(row.paperWidth) || 210,
    paperHeight: Number(row.paperHeight) || 297,
    pageUnit: row.pageUnit || 'mm',
    status: row.status ?? 1,
    isDefaultFlag: row.isDefault ?? 0,
    remark: row.remark || '',
    content: row.content || ''
  }
  dialogVisible.value = true
}

async function onSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    // 关键: 把表单的 name/bizType 注入到 content JSON, 设计器才能识别
    const content = buildContent(
      form.value.name, form.value.bizType,
      form.value.paperWidth, form.value.paperHeight, form.value.pageUnit
    )
    const payload = {
      id: form.value.id || undefined,
      name: form.value.name,
      bizType: form.value.bizType,
      paperWidth: form.value.paperWidth,
      paperHeight: form.value.paperHeight,
      pageUnit: form.value.pageUnit,
      status: form.value.status,
      isDefault: form.value.isDefaultFlag,
      remark: form.value.remark,
      content
    }
    let newId
    if (form.value.id) {
      await printTemplateApi.update(payload)
      ElMessage.success('保存成功')
      dialogVisible.value = false
      clearTemplateCache(payload.bizType)
      loadData()
    } else {
      const r = await printTemplateApi.add(payload)
      newId = r && r.data ? r.data : null
      ElMessage.success('已创建, 正在打开设计器')
      dialogVisible.value = false
      clearTemplateCache(payload.bizType)
      if (newId) router.push(`/system/print-template/designer/${newId}`)
      else loadData()
    }
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

function onDesign(row) {
  // 通过 router state 传递完整模板数据, 设计器无需再次请求, 避免 elementList 加载时序问题
  const stateData = {
    id: row.id,
    name: row.name,
    bizType: row.bizType,
    paperWidth: Number(row.paperWidth) || 210,
    paperHeight: Number(row.paperHeight) || 297,
    pageUnit: row.pageUnit || 'mm',
    status: row.status ?? 1,
    isDefault: row.isDefault ?? 0,
    content: row.content || '',
    // 用时间戳让每次进入的 key 都不同, 强制组件 remount
    _t: Date.now()
  }
  router.push({ path: `/system/print-template/designer/${row.id}`, query: { t: stateData._t } })
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除模板 "${row.name}"? 删除后该单据将无法打印`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await printTemplateApi.delete(row.id)
    ElMessage.success('删除成功')
    clearTemplateCache(row.bizType)
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(loadData)
</script>
<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
.muted { color: #c0c4cc; }
</style>