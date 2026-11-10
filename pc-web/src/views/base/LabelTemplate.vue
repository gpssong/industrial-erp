<template>
  <div>
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">基础资料 / 标签</div>
        <h1 class="xk-title">标签模板</h1>
        <p class="xk-sub">坐标单位是「点」：80×60mm @203dpi = 640×480 点。保存时校验字段名与越界，不会把错误留到打印那一刻。</p>
      </div>
      <div class="xk-heading-actions">
        <el-button data-test="new-template" @click="onNew">新建模板</el-button>
        <el-button data-test="save-template" type="primary" :loading="saving" @click="onSave">保存模板</el-button>
      </div>
    </div>

    <div class="page-card">
      <!-- fix1（M6）：saving 锁覆盖全部编辑控件（页头纸头字段 + 工具条 + 编辑区），
           与「正在保存，编辑已锁定」文案一致；锁本身带 data-test 供测试断言 -->
      <div class="editor-lock" :class="{ 'is-saving': saving }" data-test="editor-lock">
      <div class="table-toolbar">
        <div class="left">
          <el-select v-model="queryBizType" clearable placeholder="全部类型" style="width: 160px" @change="loadList">
            <el-option v-for="t in BIZ_TYPES" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
          <!-- 受控下拉：先确认未保存修改再 loadTemplate，取消时 current.id 不变（Task 10 Step 7） -->
          <el-select :model-value="current.id" placeholder="选择模板" clearable style="width: 260px" @change="onTemplateChange">
            <el-option v-for="t in list" :key="t.id" :label="`${t.templateName} (${t.templateCode})`" :value="t.id" />
          </el-select>
        </div>
        <div class="right">
          <el-button link type="primary" :disabled="!current.id" data-test="version-history" @click="openHistory">版本历史</el-button>
          <el-button link type="primary" :disabled="!current.id" @click="onSetDefault">设为默认</el-button>
          <el-button link type="danger" :disabled="!current.id" @click="onDelete">删除</el-button>
        </div>
      </div>

      <el-form :model="current" label-width="110px" inline class="geo-form">
        <el-form-item label="模板编码"><el-input v-model="current.templateCode" style="width: 180px" /></el-form-item>
        <el-form-item label="模板名称"><el-input v-model="current.templateName" style="width: 180px" /></el-form-item>
        <el-form-item label="标签类型">
          <!-- 已有模板禁用类型选择：普通 update 本就不许改变 bizType（Task 10 Step 3） -->
          <el-select v-model="current.bizType" :disabled="!!current.id || saving" style="width: 140px" @change="onBizChange">
            <el-option v-for="t in BIZ_TYPES" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="宽(mm)"><el-input-number v-model="current.widthMm" :min="20" :max="200" /></el-form-item>
        <el-form-item label="高(mm)"><el-input-number v-model="current.heightMm" :min="20" :max="200" /></el-form-item>
        <el-form-item label="间隙(mm)"><el-input-number v-model="current.gapMm" :min="0" :max="20" /></el-form-item>
        <el-form-item label="dpi">
          <el-select v-model="current.dpi" style="width: 100px"><el-option :value="203" label="203" /><el-option :value="300" label="300" /></el-select>
        </el-form-item>
        <el-form-item label="出纸方向">
          <el-select v-model="current.direction" style="width: 120px"><el-option value="NORMAL" /><el-option value="REVERSE" /></el-select>
        </el-form-item>
        <el-form-item label="份数"><el-input-number v-model="current.copies" :min="1" :max="20" /></el-form-item>
        <el-form-item label="画布"><span class="canvas-hint">{{ widthDots }} × {{ heightDots }} 点</span></el-form-item>
      </el-form>

      <!-- 固定警告：两个 tab 共用，不可关闭（Task 10 Step 2） -->
      <el-alert class="print-warn" type="warning" :closable="false" show-icon
                title="屏显字形不等同打印机点阵，最终以实纸为准" data-test="print-warning" />

      <div class="ctx-bar">
        <el-radio-group v-model="ctx.sample" data-test="ctx-mode" @change="onSampleModeChange">
          <el-radio-button :value="true">示例数据</el-radio-button>
          <el-radio-button :value="false">真实单据</el-radio-button>
        </el-radio-group>
        <template v-if="!ctx.sample">
          <el-select v-model="ctx.bizId" filterable clearable placeholder="单号/品名" style="width: 260px"
                     :loading="billsLoading" data-test="bill-select" @change="onBillChange">
            <el-option v-for="b in bills" :key="b.id" :value="b.id"
                       :label="`${b.billNo} ${b.mainName || ''} ${fmtBatch(b.batchNo)}`" />
          </el-select>
          <el-select v-if="current.bizType !== 'FG_ROLL' && lines.length" v-model="ctx.lineIds" multiple collapse-tags
                     placeholder="不选=全部行" style="width: 280px" data-test="line-select">
            <el-option v-for="l in lines" :key="l.id" :value="l.id"
                       :label="`${l.name} × ${l.qty} ${fmtBatch(l.batchNo) || '无批号'}`" />
          </el-select>
          <span v-if="billError" class="bill-error" data-test="bill-error">{{ billError }}</span>
        </template>
        <el-select v-if="labelCount > 1" v-model="labelIndex" style="width: 120px" data-test="label-picker">
          <el-option v-for="n in labelCount" :key="n" :value="n - 1" :label="`第${n}/${labelCount}张`" />
        </el-select>
        <span class="ctx-spacer"></span>
        <el-button size="small" data-test="undo" :disabled="!canUndo || saving" @click="onUndo">撤销</el-button>
        <el-button size="small" data-test="redo" :disabled="!canRedo || saving" @click="onRedo">重做</el-button>
        <span class="zoom-ctl">缩放
          <el-input-number v-model="zoom" :min="4" :max="12" :step="1" size="small" style="width: 110px" data-test="zoom" />
        </span>
      </div>

      <!-- resolve 状态区：失败保留旧盒子 + 黄字；403 用固定权限文案（Task 10 Step 4） -->
      <div v-if="resolving || resolveError || staleGeometry || needBill || (resolved && resolved.issues && resolved.issues.length)" class="resolve-status">
        <span v-if="resolving" class="resolving-hint" data-test="resolving">几何解析中…</span>
        <span v-if="resolveError" class="stale-warn" data-test="resolve-error">{{ resolveError }}</span>
        <span v-else-if="staleGeometry" class="stale-warn" data-test="stale-hint">草稿已修改，画布显示可能过时</span>
        <el-button v-if="resolveError || staleGeometry" size="small" data-test="refresh-geometry" @click="queueResolve">刷新几何</el-button>
        <span v-if="needBill" class="stale-warn" data-test="need-bill">请选择业务单据</span>
        <ul v-if="resolved && resolved.issues && resolved.issues.length" class="issue-list resolve-issues" data-test="resolve-issues">
          <li v-for="(m, i) in resolved.issues" :key="i">第{{ m.index + 1 }}个元素{{ m.field ? '（' + m.field + '）' : '' }}：{{ m.message }}</li>
        </ul>
      </div>

      <el-alert v-if="inputError" type="error" :closable="false" show-icon :title="inputError" data-test="input-error" />
      <el-alert v-if="issues.length" type="error" :closable="false" show-icon
                title="保存被拦下，下列元素需要修正" >
        <ul class="issue-list"><li v-for="(m, i) in issues" :key="i">{{ m }}</li></ul>
      </el-alert>
      <el-alert v-if="saving" type="info" :closable="false" show-icon title="正在保存，编辑已锁定" data-test="save-lock" />

      <div class="el-bar">
        <el-button size="small" @click="addRow('text')">+ 文本</el-button>
        <el-button size="small" @click="addRow('multiline')">+ 多行</el-button>
        <el-button size="small" @click="addRow('barcode')">+ 条码</el-button>
        <el-button size="small" @click="addRow('qrcode')">+ 二维码</el-button>
        <el-button size="small" @click="addRow('line')">+ 分割线</el-button>
        <el-button size="small" @click="addRow('rect')">+ 矩形框</el-button>
      </div>

      <!-- Task 10 Step 7：saving 防重入锁（保留，M6 的整页锁另见外层 .editor-lock） -->
      <div class="editor-body" :class="{ 'is-saving': saving }">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="画布" name="canvas">
            <div class="editor-center" data-test="editor-center">
              <div class="layer-list" data-test="layer-list">
                <div class="layer-title">层级（绘制次序）</div>
                <div v-for="(row, i) in rows" :key="i" class="layer-item" :class="{ 'is-active': i === selectedIndex }"
                     :data-test="'layer-' + i" @click="selectedIndex = i">
                  <span class="layer-text">#{{ i }} {{ row.type }} {{ row.field || row.text || '' }}</span>
                  <span class="layer-ops">
                    <el-button link size="small" :disabled="i === 0" @click.stop="moveRow(i, -1)">上移</el-button>
                    <el-button link type="danger" size="small" @click.stop="deleteRow(i)">删</el-button>
                  </span>
                </div>
                <div v-if="!rows.length" class="layer-empty">空草稿：点上方按钮添加元素</div>
              </div>
              <div class="canvas-wrap">
                <LabelCanvas
                  :elements="canvasElements"
                  :width-dots="widthDots" :height-dots="heightDots"
                  :width-mm="current.widthMm || 0" :height-mm="current.heightMm || 0"
                  :zoom="zoom" :selected-index="selectedIndex"
                  @select="onCanvasSelect" @move="onCanvasMove" @resize="onCanvasResize" @delete="deleteRow"
                />
              </div>
              <div class="inspector-wrap" data-test="inspector-wrap">
                <LabelInspector :row="rows[selectedIndex] || null" :resolved="selectedResolved"
                                :fields="fields" :dpi="current.dpi || 203" @patch="onInspectorPatch" />
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="表格" name="table">
            <el-table :data="rows" border stripe size="small" v-loading="loading">
              <el-table-column type="index" width="46" label="#" />
              <el-table-column label="类型" width="110">
                <template #default="{ row }"><el-select v-model="row.type" size="small">
                  <el-option v-for="t in ELEMENT_TYPES" :key="t" :label="t" :value="t" /></el-select></template>
              </el-table-column>
              <el-table-column label="x" width="90">
                <!-- 不设 :min：move 保留负数给服务端报错，不暗中拖回纸边（Task 10 Step 5） -->
                <template #default="{ row }"><el-input-number v-model="row.x" :controls="false" size="small" /></template>
              </el-table-column>
              <el-table-column label="y" width="90">
                <template #default="{ row }"><el-input-number v-model="row.y" :controls="false" size="small" /></template>
              </el-table-column>
              <el-table-column label="字段" min-width="170">
                <template #default="{ row }">
                  <el-select v-model="row.field" clearable filterable size="small" placeholder="取上下文字段">
                    <el-option v-for="f in fields" :key="f.name" :label="`${f.label} ${f.name}`" :value="f.name" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="或字面文本" min-width="150">
                <template #default="{ row }"><el-input v-model="row.text" size="small" /></template>
              </el-table-column>
              <el-table-column label="字号" width="90">
                <template #default="{ row }"><el-input-number v-model="row.size" :min="1" :controls="false" size="small" /></template>
              </el-table-column>
              <el-table-column label="宽" width="80">
                <template #default="{ row }"><el-input-number v-model="row.w" :min="0" :controls="false" size="small" /></template>
              </el-table-column>
              <el-table-column label="高" width="80">
                <template #default="{ row }"><el-input-number v-model="row.h" :min="0" :controls="false" size="small" /></template>
              </el-table-column>
              <el-table-column label="宽倍" width="80">
                <template #default="{ row }"><el-input-number v-model="row.mulX" :min="1" :max="8" :controls="false" size="small" placeholder="默认" /></template>
              </el-table-column>
              <el-table-column label="高倍" width="80">
                <template #default="{ row }"><el-input-number v-model="row.mulY" :min="1" :max="8" :controls="false" size="small" placeholder="默认" /></template>
              </el-table-column>
              <el-table-column label="cell" width="80">
                <template #default="{ row }"><el-input-number v-model="row.cell" :min="2" :max="8" :controls="false" size="small" placeholder="默认" /></template>
              </el-table-column>
              <el-table-column label="对齐" width="100">
                <template #default="{ row }"><el-select v-model="row.align" size="small">
                  <el-option value="LEFT" /><el-option value="CENTER" /><el-option value="RIGHT" /></el-select></template>
              </el-table-column>
              <el-table-column label="粗体" width="70">
                <template #default="{ row }"><el-checkbox v-model="row.bold" /></template>
              </el-table-column>
              <el-table-column label="必填" width="70">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ $index }">
                  <el-button link size="small" :disabled="$index === 0" @click="moveRow($index, -1)">上移</el-button>
                  <el-button link type="danger" size="small" @click="deleteRow($index)">删</el-button>
                </template>
              </el-table-column>
            </el-table>

            <p class="foot-hint">越界判定：字宽按真机实测的点阵（中文 TSS24.BF2 = 24 点/字、ASCII 字体"2" = 12 点/字，再乘按字号算的放大倍率，docs/34）。文本超宽会自动拆行往下压，所以拦的是「右侧放不下一个字符」与「拆完越过下边界」；line/rect 按声明宽高查；条码/二维码宽度不拦（TSPL 按内容自适应）。屏显预览不忠实打印机中文字库，最终以实纸为准。</p>
          </el-tab-pane>
        </el-tabs>
      </div>
      </div><!-- /editor-lock -->

      <!-- Task 11：版本历史抽屉（已保存模板右上角）。按需读取，回滚须二次确认。 -->
      <el-drawer v-model="historyOpen" title="版本历史" size="420px" :before-close="closeHistory">
        <div class="history-panel" data-test="history-panel">
          <p v-if="historyLoading" class="history-status">加载中…</p>
          <p v-else-if="historyError" class="history-status is-err">{{ historyError }}</p>
          <p v-else-if="!historyList.length" class="history-status">暂无历史版本，保存修改后会保留上一版。</p>
          <template v-else>
            <ul class="history-list">
              <li v-for="v in historyList" :key="v.seq"
                  :class="['history-item', { 'is-active': historyViewSeq === v.seq }]"
                  data-test="history-item" :data-seq="v.seq">
                <div class="history-meta">
                  <span class="history-seq">v{{ v.seq }}</span>
                  <span class="history-time">{{ fmtHistTime(v.createTime) }}</span>
                  <span class="history-who">{{ v.createByName || v.createBy || '-' }}</span>
                </div>
                <div class="history-meta sub">
                  <span>{{ v.elementCount ?? '?' }} 个元素</span>
                  <span>{{ v.elementsBytes ?? '?' }} 字节</span>
                </div>
                <div class="history-actions">
                  <el-button size="small" link type="primary" :disabled="historyBusy" @click="viewHistory(v)">查看</el-button>
                  <el-button size="small" link type="danger" :disabled="historyBusy" @click="restoreVersion(v.seq)">回滚</el-button>
                </div>
              </li>
            </ul>
          </template>

          <!-- 单版本只读摘要（historyOne，不写 rows） -->
          <div v-if="historyView" class="history-view" data-test="history-view">
            <div class="history-view-head">
              <b>版本 v{{ historyView.seq }} 内容（只读）</b>
            </div>
            <p class="history-view-sub">
              bizType={{ historyView.bizType }} · 份数={{ historyView.copies }} ·
              纸面 {{ historyView.widthMm }}×{{ historyView.heightMm }}mm @{{ historyView.dpi }}dpi
            </p>
            <pre class="history-json" data-test="history-view-json">{{ prettyHistoryElements(historyView) }}</pre>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { labelApi, labelTemplateApi } from '@/api/base'
