<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="检查地点"><el-input v-model="query.site" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable placeholder="全部" style="width:120px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item label="到期">
          <el-select v-model="query.overdue" clearable placeholder="全部" style="width:120px">
            <el-option label="已到期" :value="true" />
            <el-option label="未到期" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button v-if="userStore.hasPerm('work:safety:add')" @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增检查</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="recordNo" label="记录编号" width="170" />
        <el-table-column prop="checkDate" label="检查日期" width="120" />
        <el-table-column prop="checkType" label="检查类型" width="120">
          <template #default="{ row }">{{ row.checkType || '-' }}</template>
        </el-table-column>
        <el-table-column prop="site" label="检查地点" min-width="140">
          <template #default="{ row }">{{ row.site || '-' }}</template>
        </el-table-column>
        <el-table-column prop="checker" label="检查人" width="110">
          <template #default="{ row }">{{ row.checker || '-' }}</template>
        </el-table-column>
        <el-table-column prop="riskCount" label="隐患数" width="80" align="center">
          <template #default="{ row }"><el-tag v-if="(row.riskCount || 0) > 0" type="danger">{{ row.riskCount }}</el-tag><span v-else>0</span></template>
        </el-table-column>
        <el-table-column prop="nextDueDate" label="下次到期" width="120">
          <template #default="{ row }"><span :class="{ 'overdue': isOverdue(row) }">{{ row.nextDueDate || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:safety:edit')" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:safety:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:safety:check')" link type="success" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus === 'CHECKED' && userStore.hasPerm('work:safety:uncheck')" link type="warning" @click="onUncheck(row)">反审核</el-button>
            <el-button link type="primary" @click="onView(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" :page-sizes="[10, 20, 50, 100]" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑安全检查记录' : '新增安全检查记录'" width="960px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="检查日期"><el-date-picker v-model="form.checkDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="检查类型"><el-input v-model="form.checkType" placeholder="日常/专项/节前" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="检查地点"><el-input v-model="form.site" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="检查人"><el-input v-model="form.checker" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="下次到期"><el-date-picker v-model="form.nextDueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="检查明细">
          <el-button @click="addLine" type="primary" plain size="small">添加检查项</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="检查项" min-width="140"><template #default="{ row }"><el-input v-model="row.checkItem" size="small" /></template></el-table-column>
            <el-table-column label="结果" width="120"><template #default="{ row }"><el-input v-model="row.result" size="small" placeholder="合格/不合格" /></template></el-table-column>
            <el-table-column label="隐患描述" min-width="180"><template #default="{ row }"><el-input v-model="row.riskDesc" size="small" /></template></el-table-column>
            <el-table-column label="隐患照片" width="120"><template #default="{ row }">
              <el-tooltip v-if="row.riskPhoto" :content="row.riskPhoto" placement="top">
                <img :src="fullUrl(row.riskPhoto)" style="width:32px;height:32px;object-fit:cover;border-radius:2px;cursor:pointer" @click="previewImg(row.riskPhoto)" />
              </el-tooltip>
              <el-button v-else link type="primary" size="small" @click="uploadRiskPhoto(row)">上传</el-button>
            </template></el-table-column>
            <el-table-column label="整改责任人" width="120"><template #default="{ row }"><el-input v-model="row.handler" size="small" /></template></el-table-column>
            <el-table-column label="整改期限" width="140"><template #default="{ row }"><el-date-picker v-model="row.fixDeadline" type="date" value-format="YYYY-MM-DD" size="small" style="width:100%" /></template></el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index, 1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="整改后照片">
          <el-button @click="pickAttachmentFile" type="primary" plain size="small">添加照片</el-button>
          <div v-if="form._attachmentList && form._attachmentList.length" class="thumb-list">
            <div v-for="(u, i) in form._attachmentList" :key="i" class="thumb-item">
              <img :src="fullUrl(u)" @click="previewImg(u)" />
              <el-button link type="danger" size="small" @click="form._attachmentList.splice(i, 1)">删</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
        <input ref="riskPhotoInput" type="file" accept="image/*" style="display:none" @change="onRiskPhotoChange" />
        <input ref="attachmentInput" type="file" accept="image/*,.pdf,.doc,.docx" multiple style="display:none" @change="onAttachmentChange" />
        <el-dialog v-model="imgPreviewVisible" width="600px" destroy-on-close>
          <img :src="fullUrl(imgPreviewUrl)" style="width:100%;display:block" />
        </el-dialog>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="!form.id || form.billStatus === 'DRAFT'" type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="viewVisible" title="安全检查记录详情" width="860px" destroy-on-close>
      <el-descriptions :column="2" border v-if="view">
        <el-descriptions-item label="记录编号">{{ view.recordNo }}</el-descriptions-item>
        <el-descriptions-item label="检查日期">{{ view.checkDate }}</el-descriptions-item>
        <el-descriptions-item label="检查类型">{{ view.checkType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="检查地点">{{ view.site || '-' }}</el-descriptions-item>
        <el-descriptions-item label="检查人">{{ view.checker || '-' }}</el-descriptions-item>
        <el-descriptions-item label="隐患数">{{ view.riskCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="下次到期">{{ view.nextDueDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ view.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="view.details || []" size="small" border style="margin-top:12px">
        <el-table-column prop="checkItem" label="检查项" min-width="140" />
        <el-table-column prop="result" label="结果" width="100" />
        <el-table-column prop="riskDesc" label="隐患描述" min-width="160" />
        <el-table-column label="隐患照片" width="100">
          <template #default="{ row }">
            <img v-if="row.riskPhoto" :src="fullUrl(row.riskPhoto)" style="width:40px;height:40px;object-fit:cover;cursor:pointer" @click="previewImg(row.riskPhoto)" />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="handler" label="整改责任人" width="110" />
        <el-table-column prop="fixDeadline" label="整改期限" width="120" />
      </el-table>
      <div v-if="view.attachment" style="margin:8px 0">
        <b>整改后照片:</b>
        <span v-for="(u, i) in view.attachment.split(',')" :key="i" style="display:inline-block;margin:4px">
          <img :src="fullUrl(u)" style="width:80px;height:80px;object-fit:cover;border:1px solid #ddd;cursor:pointer" @click="previewImg(u)" />
        </span>
      </div>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { safetyApi, uploadFile } from '@/api/workflow'
import { useUserStore } from '@/store/user'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()
const query = reactive({ pageNum: 1, pageSize: 20, site: '', billStatus: '', overdue: null })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const viewVisible = ref(false)
const view = ref(null)
const form = reactive({
  id: null, checkDate: new Date().toISOString().substring(0, 10),
  checkType: '', site: '', checker: '', nextDueDate: '',
  attachment: '', remark: '', details: [], billStatus: 'DRAFT',
  _attachmentList: []
})

// 上传 UI refs
const riskPhotoInput = ref(null)
const attachmentInput = ref(null)
const riskPhotoTarget = ref(null)
const imgPreviewVisible = ref(false)
const imgPreviewUrl = ref('')

function fullUrl(u) { if (!u) return ''; if (u.startsWith('http')) return u; return (window.location.origin || '') + u }
function previewImg(url) { imgPreviewUrl.value = url || ''; imgPreviewVisible.value = true }
function uploadRiskPhoto(row) { riskPhotoTarget.value = row; riskPhotoInput.value && riskPhotoInput.value.click() }
function pickAttachmentFile() { attachmentInput.value && attachmentInput.value.click() }

async function onRiskPhotoChange(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  try {
    const url = await uploadFile(f)
    riskPhotoTarget.value.riskPhoto = url
    ElMessage.success('隐患照片已上传')
  } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  finally { e.target.value = '' }
}

async function onAttachmentChange(e) {
  const files = Array.from(e.target.files || [])
  for (const f of files) {
    if (f.size > 5 * 1024 * 1024) { ElMessage.warning(`${f.name} 超过 5MB, 已跳过`); continue }
    try {
      const url = await uploadFile(f)
      form._attachmentList.push(url)
    } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  }
  e.target.value = ''
}

function isOverdue(row) {
  return row.nextDueDate && new Date(row.nextDueDate).getTime() < Date.now()
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.site) delete params.site
    if (!params.billStatus) delete params.billStatus
    data.value = (await safetyApi.page(params)).data
  } finally { loading.value = false }
}

function onReset() {
  query.site = ''; query.billStatus = ''; query.overdue = null; query.pageNum = 1
  loadData()
}

function onAdd() {
  Object.assign(form, {
    id: null, checkDate: new Date().toISOString().substring(0, 10),
    checkType: '', site: '', checker: '', nextDueDate: '',
    attachment: '', remark: '', details: [], billStatus: 'DRAFT'
  })
  form._attachmentList = []
  form.details.splice(0, form.details.length)
  dialogVisible.value = true
}

function addLine() {
  form.details.push({ checkItem: '', result: '', riskDesc: '', riskPhoto: '', handler: '', fixDeadline: '' })
}

async function onEdit(row) {
  const r = await safetyApi.detail(row.id)
  Object.assign(form, r.data)
  form._attachmentList = (r.data.attachment || '').split(',').filter(Boolean)
  form.details = (r.data.details || []).map(d => ({ ...d }))
  dialogVisible.value = true
}

async function onSave() {
  if (!form.checkDate) return ElMessage.warning('请选择检查日期')
  submitting.value = true
  try {
    const payload = {
      ...form,
      attachment: form._attachmentList.join(','),
      details: form.details
    }
    delete payload._attachmentList
    if (form.id) {
      await safetyApi.update(payload); ElMessage.success('修改成功')
    } else {
      await safetyApi.add(payload); ElMessage.success('保存成功')
    }
    dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

async function onView(row) {
  const r = await safetyApi.detail(row.id)
  view.value = r.data
  viewVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除安全检查记录 ${row.recordNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await safetyApi.delete(row.id)
    ElMessage.success('删除成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核安全检查记录 ${row.recordNo}?`, '审核确认', { type: 'warning' })
  } catch { return }
  try {
    await safetyApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '审核失败') }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核安全检查记录 ${row.recordNo}? 状态将回退为草稿。`, '反审核确认', { type: 'warning' })
  } catch { return }
  try {
    await safetyApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '反审核失败') }
}

onMounted(() => { loadData() })
</script>
<style scoped>
.pager { margin-top: 12px; text-align: right; }
.overdue { color: #f56c6c; font-weight: bold; }
.thumb-list { display: flex; flex-wrap: wrap; gap: 8px; }
.thumb-item { position: relative; width: 72px; height: 72px; }
.thumb-item img { width: 100%; height: 100%; object-fit: cover; border: 1px solid #ddd; border-radius: 2px; cursor: pointer; display: block; }
.thumb-item .el-button { position: absolute; top: 2px; right: 2px; background: rgba(255,255,255,0.85); }
</style>
