<template>
  <div>
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">基础资料 / 标签</div>
        <h1 class="xk-title">标签打印中心</h1>
        <p class="xk-sub">车间出纸在手机上（蓝牙直连打印机）；本页只做选单、预览、飞鹅通道、导出文本与日志。</p>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="loadLog">查看打印日志</el-button>
        <el-button link type="primary" @click="goTemplates">编辑这一类的模板</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-form :model="q" inline>
        <el-form-item label="标签类型">
          <el-select v-model="q.bizType" style="width: 150px" @change="onBizChange">
            <el-option value="FG_ROLL" label="成品卷标" />
            <el-option value="RAW_PALLET" label="原料垛标" />
            <el-option value="CARTON" label="箱标" />
          </el-select>
        </el-form-item>
        <el-form-item label="单据">
          <el-select v-model="q.bizId" filterable placeholder="单号/品名/客户" style="width: 300px" @change="onBillChange">
            <el-option v-for="b in bills" :key="b.id" :value="b.id"
                       :label="`${b.billNo} ${b.mainName || ''} ${fmtBatch(b.batchNo) || ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="lines.length" label="明细行">
          <el-select v-model="q.lineIds" multiple collapse-tags placeholder="不选=全部行" style="width: 320px">
            <el-option v-for="l in lines" :key="l.id" :value="l.id"
                       :label="`${l.name} × ${l.qty} ${fmtBatch(l.batchNo) || '无批号'}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板">
          <el-select v-model="q.templateId" clearable placeholder="用默认模板" style="width: 240px">
            <el-option v-for="t in templates" :key="t.id" :value="t.id"
                       :label="`${t.templateName}${t.isDefault ? ' (默认)' : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!q.bizId" :loading="loading" @click="doPreview">预览</el-button>
          <el-button :disabled="!previews.length" :loading="printing" @click="doFeiePrint">发飞鹅打印机</el-button>
          <el-button disabled title="B3 接入：印麦指令集真机取证后才能出 TSPL 文本 (docs/32 §9)">导出 TSPL</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="masterWarn" type="warning" :closable="false" show-icon :title="masterWarn" />

      <!-- 飞鹅通道的回执：note 是后端原文（A10-R1 裁定它权威），这里照抄、不改写、不替它换个更响的说法 -->
      <el-alert v-if="feieNote" type="info" :closable="false" show-icon :title="feieNote" />
      <div v-if="feieFailed.length" class="feie-failed">
        <b>云端拒绝的明细（这些已按 FAIL 落进打印日志）：</b>
        <ul><li v-for="(f, i) in feieFailed" :key="i">{{ f }}</li></ul>
      </div>

      <div class="canvas-note">
        预览忠实于<b>版式</b>，不忠实于<b>打印机中文字体</b>——中文实际效果由印麦真机取证决定（docs/32 §6.3 / §9）。
        码图（条码/二维码）屏显尺寸按 A9 给的那张 PNG 等比缩放，后端已封顶 1024 点，预览不许假设更大。
      </div>

      <div class="labels">
        <div v-for="(p, pi) in previews" :key="pi" class="label-wrap">
          <div class="label-title">第 {{ pi + 1 }} 张 · {{ p.widthDots }}×{{ p.heightDots }} 点 · {{ p.dpi }}dpi · 份数 {{ p.copies }}</div>
          <div class="missing" v-if="(p.missingRequired || []).length">
            必填但为空：<b>{{ (p.missingRequired || []).map(fieldLabel).join('、') }}</b>（不阻断打印，纸面会留白）
          </div>
          <div class="canvas" :style="canvasStyle(p)">
            <template v-for="(e, ei) in p.elements" :key="ei">
              <span v-if="e.type === 'line'" class="el-line" :style="posStyle(e, p)"></span>
              <span v-else-if="e.type === 'rect'" class="el-rect" :style="posStyle(e, p)"></span>
              <img v-else-if="(e.type === 'barcode' || e.type === 'qrcode') && e.svg" :src="e.svg"
                   class="el-code" :style="codeStyle(e, p)" />
              <span v-else class="el-text" :class="{ miss: e.missing, over: e.overflow }" :style="textStyle(e, p)">
                {{ e.type === 'barcode' ? '(条码 ' + e.content + ')' : e.content }}
              </span>
            </template>
          </div>
        </div>
        <p v-if="!previews.length" class="empty">选单据后点「预览」（预览是点一次要一次，不在进页面时替每行去请求）。</p>
      </div>

      <el-dialog v-model="logVisible" title="标签打印日志" width="1080px">
        <el-table :data="logs" size="small" border v-loading="logLoading" max-height="420">
          <el-table-column label="时间" width="160">
            <!-- LocalDateTime 序列化成 ISO 带 T，仓库通例是 .replace('T',' ').slice(0,19)（xk/Audit.vue:124 等） -->
            <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column prop="bizType" label="类型" width="100" />
          <el-table-column prop="bizNo" label="单号" width="160" show-overflow-tooltip />
          <el-table-column prop="templateCode" label="模板" width="160" show-overflow-tooltip />
          <el-table-column prop="channel" label="通道" width="90" />
          <el-table-column prop="printerName" label="打印机" width="130" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="isFail(row) ? 'danger' : 'success'" size="small">{{ isFail(row) ? '失败' : '成功' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="份数" width="72">
            <template #default="{ row }">
              {{ row.copies == null ? '—' : row.copies }}<span v-if="isFeie(row)" class="star">*</span>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <!-- N4：FAIL 行不许留空白（看着像成功）；空 message 就照实说「未提供原因」，不编「未知错误」 -->
              <span v-if="isFail(row) && !reasonOf(row)" class="no-reason">未提供原因</span>
              <span v-else-if="reasonOf(row)">{{ reasonOf(row) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="log-note">
          <span class="star">*</span>「份数」是日志行 <code>copies</code> 列的原样：<b>飞鹅（FEIE）行的 copies 恒为 1</b>
          ——一份模板要出 N 张时，渲染器把 N 段拼进同一次下发、云端 times=1，所以那一行的 1 不代表实际出纸张数。
          本页因此<b>不做「共打印 N 张」的合计</b>（合计会把飞鹅行少算）；要按张数对账看蓝牙（BT_TSPL）行，
          或看上面预览里每张标签标的「份数」。
        </p>
      </el-dialog>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { labelApi, labelTemplateApi } from '@/api/base'

const q = reactive({ bizType: 'FG_ROLL', bizId: null, lineIds: [], templateId: null })
const bills = ref([])
const lines = ref([])
const templates = ref([])
/** 字段名 → 中文名（/fields，A11 与 LabelFieldDict 同源同序）。只用来把 missingRequired 显示成人话。 */
const fieldLabels = ref({})
const previews = ref([])
const logs = ref([])
const masterWarn = ref('')
const feieNote = ref('')
const feieFailed = ref([])
const loading = ref(false)
const printing = ref(false)
const logVisible = ref(false)
const logLoading = ref(false)

const router = useRouter()

/**
 * PC 请求层（utils/request.js:102-117）的口径：code===200 时 resolve 整个 R 信封（所以取 r.data），
 * 其余 reject 一个 Error，后端那句中文在 e.msg 上（e.code 也在）。
 * HTTP 层的 403/401 走的是 axios 原始错误（没有 e.msg），所以只读 status，不读 e.response.data.msg。
 */
function errText(e) {
  if (!e) return ''
  if (e.msg) return e.msg
  const st = e.response && e.response.status
  if (st === 403) return '无权限访问该接口'
  if (st === 401) return '登录已过期'
  return e.message || ''
}
const isAuthErr = (e) => !!e && (e.code === 401 || (e.response && e.response.status === 401))

/** 画布等比：点 → px（可视宽 320px，高按点阵比例推；640×480 的点阵就是 320×240）。 */
const VIEW_W = 320
const scaleOf = (p) => VIEW_W / (p.widthDots || 1)
function canvasStyle(p) {
  return { width: VIEW_W + 'px', height: Math.round(VIEW_W * ((p.heightDots || 1) / (p.widthDots || 1))) + 'px' }
}
function posStyle(e, p) {
  const k = scaleOf(p)
  return { left: ((e.x || 0) * k) + 'px', top: ((e.y || 0) * k) + 'px', width: ((e.w || 0) * k) + 'px', height: ((e.h || 0) * k) + 'px' }
}
function textStyle(e, p) {
  const st = posStyle(e, p)
  st.fontSize = Math.max(7, Math.round((e.fontSize || 12) * scaleOf(p))) + 'px'
  st.fontWeight = e.bold ? '700' : '400'
  if (e.align === 'CENTER') st.transform = 'translateX(-50%)'
  if (e.align === 'RIGHT') st.transform = 'translateX(-100%)'
  return st
}
/**
 * 码图尺寸：模板里 barcode/qrcode 的 w/h 一般是 0（TSPL 按内容自适应，A4 的估宽也豁免了它们），
 * 直接套 posStyle 会把 <img> 压成 0×0、码图整块看不见。所以 w/h 缺时按 A9 给这张图出的
 * 像素尺寸（= 点数，LabelPreviewService:56-60，已封顶 1024 点）换算，不假设它更大。
 */
function codeStyle(e, p) {
  const st = posStyle(e, p)
  const k = scaleOf(p)
  const size = e.fontSize || 0
  if (!e.w) st.width = Math.round((e.type === 'qrcode' ? Math.min(1024, Math.max(4, size) * 25) : 300) * k) + 'px'
  if (!e.h) st.height = Math.round(Math.min(1024, Math.max(20, size)) * k) + 'px'
  return st
}

/**
 * 批号在库里可能是 "B1|B2|"（v1.1.152 审核选批次出库后的多批次行，行尾裸 | 是「无批号」那部分）。
 * 下拉里按 | 拆开给人看；打印内容不走这里——标签上照原样打（CartonLabelContextLoader 的裁定）。
 */
function fmtBatch(raw) {
  if (!raw) return ''
  const parts = String(raw).split('|')
  const listed = parts.filter(x => x !== '')
  const hasEmpty = parts.length > listed.length
  if (!listed.length) return '无批号'
  return listed.join(' / ') + (hasEmpty ? ' / 含无批号' : '')
}
const fieldLabel = (name) => fieldLabels.value[name] || name
const isFail = (row) => String(row.status || '').toUpperCase() === 'FAIL'
const isFeie = (row) => String(row.channel || '').toUpperCase() === 'FEIE'
/** 空白也算「没给原因」（N4：只判 !errorMsg 会把 " " 当成有内容，格子看起来就像成功了）。 */
const reasonOf = (row) => String(row.errorMsg || '').trim()
/** 日志时间：后端 LocalDateTime 是 ISO（带 T），按仓库通例取到秒。 */
const fmtTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—')

/**
 * 每个请求各自 try/catch，一个 403 不许把别处已经取到的数据清空（仓库通例）；
 * 失败的项目合成一条提示。每个 load 返回 { name, ok, reason, silent }（永远 resolve，
 * 所以 Promise.all 不会因一处 403 变成未处理的 reject）：
 * silent 的项（字段名单只影响显示、登录过期已由拦截器弹框）不进汇总提示。
 */
const done = (name) => ({ name, ok: true })
const fail = (name, e) => ({ name, ok: false, reason: errText(e), silent: isAuthErr(e) })

/** 多个失败合成一条提示（不是一人一条红条刷屏），文案里点明「可能无权限」。 */
function reportJobs(jobs) {
  const bad = (jobs || []).filter(j => j && !j.ok && !j.silent)
  if (!bad.length) return
  const names = bad.map(j => j.name).join('、')
  const reason = bad.map(j => j.reason).filter(Boolean)[0]
  ElMessage.warning(`${names}加载失败（可能无权限）${reason ? `：${reason}` : ''}`)
}

async function loadBills() {
  try {
    bills.value = (await labelApi.bills({ bizType: q.bizType, limit: 50 })).data || []
    return done('单据')
  } catch (e) { bills.value = []; return fail('单据', e) }
}
async function loadLines() {
  lines.value = []
  if (!q.bizId) return done('明细行')
  try {
    lines.value = (await labelApi.lines({ bizType: q.bizType, bizId: q.bizId })).data || []
    return done('明细行')
  } catch (e) { return fail('明细行', e) }
}
async function loadTemplates() {
  try {
    templates.value = (await labelTemplateApi.list({ bizType: q.bizType, status: 1 })).data || []
    return done('模板列表')
  } catch (e) { templates.value = []; return fail('模板列表', e) }
}
async function loadFields() {
  // 取不到只影响「必填但为空」那行显示成英文字段名，不当故障提醒用户
  try {
    const rows = (await labelApi.fields(q.bizType)).data || []
    const map = {}
    rows.forEach(f => { if (f && f.name) map[f.name] = f.label || f.name })
    fieldLabels.value = map
    return { name: '字段名单', ok: true, silent: true }
  } catch { fieldLabels.value = {}; return { name: '字段名单', ok: false, silent: true } }
}
async function loadMaster() {
  try {
    const m = (await labelApi.master()).data || {}
    const miss = ['producerName', 'xkNo'].filter(k => !m[k])
    masterWarn.value = miss.length
      ? `标签主数据缺 ${miss.map(fieldLabel).join('、')}：标签上会留白。请到「系统设置-品牌与工厂」补齐。` : ''
    return done('标签主数据')
  } catch (e) {
    // 主数据读不到的原因写在行内的 alert 里，不再挤进汇总提示（避免同一件事说两遍）
    masterWarn.value = '读不到标签主数据（可能无权限）：' + (errText(e) || '接口未返回原因')
    return { name: '标签主数据', ok: false, silent: true }
  }
}

function invalidatePreviews() {
  previews.value = []
  feieNote.value = ''
  feieFailed.value = []
}

async function onBizChange() {
  q.bizId = null; q.lineIds = []; lines.value = []
  invalidatePreviews()
  reportJobs(await Promise.all([loadBills(), loadTemplates(), loadFields(), loadMaster()]))
}
async function onBillChange() { q.lineIds = []; reportJobs([await loadLines()]) }

// 改了选择，上一屏预览就不是要发出去的那份了：作废它，「发飞鹅」按钮跟着禁用（打印用的是当前选择）
watch(() => [q.bizId, q.lineIds.slice(), q.templateId], invalidatePreviews)

async function doPreview() {
  loading.value = true
  feieNote.value = ''
  feieFailed.value = []
  try {
    previews.value = (await labelApi.preview({
      bizType: q.bizType, bizId: q.bizId,
      lineIds: q.lineIds.length ? q.lineIds : null,
      templateId: q.templateId || null
    })).data || []
    if (!previews.value.length) ElMessage.info('该单没有可打印的行')
  } catch (e) {
    previews.value = []
    ElMessage.error(errText(e) || '预览失败')
  } finally { loading.value = false }
}

/**
 * A10-R1：/feie/print 返回成功只表示「云端接受了下发」，不代表打印机出了纸（脱机/错 UKEY/欠费
 * 都是 HTTP 200 + ret≠0）。所以文案是「云端已接受 N 张」，不写成纸已经出来了那种话；
 * 回执里的 note 是后端原文，照原样呈现。
 */
async function doFeiePrint() {
  printing.value = true
  feieFailed.value = []
  try {
    const r = (await labelApi.feiePrint({
      bizType: q.bizType, bizId: q.bizId,
      lineIds: q.lineIds.length ? q.lineIds : null,
      templateId: q.templateId || null
    })).data || {}
    const total = r.total == null ? 0 : r.total
    const ok = r.success == null ? 0 : r.success
    const failed = r.failed == null ? 0 : r.failed
    feieNote.value = r.note || ''
    feieFailed.value = r.failedItems || []
    if (failed) ElMessage.warning(`云端已接受下发 ${ok}/${total} 张，${failed} 张被云端拒绝（原因见下方列表与打印日志）`)
    else ElMessage.success(`云端已接受下发 ${ok}/${total} 张（云端收下指令，不等于打印机已出纸）`)
  } catch (e) {
    ElMessage.error(errText(e) || '发飞鹅失败')
  } finally { printing.value = false }
}

async function loadLog() {
  logVisible.value = true
  logLoading.value = true
  try {
    logs.value = (await labelApi.logs({ bizType: q.bizType, limit: 50 })).data || []
  } catch (e) {
    // 清空旧的：刷新失败还留着上一批行，看着就像「这批是新数据」
    logs.value = []
    if (!isAuthErr(e)) ElMessage.warning(`打印日志加载失败（可能无权限）：${errText(e) || '接口未返回原因'}`)
  } finally { logLoading.value = false }
}

/** A11 的模板页支持 ?bizType= 深链，这里带上当前类型。 */
function goTemplates() {
  router.push({ path: '/base/label-template', query: { bizType: q.bizType } })
}

// M-6：进页面只拉选择用的列表，不替每一行发预览请求（预览要点「预览」才做）
onMounted(async () => { reportJobs(await Promise.all([loadBills(), loadTemplates(), loadFields(), loadMaster()])) })
</script>

<style scoped>
.xk-sub { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.canvas-note { margin: 10px 0; padding: 8px 10px; background: var(--el-color-warning-light-9);
               border-left: 3px solid var(--el-color-warning); font-size: 12px; }
.labels { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 12px; }
.label-title { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 4px; }
.missing { font-size: 12px; color: var(--el-color-danger); margin-bottom: 4px; }
.canvas { position: relative; border: 1px dashed #b5b5b5; background: #fff; overflow: hidden; }
.el-text { position: absolute; white-space: pre; line-height: 1; color: #000; }
.el-text.miss { color: #fff; background: var(--el-color-danger); }
.el-text.over { outline: 1px solid var(--el-color-warning); }
.el-line { position: absolute; background: #000; }
.el-rect { position: absolute; border: 1px solid #000; }
.el-code { position: absolute; image-rendering: pixelated; }
.feie-failed { margin: 8px 0; font-size: 12px; color: var(--el-color-danger); }
.feie-failed ul { margin: 4px 0 0; padding-left: 18px; }
.empty { color: var(--el-text-color-secondary); }
.log-note { margin: 8px 0 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.log-note .star { color: var(--el-color-danger); font-weight: 700; }
.no-reason { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-placeholder); }
</style>