import LabelCanvas from '@/components/LabelCanvas.vue'
import LabelInspector from '@/components/LabelInspector.vue'

const BIZ_TYPES = [
  { code: 'FG_ROLL', name: '成品卷标' },
  { code: 'RAW_PALLET', name: '原料垛标' },
  { code: 'CARTON', name: '箱标' },
  { code: 'CERT', name: '合格证(本期不出)' }
]
const ELEMENT_TYPES = ['text', 'multiline', 'barcode', 'qrcode', 'line', 'rect']

// 嵌入「打印管理」页时由父页传 initialBizType（打印中心「编辑这一类模板」切过来）；
// 独立路由 /base/label-template 访问时仍读 route.query.bizType（向后兼容）。
const props = defineProps({ initialBizType: { type: String, default: '' } })

const route = useRoute()
const list = ref([])
// Task 10 Step 2：rows 是稳定数组引用，整体加载一律 splice；两个 tab 与两个子组件都由它派生，
// resolve 响应绝不回写 rows。
const rows = reactive([])
const fields = ref([])
const issues = ref([])
const inputError = ref('')
const loading = ref(false)
const saving = ref(false)
const queryBizType = ref('')
const activeTab = ref('canvas')
const zoom = ref(6)
const selectedIndex = ref(-1)
const labelIndex = ref(0)
const current = reactive({
  id: null, templateCode: '', templateName: '', bizType: 'FG_ROLL',
  widthMm: 80, heightMm: 60, gapMm: 3, dpi: 203, direction: 'NORMAL', copies: 1,
  status: 1, remark: '', isDefault: 0
})
// 上下文选择（Task 10 Step 3）：默认示例；真实单据选择不是模板脏数据的一部分
const ctx = reactive({ sample: true, bizId: null, lineIds: [] })
const bills = ref([])
const lines = ref([])
const billsLoading = ref(false)
const billError = ref('')
// fix1（M1）：elements JSON 解析失败标志（损坏模板禁止保存）
const elementsParseFailed = ref(false)
const fmtBatch = b => (b ? `批号:${b}` : '')

