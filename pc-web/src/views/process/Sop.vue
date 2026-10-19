<template>
  <div>
    <div class="page-card">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">📐 工艺 SOP + 工艺参数 (按工序组织)</div>
        <div>
          <el-input v-model="pidSearch" placeholder="按产品筛选 (输入产品ID)" style="width:220px" clearable
            @keyup.enter="loadDefs" @clear="loadDefs" />
        </div>
      </div>

      <div style="display:flex;gap:12px">
        <!-- 左: 工序定义树 (按 productId 分组) -->
        <div class="left-panel" v-loading="defLoading">
          <div class="panel-title">工序定义</div>
          <el-empty v-if="!defGroups.length && !defLoading" description="暂无工序定义" />
          <div v-for="g in defGroups" :key="g.key">
            <div class="group-title">{{ g.title }}</div>
            <div v-for="d in g.defs" :key="d.id" class="def-node" :class="{ active: selectedDef && selectedDef.id === d.id }"
              @click="selectDef(d)">
              <span class="def-name">{{ d.processNo }}. {{ d.processName }}</span>
              <el-tag size="small" :type="d.version ? 'primary' : 'info'" style="margin-left:8px">
                {{ d.version || '默认' }}
              </el-tag>
            </div>
          </div>
        </div>

        <!-- 右: 选中工序时的 3 个 tab -->
        <div class="right-panel" style="flex:1">
          <el-empty v-if="!selectedDef" description="← 选择左侧工序以查看工艺参数 / SOP" />
          <div v-else>
            <div style="margin-bottom:12px">
              <b>{{ selectedDef.processName }}</b>
              <el-tag size="small" :type="selectedDef.version ? 'primary' : 'info'" style="margin-left:8px">
                版本 {{ selectedDef.version || '默认' }}
              </el-tag>
              <span class="muted-tip" style="margin-left:12px">{{ selectedDef.workshop || '-' }}</span>
            </div>
            <el-tabs v-model="activeTab">
              <!-- 基本信息 -->
              <el-tab-pane label="基本信息" name="info">
                <el-descriptions :column="2" border>
                  <el-descriptions-item label="工序ID">{{ selectedDef.id }}</el-descriptions-item>
                  <el-descriptions-item label="工序名">{{ selectedDef.processName }}</el-descriptions-item>
                  <el-descriptions-item label="顺序">{{ selectedDef.processNo }}</el-descriptions-item>
                  <el-descriptions-item label="车间/工位">{{ selectedDef.workshop || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="适用产品">
                    {{ selectedDef.productId ? ('产品 ' + selectedDef.productId) : '通用 (所有产品)' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="版本">{{ selectedDef.version || '默认' }}</el-descriptions-item>
                  <el-descriptions-item label="默认链">
                    <el-tag :type="selectedDef.isDefault === 1 ? 'success' : 'info'" size="small">
                      {{ selectedDef.isDefault === 1 ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="关键工序">
                    <el-tag :type="selectedDef.isCritical === 1 ? 'danger' : 'info'" size="small">
                      {{ selectedDef.isCritical === 1 ? '关键' : '普通' }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="生效日期">{{ selectedDef.effectiveDate || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="备注" :span="2">{{ selectedDef.remark || '-' }}</el-descriptions-item>
                </el-descriptions>
                <div style="margin-top:12px">
                  <el-button type="primary" @click="openDefEdit">编辑基本信息</el-button>
                </div>
              </el-tab-pane>

              <!-- 工艺参数 -->
              <el-tab-pane :label="'工艺参数 (' + params.length + ')'" name="params">
                <div style="margin-bottom:8px">
                  <el-button type="primary" size="small" @click="openParamAdd">新增参数</el-button>
                </div>
                <el-table :data="params" border stripe v-loading="paramLoading">
                  <el-table-column type="index" width="50" />
                  <el-table-column prop="paramKey" label="参数名" width="140" />
                  <el-table-column prop="paramValue" label="参数值" width="120" />
                  <el-table-column prop="unit" label="单位" width="80" />
                  <el-table-column label="下限" width="90">
                    <template #default="{ row }">{{ row.lowerLimit ?? '-' }}</template>
                  </el-table-column>
                  <el-table-column label="上限" width="90">
                    <template #default="{ row }">{{ row.upperLimit ?? '-' }}</template>
                  </el-table-column>
                  <el-table-column label="关键" width="80" align="center">
                    <template #default="{ row }">
                      <el-tag :type="row.isCritical === 1 ? 'danger' : 'info'" size="small">
                        {{ row.isCritical === 1 ? '关键' : '否' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="sortNo" label="排序" width="70" align="center" />
                  <el-table-column label="操作" width="140" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" @click="openParamEdit(row)">编辑</el-button>
                      <el-button link type="danger" size="small" @click="onParamDelete(row)">删除</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!params.length && !paramLoading" description="该工序暂无工艺参数" />
              </el-tab-pane>

              <!-- SOP 文档 -->
              <el-tab-pane :label="'SOP 文档 (' + sops.length + ')'" name="sop">
                <div style="margin-bottom:8px">
                  <el-button type="primary" size="small" @click="openSopAdd">新增 SOP</el-button>
                </div>
                <el-table :data="sops" border stripe v-loading="sopLoading">
                  <el-table-column prop="version" label="版本" width="80" />
                  <el-table-column label="状态" width="120" align="center">
                    <template #default="{ row }">
                      <el-tag :type="sopStatusTag(row.status)" size="small">{{ sopStatusLabel(row.status) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="effectiveDate" label="生效日期" width="130">
                    <template #default="{ row }">{{ row.effectiveDate || '-' }}</template>
                  </el-table-column>
                  <el-table-column label="文档URL" min-width="140">
                    <template #default="{ row }">
                      <span v-if="row.docUrl" style="color:#409eff;cursor:pointer" @click="open(row.docUrl)">{{ row.docUrl }}</span>
                      <span v-else class="muted-tip">仅内联文本</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="内容预览" min-width="200" show-overflow-tooltip>
                    <template #default="{ row }">{{ (row.content || '').slice(0, 60) || '-' }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="200" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" @click="openSopEdit(row)">编辑</el-button>
                      <el-button v-if="row.status !== 'ACTIVE'" link type="success" size="small" @click="onSopActivate(row)">激活</el-button>
                      <el-button v-if="row.status === 'DRAFT'" link type="danger" size="small" @click="onSopDelete(row)">删除</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!sops.length && !sopLoading" description="该工序暂无 SOP 文档" />
              </el-tab-pane>
            </el-tabs>
          </div>
        </div>
      </div>

      <!-- 编辑基本信息 对话框 -->
      <el-dialog v-model="defEditVisible" title="编辑工序基本信息" width="520px" destroy-on-close>
        <el-form :model="defEdit" label-width="90px">
          <el-form-item label="工序名"><el-input v-model="defEdit.processName" /></el-form-item>
          <el-form-item label="顺序"><el-input-number v-model="defEdit.processNo" :min="1" :max="99" /></el-form-item>
          <el-form-item label="车间/工位"><el-input v-model="defEdit.workshop" /></el-form-item>
          <el-form-item label="版本"><el-input v-model="defEdit.version" placeholder="留空=默认" /></el-form-item>
          <el-form-item label="生效日期">
            <el-date-picker v-model="defEdit.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="defEdit.remark" type="textarea" :rows="2" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="defEditVisible = false">取消</el-button>
          <el-button type="primary" :loading="defSaving" @click="onDefSave">保存</el-button>
        </template>
      </el-dialog>

      <!-- 工艺参数 对话框 -->
      <el-dialog v-model="paramVisible" :title="paramForm.id ? '编辑工艺参数' : '新增工艺参数'" width="560px" destroy-on-close>
        <el-form :model="paramForm" label-width="90px">
          <el-form-item label="参数名" required><el-input v-model="paramForm.paramKey" placeholder="温度/压力/速度/时间" /></el-form-item>
          <el-form-item label="参数值"><el-input v-model="paramForm.paramValue" /></el-form-item>
          <el-form-item label="单位"><el-input v-model="paramForm.unit" placeholder="℃ / MPa / m/min / s" /></el-form-item>
          <el-row :gutter="12">
            <el-col :span="8"><el-form-item label="下限"><el-input-number v-model="paramForm.lowerLimit" :controls="false" style="width:100%" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="上限"><el-input-number v-model="paramForm.upperLimit" :controls="false" style="width:100%" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="排序"><el-input-number v-model="paramForm.sortNo" :min="1" :controls="false" style="width:100%" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="关键参数">
            <el-switch v-model="paramForm.isCritical" :active-value="1" :inactive-value="0" />
            <span class="muted-tip" style="margin-left:8px">v1.1.91 报工页 UI 高亮必填</span>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="paramForm.remark" type="textarea" :rows="2" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="paramVisible = false">取消</el-button>
          <el-button type="primary" :loading="paramSaving" @click="onParamSave">保存</el-button>
        </template>
      </el-dialog>

      <!-- SOP 编辑 对话框 -->
      <el-dialog v-model="sopVisible" :title="sopForm.id ? '编辑 SOP' : '新增 SOP'" width="720px" destroy-on-close>
        <el-form :model="sopForm" label-width="90px">
          <el-row :gutter="12">
            <el-col :span="8"><el-form-item label="版本号"><el-input v-model="sopForm.version" placeholder="默认 1.0" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="生效日期">
              <el-date-picker v-model="sopForm.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item></el-col>
            <el-col :span="8"><el-form-item label="文档URL"><el-input v-model="sopForm.docUrl" placeholder="可选, 附件文件" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="SOP 内容 (Markdown)">
            <el-input v-model="sopForm.content" type="textarea" :rows="14" placeholder="纯文本 / Markdown (不渲染, 直接编辑)" />
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="sopForm.remark" type="textarea" :rows="2" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="sopVisible = false">取消</el-button>
          <el-button type="primary" :loading="sopSaving" @click="onSopSave">保存草稿</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mesApi } from '@/api/production'
import { paramApi, sopApi } from '@/api/process'

const pidSearch = ref('')
const defLoading = ref(false)
const defGroups = ref([])
const selectedDef = ref(null)
const activeTab = ref('info')

// 参数
const params = ref([])
const paramLoading = ref(false)
const paramVisible = ref(false)
const paramSaving = ref(false)
const emptyParam = () => ({
  id: null, paramKey: '', paramValue: '', unit: '',
  lowerLimit: null, upperLimit: null, sortNo: 1, isCritical: 0, remark: ''
})
const paramForm = reactive(emptyParam())

// SOP
const sops = ref([])
const sopLoading = ref(false)
const sopVisible = ref(false)
const sopSaving = ref(false)
const emptySop = () => ({
  id: null, version: '1.0', docUrl: '', content: '', effectiveDate: '', remark: ''
})
const sopForm = reactive(emptySop())

// 基本信息编辑
const defEditVisible = ref(false)
const defSaving = ref(false)
const defEdit = reactive({ id: null, processName: '', processNo: 1, workshop: '', version: '', effectiveDate: '', remark: '' })

const sopStatusTag = (s) => ({ ACTIVE: 'success', DRAFT: 'warning', DEPRECATED: 'info' }[s] || 'info')
const sopStatusLabel = (s) => ({ ACTIVE: '生效', DRAFT: '草稿', DEPRECATED: '废弃' }[s] || s)

async function open(url) { if (url) window.open(url, '_blank') }

async function loadDefs() {
  defLoading.value = true
  try {
    const pid = pidSearch.value === '' ? null : Number(pidSearch.value)
    const r = await mesApi.processDefList(pid)
    const all = r?.data || []
    // 按 productId 分组
    const map = new Map()
    for (const d of all) {
      const key = d.productId ? 'product:' + d.productId : 'common'
      if (!map.has(key)) map.set(key, [])
      map.get(key).push(d)
    }
    defGroups.value = Array.from(map.entries()).map(([key, defs]) => ({
      key,
      title: key === 'common' ? '通用工序' : ('产品 ' + key.split(':')[1]),
      defs: defs.sort((a, b) => (a.processNo || 0) - (b.processNo || 0))
    }))
    // 默认选中第一个
    const first = defGroups.value[0]?.defs[0]
    if (first && (!selectedDef.value || selectedDef.value.id === first.id)) {
      selectDef(first)
    }
  } catch (e) {
    console.warn('[Sop] 工序定义加载失败:', e)
    defGroups.value = []
  } finally {
    defLoading.value = false
  }
}

async function selectDef(d) {
  selectedDef.value = d
  activeTab.value = 'info'
  loadParams()
  loadSops()
}

async function loadParams() {
  if (!selectedDef.value) return
  paramLoading.value = true
  try {
    const r = await paramApi.list(selectedDef.value.id)
    params.value = r?.data || []
  } catch (e) {
    console.warn('[Sop] 参数加载失败:', e)
    params.value = []
  } finally {
    paramLoading.value = false
  }
}

async function loadSops() {
  if (!selectedDef.value) return
  sopLoading.value = true
  try {
    const r = await sopApi.list(selectedDef.value.id)
    sops.value = r?.data || []
  } catch (e) {
    console.warn('[Sop] SOP 加载失败:', e)
    sops.value = []
  } finally {
    sopLoading.value = false
  }
}

// 基本信息编辑
function openDefEdit() {
  Object.assign(defEdit, {
    id: selectedDef.value.id,
    processName: selectedDef.value.processName,
    processNo: selectedDef.value.processNo ?? 1,
    workshop: selectedDef.value.workshop || '',
    version: selectedDef.value.version || '',
    effectiveDate: selectedDef.value.effectiveDate || '',
    remark: selectedDef.value.remark || ''
  })
  defEditVisible.value = true
}
async function onDefSave() {
  defSaving.value = true
  try {
    await mesApi.processDefSave({
      ...defEdit,
      version: defEdit.version || null,
      effectiveDate: defEdit.effectiveDate || null
    })
    ElMessage.success('已保存')
    defEditVisible.value = false
    loadDefs()
    // 刷新当前选中
    if (selectedDef.value) {
      const r = await mesApi.processDefList(null)
      const found = (r?.data || []).find(x => x.id === selectedDef.value.id)
      if (found) selectedDef.value = found
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    defSaving.value = false
  }
}

// 参数 CRUD
function openParamAdd() {
  Object.assign(paramForm, emptyParam())
  paramForm.processDefId = selectedDef.value.id
  paramForm.sortNo = (params.value.length + 1)
  paramVisible.value = true
}
function openParamEdit(row) {
  Object.assign(paramForm, {
    id: row.id, paramKey: row.paramKey, paramValue: row.paramValue || '', unit: row.unit || '',
    lowerLimit: row.lowerLimit ?? null, upperLimit: row.upperLimit ?? null,
    sortNo: row.sortNo ?? 1, isCritical: row.isCritical ?? 0, remark: row.remark || ''
  })
  paramForm.processDefId = selectedDef.value.id
  paramVisible.value = true
}
async function onParamSave() {
  if (!paramForm.paramKey) { ElMessage.warning('参数名必填'); return }
  paramSaving.value = true
  try {
    await paramApi.save({ ...paramForm, processDefId: selectedDef.value.id })
    ElMessage.success('已保存')
    paramVisible.value = false
    loadParams()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    paramSaving.value = false
  }
}
async function onParamDelete(row) {
  await ElMessageBox.confirm(`确认删除参数「${row.paramKey}」?`, '删除', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await paramApi.delete(row.id)
    ElMessage.success('已删除')
    loadParams()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

// SOP CRUD
function openSopAdd() {
  Object.assign(sopForm, emptySop())
  sopForm.processDefId = selectedDef.value.id
  sopVisible.value = true
}
function openSopEdit(row) {
  Object.assign(sopForm, {
    id: row.id, version: row.version || '1.0', docUrl: row.docUrl || '',
    content: row.content || '', effectiveDate: row.effectiveDate || '', remark: row.remark || ''
  })
  sopForm.processDefId = selectedDef.value.id
  sopVisible.value = true
}
async function onSopSave() {
  sopSaving.value = true
  try {
    await sopApi.save({ ...sopForm, processDefId: selectedDef.value.id })
    ElMessage.success('已保存草稿 (激活后旧版本自动废弃)')
    sopVisible.value = false
    loadSops()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    sopSaving.value = false
  }
}
async function onSopActivate(row) {
  await ElMessageBox.confirm(`确认激活 SOP v${row.version}? 同工序其他生效版本将自动废弃。`, '激活确认', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await sopApi.activate(row.id)
    ElMessage.success('已激活')
    loadSops()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '激活失败')
  }
}
async function onSopDelete(row) {
  await ElMessageBox.confirm(`确认删除 SOP v${row.version}? 仅草稿可删除。`, '删除', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await sopApi.delete(row.id)
    ElMessage.success('已删除')
    loadSops()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

onMounted(loadDefs)
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
.left-panel { width:300px;border:1px solid #e4e7ed;border-radius:6px;padding:8px;overflow-y:auto;max-height:70vh }
.right-panel { min-width:0 }
.panel-title { font-weight:600;font-size:14px;margin:6px 4px }
.group-title { color:#909399;font-size:12px;margin:10px 4px 4px }
.def-node { padding:8px 10px;border-radius:4px;cursor:pointer;display:flex;align-items:center;justify-content:space-between }
.def-node:hover { background:#f4f4f5 }
.def-node.active { background:#ecf5ff }
.def-name { font-size:13px }
</style>
