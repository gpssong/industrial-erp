<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="租客">
          <el-select v-model="query.tenantId" filterable clearable placeholder="全部" style="width:180px">
            <el-option v-for="t in tenants" :key="t.id" :label="t.tenantName" :value="t.id" />
          </el-select>
        </el-form-item>
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
          <el-button v-if="userStore.hasPerm('work:emergency:add')" @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增预案</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="planNo" label="预案编号" width="160" />
        <el-table-column prop="tenantName" label="租客" min-width="140">
          <template #default="{ row }">{{ row.tenantName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="planType" label="预案类型" width="120">
          <template #default="{ row }">{{ row.planType || '-' }}</template>
        </el-table-column>
        <el-table-column prop="scenario" label="适用场景" min-width="160" show-overflow-tooltip />
        <el-table-column prop="owner" label="负责人" width="110">
          <template #default="{ row }">{{ row.owner || '-' }}</template>
        </el-table-column>
        <el-table-column prop="nextDrillDate" label="下次演练" width="120">
          <template #default="{ row }"><span :class="{ 'overdue': isOverdue(row) }">{{ row.nextDrillDate || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:emergency:edit')" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:emergency:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:emergency:check')" link type="success" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus === 'CHECKED' && userStore.hasPerm('work:emergency:uncheck')" link type="warning" @click="onUncheck(row)">反审核</el-button>
            <el-button link type="primary" @click="onView(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" :page-sizes="[10, 20, 50, 100]" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑应急预案' : '新增应急预案'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="租客">
              <el-select v-model="form.tenantId" filterable clearable style="width:100%">
                <el-option v-for="t in tenants" :key="t.id" :label="t.tenantName" :value="t.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="预案类型"><el-input v-model="form.planType" placeholder="消防/触电/泄漏..." /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="负责人"><el-input v-model="form.owner" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="适用场景"><el-input v-model="form.scenario" type="textarea" :rows="2" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="最近演练"><el-date-picker v-model="form.drillDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="下次演练"><el-date-picker v-model="form.nextDrillDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="应急电话"><el-input v-model="form.contactPhone" placeholder="紧急联系人电话" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="处置步骤">
          <el-button @click="addLine" type="primary" plain size="small">添加步骤</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="序号" width="70"><template #default="{ row, $index }"><el-input-number v-model="row.stepNo" :min="1" controls-position="right" size="small" style="width:100%" /></template></el-table-column>
            <el-table-column label="处置步骤" min-width="360"><template #default="{ row }"><el-input v-model="row.stepDesc" size="small" /></template></el-table-column>
            <el-table-column label="步骤照片" width="120"><template #default="{ row }">
              <el-tooltip v-if="row.stepPhoto" :content="row.stepPhoto" placement="top">
                <img :src="fullUrl(row.stepPhoto)" style="width:32px;height:32px;object-fit:cover;border-radius:2px;cursor:pointer" @click="previewImg(row.stepPhoto)" />
              </el-tooltip>
              <el-button v-else link type="primary" size="small" @click="uploadStepPhoto(row)">上传</el-button>
            </template></el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index, 1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="演练照片">
              <el-button @click="openImgPicker(() => 'drillPhotos', '演练照片')" type="primary" plain size="small">添加照片</el-button>
              <div v-if="form._drillPhotoList && form._drillPhotoList.length" class="thumb-list">
                <div v-for="(u, i) in form._drillPhotoList" :key="i" class="thumb-item">
                  <img :src="fullUrl(u)" @click="previewImg(u)" />
                  <el-button link type="danger" size="small" @click="form._drillPhotoList.splice(i, 1)">删</el-button>
                </div>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="演练附件">
              <el-button @click="pickAttachmentFile" type="primary" plain size="small" :loading="form._uploadingAtt">上传文件</el-button>
              <span v-if="form.drillAttachment" class="att-link" style="margin-left:8px;word-break:break-all">
                <a :href="fullUrl(form.drillAttachment)" target="_blank">{{ form.drillAttachment.split('/').pop() }}</a>
              </span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
        <input ref="stepImgInput" type="file" accept="image/*" style="display:none" @change="onStepImgChange" />
        <input ref="drillPhotosInput" type="file" accept="image/*" multiple style="display:none" @change="onDrillPhotosChange" />
        <input ref="attInput" type="file" accept="image/*,.pdf,.doc,.docx,.xls,.xlsx" style="display:none" @change="onAttChange" />
        <el-dialog v-model="imgPreviewVisible" width="600px" destroy-on-close>
          <img :src="fullUrl(imgPreviewUrl)" style="width:100%;display:block" />
        </el-dialog>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="!form.id || form.billStatus === 'DRAFT'" type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="viewVisible" title="应急预案详情" width="760px" destroy-on-close>
      <el-descriptions :column="2" border v-if="view">
        <el-descriptions-item label="预案编号">{{ view.planNo }}</el-descriptions-item>
        <el-descriptions-item label="租客">{{ view.tenantName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="预案类型">{{ view.planType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="负责人">{{ view.owner || '-' }}</el-descriptions-item>
        <el-descriptions-item label="应急电话">{{ view.contactPhone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="最近演练">{{ view.drillDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="下次演练">{{ view.nextDrillDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="演练附件">
          <a v-if="view.drillAttachment" :href="fullUrl(view.drillAttachment)" target="_blank">{{ view.drillAttachment.split('/').pop() }}</a>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="适用场景" :span="2">{{ view.scenario || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="view.drillPhotos" style="margin:8px 0">
        <b>演练照片:</b>
        <span v-for="(u, i) in view.drillPhotos.split(',')" :key="i" style="display:inline-block;margin:4px">
          <img :src="fullUrl(u)" style="width:80px;height:80px;object-fit:cover;border:1px solid #ddd;cursor:pointer" @click="previewImg(u)" />
        </span>
      </div>
      <el-table :data="view.details || []" size="small" border style="margin-top:12px">
        <el-table-column prop="stepNo" label="序号" width="70" />
        <el-table-column prop="stepDesc" label="处置步骤" min-width="300" />
        <el-table-column label="步骤照片" width="100">
          <template #default="{ row }">
            <img v-if="row.stepPhoto" :src="fullUrl(row.stepPhoto)" style="width:40px;height:40px;object-fit:cover;cursor:pointer" @click="previewImg(row.stepPhoto)" />
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { emergencyApi, tenantApi, uploadFile } from '@/api/workflow'
import { useUserStore } from '@/store/user'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()
const query = reactive({ pageNum: 1, pageSize: 20, tenantId: null, billStatus: '', overdue: null })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const viewVisible = ref(false)
const view = ref(null)
const tenants = ref([])
// 上传 UI: 演练照片 (数组, 保存时拼逗号), 演练附件 (单文件 URL), 步骤照片 (per-row)
const form = reactive({
  id: null, tenantId: null, planType: '', scenario: '', owner: '',
  contactPhone: '', drillDate: '', nextDrillDate: '',
  drillAttachment: '', remark: '', details: [], billStatus: 'DRAFT',
  _drillPhotoList: []
})

// 图片预览
const imgPreviewVisible = ref(false)
const imgPreviewUrl = ref('')
function previewImg(url) { imgPreviewUrl.value = url || ''; imgPreviewVisible.value = true }
function fullUrl(u) { if (!u) return ''; if (u.startsWith('http')) return u; return (window.location.origin || '') + u }

// refs for file inputs
const stepImgInput = ref(null)
const drillPhotosInput = ref(null)
const attInput = ref(null)
const stepPhotoTarget = ref(null)  // currently editing row

function openImgPicker(cb) { cb ? cb() : drillPhotosInput.value && drillPhotosInput.value.click() }
function uploadStepPhoto(row) {
  stepPhotoTarget.value = row
  stepImgInput.value && stepImgInput.value.click()
}
function pickAttachmentFile() {
  form._uploadingAtt = true
  attInput.value && attInput.value.click()
}

async function onStepImgChange(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  try {
    const url = await uploadFile(f)
    stepPhotoTarget.value.stepPhoto = url
    ElMessage.success('步骤照片已上传')
  } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  finally { e.target.value = '' }
}

async function onDrillPhotosChange(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  for (const f of files) {
    if (f.size > 5 * 1024 * 1024) { ElMessage.warning(`${f.name} 超过 5MB, 已跳过`); continue }
    try {
      const url = await uploadFile(f)
      form._drillPhotoList.push(url)
    } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  }
  e.target.value = ''
}

async function onAttChange(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  try {
    const url = await uploadFile(f)
    form.drillAttachment = url
    ElMessage.success('演练附件已上传')
  } catch (err) { ElMessage.error('上传失败: ' + (err.message || err)) }
  finally { form._uploadingAtt = false; e.target.value = '' }
}

function isOverdue(row) {
  return row.nextDrillDate && new Date(row.nextDrillDate).getTime() < Date.now()
}

async function loadTenants() {
  if (tenants.value.length === 0) {
    tenants.value = (await tenantApi.list()).data
  }
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.tenantId) delete params.tenantId
    if (!params.billStatus) delete params.billStatus
    data.value = (await emergencyApi.page(params)).data
  } finally { loading.value = false }
}

function onReset() {
  query.tenantId = null; query.billStatus = ''; query.overdue = null; query.pageNum = 1
  loadData()
}

async function onAdd() {
  await loadTenants()
  Object.assign(form, {
    id: null, tenantId: null, planType: '', scenario: '', owner: '',
    contactPhone: '', drillDate: '', nextDrillDate: '',
    drillAttachment: '', remark: '', details: [], billStatus: 'DRAFT'
  })
  form._drillPhotoList = []
  form.details.splice(0, form.details.length)
  dialogVisible.value = true
}

function addLine() {
  const n = form.details.length + 1
  form.details.push({ stepNo: n, stepDesc: '', stepPhoto: '' })
}

async function onEdit(row) {
  await loadTenants()
  const r = await emergencyApi.detail(row.id)
  Object.assign(form, r.data)
  form._drillPhotoList = (r.data.drillPhotos || '').split(',').filter(Boolean)
  form.details = (r.data.details || []).map(d => ({ ...d }))
  dialogVisible.value = true
}

async function onSave() {
  submitting.value = true
  try {
    // 演练照片: 数组 → 逗号分隔 (后端字段 drill_photos TEXT)
    const payload = {
      ...form,
      drillPhotos: form._drillPhotoList.join(','),
      details: form.details
    }
    delete payload._drillPhotoList
    delete payload._uploadingAtt
    if (form.id) {
      await emergencyApi.update(payload); ElMessage.success('修改成功')
    } else {
      await emergencyApi.add(payload); ElMessage.success('保存成功')
    }
    dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

async function onView(row) {
  const r = await emergencyApi.detail(row.id)
  view.value = r.data
  viewVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除应急预案 ${row.planNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await emergencyApi.delete(row.id)
    ElMessage.success('删除成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核应急预案 ${row.planNo}?`, '审核确认', { type: 'warning' })
  } catch { return }
  try {
    await emergencyApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '审核失败') }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核应急预案 ${row.planNo}? 状态将回退为草稿。`, '反审核确认', { type: 'warning' })
  } catch { return }
  try {
    await emergencyApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '反审核失败') }
}

onMounted(async () => { await loadTenants(); loadData() })
</script>
<style scoped>
.pager { margin-top: 12px; text-align: right; }
.overdue { color: #f56c6c; font-weight: bold; }
.thumb-list { display: flex; flex-wrap: wrap; gap: 8px; }
.thumb-item { position: relative; width: 72px; height: 72px; }
.thumb-item img { width: 100%; height: 100%; object-fit: cover; border: 1px solid #ddd; border-radius: 2px; cursor: pointer; display: block; }
.thumb-item .el-button { position: absolute; top: 2px; right: 2px; background: rgba(255,255,255,0.85); }
</style>