/** 换算与服务端同源：203dpi 的真值是 203.2（8 点/毫米），否则 80mm 会算成 639 点、比后端画布少 1 点 */
const trueDpi = (dpi) => (dpi === 203 ? 203.2 : dpi)
const dots = (mm, dpi) => Math.floor((mm * trueDpi(dpi)) / 25.4)
const widthDots = computed(() => dots(current.widthMm || 0, current.dpi || 203))
const heightDots = computed(() => dots(current.heightMm || 0, current.dpi || 203))

/* ---------- Task 10 Step 4：generation + 150ms 防抖（简报原文，状态都在本组件） ---------- */
const resolved = ref(null)
const resolveError = ref('')
const resolving = ref(false)
const resolvedGen = ref(-1)
let resolveGen = 0
let resolveTimer
let disposed = false

function queueResolve() {
  const gen = ++resolveGen
  clearTimeout(resolveTimer)
  resolveTimer = setTimeout(() => refreshGeometry(gen), 150)
}

async function refreshGeometry(gen) {
  if (disposed || gen !== resolveGen) return
  const payload = buildResolveRequest()
  if (!payload) {
    resolving.value = false
    return
  }
  resolving.value = true
  try {
    const result = await labelApi.resolve(payload)
    if (disposed || gen !== resolveGen) return
    resolved.value = result.data
    resolvedGen.value = gen
    resolveError.value = ''
  } catch (error) {
    if (disposed || gen !== resolveGen) return
    const code = error.response?.status || error.code
    resolveError.value = Number(code) === 403
      ? '无权限访问: base:label:template'
      : '几何刷新失败，显示可能过时'
  } finally {
    if (!disposed && gen === resolveGen) resolving.value = false
  }
}

// resolveGen 是简报原文的非响应式 let；用响应式标志把「最新成功之后是否又编辑过/失败过」
// 暴露给模板（不改动简报函数本身）。resolvedGen 只在最新 gen 成功时变化。
const geometryDirty = ref(false)
watch(resolvedGen, () => { geometryDirty.value = false })
const hasCurrentGeometry = computed(() => !!resolved.value && !geometryDirty.value && !resolving.value && !resolveError.value)
const staleGeometry = computed(() => !!resolved.value && !hasCurrentGeometry.value)
const needBill = computed(() => !ctx.sample && !ctx.bizId)

const labelCount = computed(() => (resolved.value && Array.isArray(resolved.value.labels) ? resolved.value.labels.length : 0))
const canvasElements = computed(() => {
  const r = resolved.value
  if (!r || !Array.isArray(r.labels) || !r.labels.length) return []
  const idx = Math.min(Math.max(labelIndex.value, 0), r.labels.length - 1)
  return r.labels[idx].elements || []
})
const selectedResolved = computed(() => canvasElements.value.find(e => e.index === selectedIndex.value) || null)
watch(labelCount, n => { if (labelIndex.value >= n) labelIndex.value = Math.max(0, n - 1) })
watch(() => rows.length, n => { if (selectedIndex.value >= n) selectedIndex.value = n - 1 })

/** 完整 resolve 载荷（Task 10 Step 4）：lineIds 必须拷贝；真实模式无 bizId ⇒ null + 「请选择业务单据」。 */
function buildResolveRequest() {
  if (!ctx.sample && !ctx.bizId) return null
  let elements
  try {
    elements = JSON.stringify(cleanRows())
  } catch (e) {
    inputError.value = e.message
    return null
  }
  // inputError 有两个来源：M1 的 elements 解析失败（loadTemplate 置位，须常驻到下次切模板/新建），
  // 和这里 cleanRows 的校验失败（每次 resolve 自有）。解析失败时 rows 为 []，cleanRows 不会抛，
  // 无条件清空会把 loadTemplate 刚置的「模板内容无法解析」抹掉——loadTemplate 末尾就调 queueResolve，
  // 150ms 后红条消失，用户只看到保存被静默拦下。故只清属于本通道的那份。
  if (!elementsParseFailed.value) inputError.value = ''
  return {
    bizType: current.bizType,
    elements,
    header: {
      widthMm: current.widthMm, heightMm: current.heightMm, gapMm: current.gapMm,
      dpi: current.dpi, direction: current.direction, copies: current.copies
    },
    sample: ctx.sample,
    bizId: ctx.sample ? null : ctx.bizId,
    lineIds: ctx.sample ? null : [...ctx.lineIds]
  }
}

/* ---------- Task 10 Step 6：统一撤销 50 步（简报原文） ---------- */
let history = []
let historyCursor = -1
let historyTimer
let restoringHistory = false
const rowsSnapshot = () => JSON.stringify(rows)

function recordRows() {
  clearTimeout(historyTimer)
  const snapshot = rowsSnapshot()
  if (history[historyCursor] === snapshot) return
  history = history.slice(0, historyCursor + 1)
  history.push(snapshot)
  if (history.length > 51) history.shift()
  historyCursor = history.length - 1
}

function resetHistory() {
  clearTimeout(historyTimer)
  history = [rowsSnapshot()]
  historyCursor = 0
}

function undoRows() {
  recordRows()
  if (historyCursor <= 0) return
  restoringHistory = true
  historyCursor--
  rows.splice(0, rows.length, ...JSON.parse(history[historyCursor]))
  restoringHistory = false
}

// redo：先清合并定时器；当前 rows 与 cursor 快照不一致 ⇒ 有新修改，recordRows 后不再 redo（清掉 redo 链）
function redoRows() {
  clearTimeout(historyTimer)
  if (rowsSnapshot() !== history[historyCursor]) {
    recordRows()
    return
  }
  if (historyCursor >= history.length - 1) return
  restoringHistory = true
  historyCursor++
  rows.splice(0, rows.length, ...JSON.parse(history[historyCursor]))
  restoringHistory = false
}

// history/cursor 是简报原文的普通变量；historyTick 只驱动按钮可用性，不进 rows 撤销
const historyTick = ref(0)
const canUndo = computed(() => { historyTick.value; return historyCursor > 0 || rowsSnapshot() !== history[historyCursor] })
const canRedo = computed(() => { historyTick.value; return historyCursor < history.length - 1 && rowsSnapshot() === history[historyCursor] })
function flushHistory() { clearTimeout(historyTimer); recordRows(); historyTick.value++ }
function afterMutation() { recordRows(); historyTick.value++ }
function onUndo() { if (saving.value) return; undoRows(); historyTick.value++ }
function onRedo() { if (saving.value) return; redoRows(); historyTick.value++ }

// rows 同步 watch：几何立即失效（不受 restoringHistory 影响，undo/redo 也要作废旧 resolve）；
// 历史用 150ms 合并定时器，一次 Object.assign/连续打字只记一步。
watch(rows, () => {
  geometryDirty.value = true
  queueResolve()
  if (!restoringHistory) {
    clearTimeout(historyTimer)
    historyTimer = setTimeout(() => { recordRows(); historyTick.value++ }, 150)
  }
}, { flush: 'sync' })
watch(
  () => [current.widthMm, current.heightMm, current.gapMm, current.dpi, current.direction, current.copies, current.bizType],
  () => { geometryDirty.value = true; queueResolve() },
  { flush: 'sync' }
)
watch(
  () => [ctx.sample, ctx.bizId, [...ctx.lineIds]],
  () => { geometryDirty.value = true; queueResolve() },
  { flush: 'sync' }
)

/* ---------- Task 10 Step 2：解析/清洗（11 旧键 + 三声明值；缺省三键继续缺省） ---------- */
const optionalInt = value => {
  if (value == null || value === '' || Number(value) === 0) return undefined
  // fix1（M3）：非有限输入直接拒绝（返回 undefined），防止 NaN 序列化成 null 发往服务端
  if (!Number.isFinite(Number(value))) return undefined
  return Math.round(Number(value))
}

const newFields = e => ({
  mulX: optionalInt(e.mulX), mulY: optionalInt(e.mulY), cell: optionalInt(e.cell)
})

function parseElements(json) {
  let parsed
  try {
    parsed = JSON.parse(json || '[]')
  } catch {
    // fix1（M1）：损坏的 elements JSON 不再静默变空草稿——置失败标志，
    // 由调用方（loadTemplate）走 inputError 通道提示「模板内容无法解析」，并禁止保存
    // （否则保存会把整份模板的 elements 覆盖成 []，数据丢失）。
    elementsParseFailed.value = true
    return []
  }
  return parsed.map(e => ({
    type: e.type || 'text', x: e.x || 0, y: e.y || 0, text: e.text || '', field: e.field || '',
    size: e.size || 24, bold: !!e.bold, align: e.align || 'LEFT',
    w: e.w || 0, h: e.h || 0,
    // required 空值保持缺省，不改成 false；未知 field 字符串原样保留
    required: e.required == null || e.required === '' ? undefined : !!e.required,
    ...newFields(e)
  }))
}

/** clean：x/y/w/h/size 取有限整数（UI 提交前主动取整）；非数输入先在 UI 报错，绝不把 NaN 字符串发往服务器。 */
function cleanRows() {
  return rows.map((e, i) => {
    const int = (key) => {
      const n = Number(e[key])
      if (e[key] == null || e[key] === '' || !Number.isFinite(n)) {
        throw new Error(`第 ${i + 1} 个元素的 ${key} 不是有效数字，请先修正`)
      }
      return Math.round(n)
    }
    return {
      type: e.type, x: int('x'), y: int('y'),
      text: (e.text || '').trim() || undefined,
      field: (e.field || '').trim() || undefined,
      size: int('size'), bold: !!e.bold, align: e.align || 'LEFT',
      w: int('w'), h: int('h'), required: !!e.required,
      ...newFields(e)
    }
  })
}

/* ---------- Task 10 Step 5：画布交互 ⇒ 一次 rows 修改（一手势一条撤销） ---------- */
function onCanvasSelect(index) { selectedIndex.value = index }

function onCanvasMove({ index, dx, dy }) {
  if (saving.value) return
  const row = rows[index]
  if (!row) return
  flushHistory()
  // 整数点相加；保留负数给服务端报错，不暗中拖回纸边
  row.x = Math.round(Number(row.x) || 0) + dx
  row.y = Math.round(Number(row.y) || 0) + dy
  afterMutation()
}

function resizePatch(row, box, deltaX, deltaY) {
  if (row.type === 'text' || row.type === 'multiline') {
    const segment = box.segments[0]
    if (!segment || !segment.w || !segment.h) return null
    const stepX = segment.w / box.mulX
    const stepY = segment.h / box.mulY
    const patch = {}
    if (deltaX) patch.mulX = Math.max(1, Math.min(8,
      box.mulX + Math.round(deltaX / stepX)))
    if (deltaY) patch.mulY = Math.max(1, Math.min(8,
      box.mulY + Math.round(deltaY / stepY)))
    return patch
  }
  if (row.type === 'qrcode') {
    const step = box.displayBounds.w / box.cell
    return { cell: Math.max(2, Math.min(8,
      box.cell + Math.round((deltaX || deltaY) / step))) }
  }
  const patch = {}
  if (row.type !== 'barcode') patch.w = Math.max(1, Math.round(box.displayBounds.w + deltaX))
  patch.h = Math.max(row.type === 'rect' ? 4 : 1,
    Math.round(box.displayBounds.h + deltaY))
  return patch
}

function onCanvasResize({ index, deltaX, deltaY }) {
  if (saving.value) return
  // 没有当前几何（未解析/已过期/失败）⇒ 不知道有效档位，禁止 resize 并提示刷新
  if (!hasCurrentGeometry.value) {
    ElMessage.warning('几何未解析或已过期，请先刷新几何再缩放')
    return
  }
  const row = rows[index]
  const box = canvasElements.value.find(e => e.index === index)
  if (!row || !box) return
  const patch = resizePatch(row, box, deltaX, deltaY)
  if (!patch || !Object.keys(patch).length) return
  flushHistory()
  Object.assign(row, patch)
  afterMutation()
}

function onInspectorPatch(partial) {
  if (saving.value) return
  const row = rows[selectedIndex.value]
  if (!row) return
  flushHistory()
  Object.assign(row, partial)
  afterMutation()
}

function addRow(type) {
  if (saving.value) return
  flushHistory()
  rows.push({
    type, x: 20, y: rows.length ? (rows[rows.length - 1].y + 40) : 20,
    // 二维码的「字号」不是点高，是渲染器换算模块边长的原料：cell = clamp(2..8, round(max(12,size)/6))，见 LabelGeometry#qrCell。
    // 给 7 会得到 cell=2 ⇒ 21 模块只有 42 点 ≈ 5.25mm，车间实际扫不出来；24 才落到 cell=4（84 点 ≈ 10.5mm）。
    // 这里给 24 与 sql/155 种子模板一致；三个新键不预置：缺省继续缺省，打开面板不写入有效倍率。
    text: '', field: '', size: type === 'barcode' ? 60 : 24,
    bold: false, align: 'LEFT', w: type === 'line' ? 600 : (type === 'rect' ? 620 : 0), h: type === 'line' ? 2 : 0,
    required: false
  })
  selectedIndex.value = rows.length - 1
  afterMutation()
}

function moveRow(i, delta) {
  if (saving.value) return
  const j = i + delta
  if (j < 0 || j >= rows.length) return
  flushHistory()
  ;[rows[i], rows[j]] = [rows[j], rows[i]]
  selectedIndex.value = j
  afterMutation()
}

function deleteRow(i) {
  if (saving.value || i < 0 || i >= rows.length) return
  flushHistory()
  rows.splice(i, 1)
  afterMutation()
}

/* ---------- 列表 / 字段 / 单据 / 明细（各自 generation，防快速切换后旧选项覆盖） ---------- */
async function loadList() {
  loading.value = true
  try {
    list.value = (await labelTemplateApi.list(queryBizType.value ? { bizType: queryBizType.value } : {})).data || []
  } catch (e) {
    ElMessage.warning(e.message || '模板列表加载失败（可能无权限）')
    list.value = []
  } finally { loading.value = false }
}

let fieldGen = 0
async function loadFields(bizType) {
  const gen = ++fieldGen
  try {
    const r = await labelApi.fields(bizType)
    if (gen !== fieldGen || disposed) return
    fields.value = r.data || []
  } catch (e) {
    if (gen !== fieldGen || disposed) return
    fields.value = []
    ElMessage.warning(e.message || '字段名单加载失败')
  }
}

let billGen = 0
async function loadBills() {
  const gen = ++billGen
  billsLoading.value = true
  billError.value = ''
  try {
    const r = await labelApi.bills({ bizType: current.bizType, limit: 50 })
    if (gen !== billGen || disposed) return
    bills.value = r.data || []
  } catch (e) {
    if (gen !== billGen || disposed) return
    // fix1（I1/R4/R9）：/base/label/bills 要求 base:label:list（或生产/采购/销售列表权限，或
    // admin 角色），而本页路由权限是 base:label:template ⇒ 只有模板权限的用户切「真实单据」必 403。
    // 权限点不改（全局约束 4）：优雅降级——403 给精确文案，示例模式仍可用、草稿不清除。
    // request.js:135-138 对 HTTP 401/403 静默 reject 原始 err（不弹全局 ElMessage），所以这里
    // 是唯一的一次提示：403 走精确文案，其余沿用原错误信息。真实单据列表失败单独提示，
    // 不动 rows、不把 ctx.sample 改回 true（buildResolveRequest 仍返回 null ⇒ 显示「请选择业务单据」，
    // 不冒充 sample 成功）。
    bills.value = []
    const is403 = Number(e.response?.status || e.code) === 403
    billError.value = is403
      ? '无权限访问: base:label:list（真实单据模式需要该权限，示例模式仍可用）'
      : (e.message || '单据列表加载失败')
    // 唯一一次提示（常驻的 billError 提示条 + 这条），拦截器对 403 静默、不弹第二条
    ElMessage.warning(billError.value)
  } finally {
    if (gen === billGen) billsLoading.value = false
  }
}

let lineGen = 0
async function loadLines() {
  const gen = ++lineGen
  if (!ctx.bizId || current.bizType === 'FG_ROLL') { lines.value = []; return }
  try {
    const r = await labelApi.lines({ bizType: current.bizType, bizId: ctx.bizId })
    if (gen !== lineGen || disposed) return
    lines.value = r.data || []
  } catch (e) {
    if (gen !== lineGen || disposed) return
    lines.value = []
    // fix1（I1/R4）：与 bills 同款 403 精确文案（lines 端点同一组权限）
    const msg = Number(e.response?.status || e.code) === 403
      ? '无权限访问: base:label:list（真实单据模式需要该权限，示例模式仍可用）'
      : (e.message || '明细行加载失败')
    billError.value = msg
    ElMessage.warning(msg)
  }
}

function onSampleModeChange(real) {
  // el-radio-button 的 change 回传新值：false = 真实单据
  if (real === false) loadBills()
}
function onBillChange() {
  ctx.lineIds = []
  lines.value = []
  loadLines()
}
async function onBizChange() {
  // 仅新建模板可切类型；切类型清空 bizId/lineIds/旧 fields，示例模式仍可用
  ctx.bizId = null
  ctx.lineIds = []
  bills.value = []
  lines.value = []
  billError.value = ''
  fields.value = []
  ++billGen
  ++lineGen
  await loadFields(current.bizType)
  if (!ctx.sample) await loadBills()
}

/* ---------- Task 10 Step 7：脏检查 / 加载 / 保存 ---------- */
const SAVE_KEYS = ['id', 'templateCode', 'templateName', 'bizType', 'widthMm', 'heightMm', 'gapMm', 'dpi', 'direction', 'copies', 'status', 'remark']
const signatureOf = payload => JSON.stringify({ ...Object.fromEntries(SAVE_KEYS.map(k => [k, payload[k] ?? null])), elements: payload.elements })
const savedSignature = ref(null)
function draftSignature() {
  try {
    return signatureOf({ ...current, elements: JSON.stringify(cleanRows()) })
  } catch {
    // fix1（M2）：注释「视为脏」⇒ 返回一个必与 savedSignature 不同的哨兵值。
    // （旧代码返回 null：若 savedSignature 恰好也是 null，null !== null 为 false ⇒ 反而「不脏」，与注释矛盾。）
    return '__DRAFT_INVALID__'
  }
}
const hasUnsavedChanges = computed(() => draftSignature() !== savedSignature.value)

let pickGen = 0
/** 取代原 onPick 内部加载；Task 11 历史回滚后也调用它。过期 detail 丢弃（pickGen）。 */
async function loadTemplate(id) {
  if (!id) return
  const gen = ++pickGen
  // 模板切换开始：作废所有在途 resolve，主动清空旧 boxes（网络失败保留规则只适用于同一草稿会话）
  ++resolveGen
  clearTimeout(resolveTimer)
  resolved.value = null
  resolvedGen.value = -1
  resolveError.value = ''
  geometryDirty.value = false
  resolving.value = false
  let t
  try {
    const r = await labelTemplateApi.detail(id)
    if (disposed || gen !== pickGen) return
    t = r.data || {}
  } catch (e) {
    if (disposed || gen !== pickGen) return
    ElMessage.error(e.message || '模板加载失败')
    queueResolve() // 加载失败仍留在当前草稿：重新解析当前 rows
    return
  }
  Object.assign(current, {
    id: t.id, templateCode: t.templateCode, templateName: t.templateName, bizType: t.bizType,
    widthMm: t.widthMm, heightMm: t.heightMm, gapMm: t.gapMm, dpi: t.dpi,
    direction: t.direction, copies: t.copies, status: t.status, remark: t.remark, isDefault: t.isDefault
  })
  // fix1（M4）：切模板先清空 fields，避免新 bizType 的字段返回前下拉仍列着上一个 bizType 的字段
  fields.value = []
  elementsParseFailed.value = false
  rows.splice(0, rows.length, ...parseElements(t.elements))
  // fix1（M1）：elements 损坏 ⇒ 走 inputError 通道提示，并禁止保存（避免把模板覆盖成 []）
  elementsParseFailed.value ? (inputError.value = '模板内容无法解析') : (inputError.value = '')
  issues.value = []
  selectedIndex.value = -1
  labelIndex.value = 0
  ctx.sample = true
  ctx.bizId = null
  ctx.lineIds = []
  bills.value = []
  lines.value = []
  billError.value = ''
  // loadTemplate 不主动重置撤销栈：普通保存（add 成功后加载刚存模板）须保留撤销链；
  // 切模板/新建由调用方显式 resetHistory()（Task 11 亦单独调用 resetHistory）。
  historyTick.value++
  savedSignature.value = draftSignature()
  await loadFields(t.bizType)
  queueResolve()
}

async function onTemplateChange(id) {
  if (!id || id === current.id) return
  // Task 11：回滚/查看在途时禁止切模板（避免 historyGen 失效后旧请求写错模板）
  if (historyBusy.value) return
  closeHistory()
  if (hasUnsavedChanges.value) {
    try {
      await ElMessageBox.confirm('当前模板有未保存的修改，切换后将丢弃。确认切换？', '提示', { type: 'warning' })
    } catch { return } // 取消：current.id 不变（下拉是受控的）
  }
  await loadTemplate(id)
  resetHistory() // 切模板重置栈，避免撤销回到上一个模板的 rows
  historyTick.value++
}

/** fix1（M5/R10）：force=true 跳过未保存确认——删除成功后的内联重置用（模板已不存在，
 *  草稿无主，无需询问）；「新建模板」按钮仍走普通确认路径。 */
async function onNew(force) {
  if (!force && hasUnsavedChanges.value) {
    try {
      await ElMessageBox.confirm('当前模板有未保存的修改，新建后将丢弃。确认新建？', '提示', { type: 'warning' })
    } catch { return }
  }
  closeHistory() // Task 11：新建前失效历史抽屉的在途请求
  ++pickGen // 丢弃在途 detail
  ++resolveGen
  clearTimeout(resolveTimer)
  resolved.value = null
  resolvedGen.value = -1
  resolveError.value = ''
  geometryDirty.value = false
  resolving.value = false
  Object.assign(current, {
    id: null, templateCode: '', templateName: '', bizType: 'FG_ROLL',
    widthMm: 80, heightMm: 60, gapMm: 3, dpi: 203, direction: 'NORMAL', copies: 1, status: 1, remark: '', isDefault: 0
  })
  rows.splice(0, rows.length)
  fields.value = [] // fix1（M4）：新建也清旧字段列表
  elementsParseFailed.value = false
  issues.value = []
  inputError.value = ''
  selectedIndex.value = -1
  labelIndex.value = 0
  ctx.sample = true
  ctx.bizId = null
  ctx.lineIds = []
  bills.value = []
  lines.value = []
  billError.value = ''
  resetHistory()
  historyTick.value++
  savedSignature.value = draftSignature()
  loadFields('FG_ROLL')
}

async function onSave() {
  if (saving.value) return
  // fix1（M1）：elements 损坏（模板内容无法解析）时禁止保存——
  // 否则会把整份模板的 elements 覆盖成 []，数据丢失。
  if (elementsParseFailed.value) {
    inputError.value = '模板内容无法解析'
    ElMessage.error('模板内容无法解析，不能保存')
    return
  }
  let payload
  try {
    payload = { ...current, elements: JSON.stringify(cleanRows()) }
  } catch (e) {
    inputError.value = e.message
    ElMessage.error(e.message)
    return
  }
  inputError.value = ''
  saving.value = true
  issues.value = []
  // update 的已保存快照 = 提交时 payload（不是迟到响应、不是重新 detail）
  const submittedSignature = signatureOf(payload)
  try {
    if (payload.id) {
      await labelTemplateApi.update(payload)
      savedSignature.value = submittedSignature
      ElMessage.success('已保存')
      await loadList()
    } else {
      // add 是 R<Void>：没有 data.id。成功后重新 list，按唯一 templateCode 找新行并加载，
      // 防止再点保存重复新增。
      await labelTemplateApi.add(payload)
      await loadList()
      let found = list.value.find(t => t.templateCode === payload.templateCode)
      if (!found) {
        const r = await labelTemplateApi.list({ bizType: payload.bizType })
        found = (r.data || []).find(t => t.templateCode === payload.templateCode)
      }
      if (found) {
        await loadTemplate(found.id)
        ElMessage.success('已保存')
      } else {
        savedSignature.value = submittedSignature
        ElMessage.warning('已保存，但列表中未找到新模板，请手动选择')
      }
    }
  } catch (e) {
    const msg = e.message || '保存失败'
    // 服务端 gate() 把每条问题用 "; " 拼起来，这里拆回列表逐条指到元素
    issues.value = msg.split('; ')
    ElMessage.error('模板校验未通过')
  } finally { saving.value = false }
}

/* ---------- Task 11：版本历史抽屉（按需读取 + 回滚二次确认） ---------- */
const historyOpen = ref(false)
const historyList = ref([])
const historyLoading = ref(false)
const historyError = ref('')
const historyView = ref(null)      // historyOne 只读摘要，绝不写 rows
const historyViewSeq = ref(null)
const historyBusy = ref(false)     // 回滚/查看在途，禁重复回滚与切换模板
let historyGen = 0
const fmtHistTime = t => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')
function prettyHistoryElements(v) {
  try { return JSON.stringify(JSON.parse(v.elements || '[]'), null, 2) }
  catch { return String(v.elements || '') }
}
function closeHistory() {
  ++historyGen
  historyView.value = null
  historyViewSeq.value = null
  // ElDrawer 关闭按钮走 :before-close="closeHistory"：本函数 resolve 后框架才翻 modelValue，
  // 但显式置 false 让「新建/切模板」调 closeHistory() 时也能真正收起面板（否则遮罩残留挡下拉）
  historyOpen.value = false
}
async function openHistory() {
  if (!current.id || historyBusy.value) return
  historyOpen.value = true
  historyView.value = null
  historyViewSeq.value = null
  await loadHistory(current.id)
}
async function loadHistory(id) {
  const gen = ++historyGen
  historyList.value = []
  historyError.value = ''
  historyLoading.value = true
  try {
    const r = await labelTemplateApi.history(id)
    if (gen !== historyGen || disposed) return
    // 列表接口只回摘要（seq/createBy/createByName/createTime/elementsBytes/elementCount），
    // 不含完整 elements —— 单版内容才按需 historyOne
    historyList.value = r.data || []
  } catch (e) {
    if (gen !== historyGen || disposed) return
    // 明确失败，不伪造空列表（Step 2）
    historyError.value = Number(e.response?.status || e.code) === 404
      ? '该模板已不存在，无法读取历史。'
      : '历史读取失败，请重试。'
  } finally {
    if (gen === historyGen) historyLoading.value = false
  }
}
/** 查看单版：按需 historyOne，只读摘要/JSON，不写 rows（Step 2） */
async function viewHistory(v) {
  if (historyBusy.value) return
  historyBusy.value = true
  historyViewSeq.value = v.seq
  historyView.value = null
  const gen = ++historyGen
  try {
    const r = await labelTemplateApi.historyOne(current.id, v.seq)
    if (gen !== historyGen || disposed) return
    historyView.value = r.data || null
  } catch (e) {
    if (gen !== historyGen || disposed) return
    ElMessage.error(e.message || '版本读取失败')
  } finally {
    if (gen === historyGen) historyBusy.value = false
  }
}
/** 回滚必须二次确认（Step 3）。busy 期间禁重复回滚及切换模板，finally 恢复。 */
async function restoreVersion(seq) {
  const id = current.id
  const warning = hasUnsavedChanges.value ? '当前未保存的修改将被放弃。' : ''
  let confirmed = false
  try {
    await ElMessageBox.confirm(
      `${warning}回滚到版本 ${seq}？当前已保存版本会先留存快照。`,
      '确认回滚', { type: 'warning' }
    )
    confirmed = true
  } catch { return } // 取消：不显示「回滚失败」
  if (!confirmed) return
  historyBusy.value = true
  try {
    await labelTemplateApi.rollback(id, seq)
    // 成功：只加载同 id 最新主表，清旧错误/选中，重新 resolve，刷新抽屉；
    // 回滚本身也是一次保存，会新增一个 seq（当前版本先留史）。
    await loadTemplate(id)
    resetHistory()
    await loadHistory(id)
    ElMessage.success('已回滚到版本 ' + seq)
  } catch (e) {
    // API 失败保留当前草稿与画布；400 展示服务端闸的具体元素错误，404 目标已失效
    const code = Number(e.response?.status || e.code)
    if (code === 400) {
      issues.value = (e.message || '回滚校验未通过').split('; ')
      ElMessage.error('回滚校验未通过')
    } else if (code === 403) {
      ElMessage.error('无权限访问: base:label:template')
    } else if (code === 404) {
      // 目标历史版本已被裁掉（保留最近 20 版）或模板已删：给精确文案，不清草稿
      ElMessage.error('目标历史版本不存在（可能已被裁掉），画布未改动')
    } else {
      ElMessage.error(e.message || '回滚失败')
    }
  } finally {
    historyBusy.value = false
  }
}

async function onSetDefault() {
  try {
    await labelTemplateApi.setDefault(current.id)
    ElMessage.success('已设为该类型默认模板'); await loadList()
  } catch (e) { ElMessage.error(e.message || '设置失败') }
}

async function onDelete() {
  await ElMessageBox.confirm(`删除模板 ${current.templateName}?`, '提示', { type: 'warning' })
  try {
    await labelTemplateApi.delete(current.id)
    ElMessage.success('已删除')
    // fix1（M5/R10）：删除成功后直接重置为新建态，**不**调 onNew() 的未保存确认——
    // 删除前若做过编辑 hasUnsavedChanges=true，onNew() 会就一个已不存在的模板弹「确认新建？」，
    // 取消后编辑器仍显示已删除模板、后续保存会 PUT 不存在的 id。force=true 跳过确认。
    await onNew(true)
    await loadList()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

/* ---------- 键盘：Ctrl/Cmd+S 只在本页阻止默认；输入框内 Ctrl+Z 保留原生 ---------- */
const EDITABLE_SELECTOR =
  'input, textarea, select, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], .el-input__inner, .el-textarea__inner, .el-select__input'
function onKeydown(e) {
  if (!(e.ctrlKey || e.metaKey)) return
  const k = (e.key || '').toLowerCase()
  if (k === 's') {
    e.preventDefault()
    if (!saving.value) onSave()
    return
  }
  if (k !== 'z' && k !== 'y') return
  const target = e.target
  if (target && typeof target.closest === 'function' && target.closest(EDITABLE_SELECTOR)) return // 原生文本撤销
  const canvasRoot = document.querySelector('[data-test="label-canvas"]')
  if (!canvasRoot || !(target === canvasRoot || canvasRoot.contains(target))) return // 焦点在画布才操作 rows 栈
  e.preventDefault()
  if (k === 'y' || e.shiftKey) onRedo(); else onUndo()
}

onBeforeRouteLeave(async () => {
  if (!hasUnsavedChanges.value) return true
  try {
    await ElMessageBox.confirm('当前模板有未保存的修改，离开后将丢弃。确认离开？', '提示', { type: 'warning' })
    return true
  } catch { return false }
})

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  resetHistory()
  savedSignature.value = draftSignature()
  await loadList()
  await loadFields(current.bizType)
  // 支持从打印中心带 ?bizType=CARTON 进来（或嵌入页传 initialBizType），直接筛好
  const q = props.initialBizType || route.query?.bizType
  if (q && BIZ_TYPES.some(t => t.code === q)) { queryBizType.value = q; current.bizType = q; await loadList(); await loadFields(q) }
  queueResolve()
})

// 嵌入页已挂载后父页再切 bizType（el-tabs 内容常驻），watch 重新预筛
watch(() => props.initialBizType, async (val) => {
  if (val && BIZ_TYPES.some(t => t.code === val)) {
    queryBizType.value = val; current.bizType = val
    await loadList(); await loadFields(val)
    queueResolve()
  }
})

onBeforeUnmount(() => {
  disposed = true
  ++resolveGen
  ++historyGen
  ++pickGen
  clearTimeout(resolveTimer)
  clearTimeout(historyTimer)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.xk-sub { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.geo-form { margin: 8px 0 12px; }
.canvas-hint { color: var(--el-text-color-secondary); font-size: 13px; }
.el-bar { margin: 8px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.issue-list { margin: 0; padding-left: 18px; }
.foot-hint { margin-top: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
.print-warn { margin-bottom: 8px; }
.ctx-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
.ctx-spacer { flex: 1; }
.zoom-ctl { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; }
.bill-error { color: #f56c6c; font-size: 12px; }
.resolve-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 6px 0; font-size: 13px; }
.resolving-hint { color: #909399; }
.stale-warn { color: #e6a23c; }
.resolve-issues { color: #e6a23c; font-size: 12px; width: 100%; }
/* 窗口缩小时横向滚动访问层级/画布/属性面板（Task 10 Step 8） */
.editor-center { display: flex; gap: 12px; align-items: flex-start; overflow-x: auto; padding-bottom: 6px; }
.layer-list { flex: none; width: 200px; border: 1px solid #ebeef5; border-radius: 4px; padding: 6px; }
.layer-title { font-size: 12px; color: #909399; margin-bottom: 6px; }
.layer-item { display: flex; align-items: center; justify-content: space-between; gap: 4px; padding: 4px 6px; border-radius: 3px; cursor: pointer; font-size: 12px; }
.layer-item:hover { background: #f5f7fa; }
.layer-item.is-active { background: #ecf5ff; color: #409eff; }
.layer-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.layer-ops { flex: none; display: inline-flex; }
.layer-empty { color: #909399; font-size: 12px; padding: 8px 4px; }
.canvas-wrap { flex: none; max-width: 100%; }
.inspector-wrap { flex: none; width: 360px; border: 1px solid #ebeef5; border-radius: 4px; }
/* saving 期间锁定全部编辑控件（各变更函数另有 saving 防重入守卫） */
.editor-body.is-saving { pointer-events: none; opacity: 0.6; }
/* Task 11 版本历史抽屉 */
.history-panel { font-size: 13px; }
.history-status { color: var(--el-text-color-secondary); margin: 0 0 8px; }
.history-status.is-err { color: var(--el-color-danger); }
.history-list { list-style: none; margin: 0; padding: 0; border-top: 1px solid #ebeef5; }
.history-item { padding: 8px 4px; border-bottom: 1px solid #f0f2f5; }
.history-item.is-active { background: #ecf5ff; }
.history-meta { display: flex; align-items: center; gap: 8px; }
.history-meta.sub { margin-top: 2px; color: var(--el-text-color-secondary); font-size: 12px; }
.history-seq { font-weight: 600; color: var(--el-color-primary); }
.history-who { color: var(--el-text-color-secondary); font-size: 12px; }
.history-actions { margin-top: 4px; }
.history-view { margin-top: 14px; border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; }
.history-view-head { margin-bottom: 4px; }
.history-view-sub { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 6px; }
.history-json { background: #f5f7fa; border-radius: 4px; padding: 8px; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 260px; overflow: auto; margin: 0; }
/* fix1（M6）：锁扩展到页头纸头字段/工具条（.editor-lock 包住除「新建模板」外的全部编辑控件） */
.editor-lock.is-saving { pointer-events: none; }
.editor-lock.is-saving .el-input__inner,
.editor-lock.is-saving .el-select__wrapper { opacity: 0.5; }
</style>