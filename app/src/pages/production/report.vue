<template>
  <view class="container">
    <!-- 扫单/手输 入口 -->
    <view v-if="!loaded" class="card scan-card">
      <view class="scan-title">扫生产单飞鹅标签 (billNo) 报工</view>
      <view class="scan-sub muted">扫飞鹅打印的生产单标签二维码, 或手输生产单号 (如 PD202610090001)</view>
      <input class="scan-input" v-model="billNo" placeholder="输入生产单号" confirm-type="done" @confirm="onManual" />
      <view class="scan-actions">
        <button class="btn-primary" @click="onScan">📷 扫一扫</button>
        <button class="btn-plain" @click="onManual">定位报工</button>
      </view>
      <view v-if="scanMsg" class="muted">{{ scanMsg }}</view>
    </view>

    <!-- 定位到单据后, 展示工序实例 + 报工表单 -->
    <template v-else>
      <view class="card">
        <view class="row" style="display:flex;justify-content:space-between;align-items:center">
          <view>
            <view class="bill-no">{{ order.billNo }}</view>
            <view class="muted">{{ order.productName || '—' }} × {{ order.planQty || 0 }}</view>
          </view>
          <view class="muted" @click="reset">重扫</view>
        </view>
      </view>

      <view v-if="!processes.length" class="card empty-warn">
        <view>该生产单未展开工序 (开单时没配默认工序链)。</view>
        <view class="muted">请在 PC 端「生产管理 → 工序配置」配好该产品的默认工序链, 再重新开单/开工。</view>
      </view>

      <template v-else>
        <view class="card" v-for="p in processes" :key="p.id">
          <view class="proc-head" style="display:flex;justify-content:space-between;align-items:center">
            <view class="proc-name">#{{ p.processNo }} {{ p.processName }}</view>
            <view :class="['tag', 'tag-' + stKey(p.status)]">{{ stLabel(p.status) }}</view>
          </view>
          <view class="muted" style="margin-top:2px">
            计划 {{ p.planQty || 0 }} · 合格 {{ p.goodQty || 0 }} · 不良 {{ p.lossQty || 0 }}
            <text v-if="p.operator"> · {{ p.operator }}</text>
          </view>

          <!-- 报工表单 (未完成才显示) -->
          <view v-if="p.status !== 2 && p.status !== 3" class="report-form">
            <view class="form-row">
              <text class="form-label">合格数</text>
              <input class="form-input" v-model="forms[p.id].good" type="number" placeholder="0" />
            </view>
            <view class="form-row">
              <text class="form-label">不良数</text>
              <input class="form-input" v-model="forms[p.id].bad" type="number" placeholder="0" />
            </view>
            <view class="form-row">
              <text class="form-label">操作人</text>
              <input class="form-input" v-model="forms[p.id].operator" placeholder="工人姓名" />
            </view>

            <!-- v1.1.99 C3: 关键工艺参数录入 (关键参数越限则本次不计合格/不自动完工) -->
            <view v-if="params[p.id] && params[p.id].length" class="param-block">
              <view class="param-title">工艺参数 <text class="muted">(关键项超上下限将判不良)</text></view>
              <view class="form-row" v-for="par in params[p.id]" :key="par.paramKey">
                <text class="form-label" style="width:auto">
                  <text v-if="par.isCritical === 1" class="param-critical">★</text>
                  {{ par.paramKey }}
                </text>
                <input class="form-input" v-model="forms[p.id]['param_' + par.paramKey]"
                       type="digit"
                       :placeholder="parHint(par)" />
              </view>
            </view>

            <button class="btn-primary" style="margin-top:10px" :disabled="submitting"
              @click="onReport(p)">提交报工</button>
          </view>
        </view>
      </template>
    </template>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '@/api/index.js'
import { doScan } from '../../utils/scan.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const loaded = ref(false)
const order = ref({})
const processes = ref([])
const billNo = ref('')
const scanMsg = ref('')
const submitting = ref(false)
const forms = reactive({})
const params = reactive({})   // v1.1.99 C3: 每道工序的关键工艺参数 (fromDefId → mes_process_param)

function stLabel(s) { return { 0: '未开工', 1: '进行中', 2: '完成', 3: '回退' }[s] ?? '未知' }
function stKey(s) { return { 0: 'info', 1: 'warn', 2: 'ok', 3: 'danger' }[s] ?? 'info' }

// 参数输入框 placeholder: 上下限 + 单位 (帮工人知道范围)
function parHint(par) {
  const parts = []
  if (par.lowerLimit != null) parts.push('≥' + par.lowerLimit)
  if (par.upperLimit != null) parts.push('≤' + par.upperLimit)
  if (par.unit) parts.push(par.unit)
  return parts.length ? parts.join(' ') : '实测值'
}

// 拉某工序 (fromDefId) 的关键工艺参数
async function loadParams(p) {
  if (!p.fromDefId) { params[p.id] = []; return }
  try {
    const r = await api.pdmParamList(p.fromDefId)
    const list = (r && r.data) || []
    // 只保留配了上下限的参数 (可判越限的); 关键参数优先
    params[p.id] = list.filter(x => x.upperLimit != null || x.lowerLimit != null || x.isCritical === 1)
  } catch (e) { params[p.id] = [] }
}

function onScan() {
  scanMsg.value = ''
  // doScan 走回调 (onResult/onCancel), 不返回对象 (与现有 scan 页一致)
  doScan({
    onResult: (text) => {
      // 飞鹅标签 QR 内容 = billNo (prd_order_feie.ftl <QR>billNo</QR>), 取最后一段兼容 {billNo}:{工序}
      const code = String(text).split(':').pop().trim()
      billNo.value = code
      onManual()
    },
    onCancel: () => { scanMsg.value = '已取消' },
    onError: (e) => { scanMsg.value = '扫码失败, 请手输生产单号 ' + (e && e.message ? e.message : '') }
  })
}

async function onManual() {
  const no = (billNo.value || '').trim()
  if (!no) { scanMsg.value = '请先扫标签或输入生产单号'; return }
  scanMsg.value = '加载中...'
  try {
    // 1) 按 billNo 查生产单拿 id (App 端 page 支持 billNo 过滤)
    const r = await api.prdOrderPage({ billNo: no, pageNum: 1, pageSize: 1 })
    const recs = (r && (r.records || (r.data && r.data.records))) || []
    const ord = recs[0]
    if (!ord) { scanMsg.value = '未找到生产单 ' + no; return }
    order.value = ord
    // 2) 查工序实例
    const pr = await api.prdOrderProgress(String(ord.id))
    const ps = (pr && pr.data) || []
    processes.value = ps
    // 初始化每道工序的报工表单
    for (const p of ps) forms[p.id] = { good: '', bad: '', operator: '' }
    // v1.1.99 C3: 拉每道工序的关键工艺参数
    for (const p of ps) await loadParams(p)
    loaded.value = true
  } catch (e) {
    scanMsg.value = '加载失败: ' + ((e && (e.msg || e.message)) || e)
  }
}

async function onReport(p) {
  const f = forms[p.id] || {}
  const good = Number(f.good || 0)
  const bad = Number(f.bad || 0)
  if (good <= 0 && bad <= 0) {
    if (typeof uni !== 'undefined') uni.showToast({ title: '合格/不良至少填一个', icon: 'none' })
    return
  }
  // v1.1.99 C3: 组装工艺参数实测值 (key=paramKey, 仅提交已填项)
  const paramValues = {}
  for (const par of (params[p.id] || [])) {
    const v = f['param_' + par.paramKey]
    if (v != null && String(v) !== '') {
      const n = Number(v)
      if (!isNaN(n)) paramValues[par.paramKey] = n
    }
  }
  submitting.value = true
  try {
    await api.mesReport({
      orderId: order.value.id,
      processId: p.id,
      goodQty: good,
      badQty: bad,
      operator: f.operator || '',
      paramValues
    })
    if (typeof uni !== 'undefined') uni.showToast({ title: '报工成功', icon: 'success' })
    // 刷新该单工序进度
    const pr = await api.prdOrderProgress(String(order.value.id))
    processes.value = (pr && pr.data) || []
    for (const x of processes.value) forms[x.id] = { good: '', bad: '', operator: f.operator || '' }
  } catch (e) {
    if (typeof uni !== 'undefined') uni.showToast({ title: (e && (e.msg || e.message)) || '报工失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function reset() {
  loaded.value = false
  order.value = {}
  processes.value = []
  billNo.value = ''
  scanMsg.value = ''
}

onMounted(() => {
  // 模块开关短路 (生产模块被管理员全局关 → 回工作台)
  if (!guardPage('production')) return
})
</script>

<style scoped>
.container { padding: 12px; }
.card { background:#fff;border-radius:8px;padding:14px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.05) }
.scan-card { text-align:center;padding:28px 14px }
.scan-title { font-size:16px;font-weight:600;margin-bottom:6px }
.scan-sub { font-size:12px;margin-bottom:14px }
.scan-input { border:1px solid #dcdfe6;border-radius:6px;padding:10px;font-size:15px;width:100%;box-sizing:border-box }
.scan-actions { margin-top:14px;display:flex;gap:10px }
.btn-primary { background:#409eff;color:#fff;border-radius:6px;padding:10px 20px;font-size:14px;border:none;flex:1 }
.btn-plain { background:#f0f2f5;color:#333;border-radius:6px;padding:10px 20px;font-size:14px;border:none;flex:1 }
.bill-no { font-size:18px;font-weight:700 }
.muted { color:#909399;font-size:12px }
.empty-warn { color:#e6a23c }
.proc-head { margin-bottom:4px }
.proc-name { font-size:15px;font-weight:600 }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-info { background:#f0f2f5;color:#606266 }
.tag-warn { background:#fdf6ec;color:#e6a23c }
.tag-ok { background:#f0f9eb;color:#67c23a }
.tag-danger { background:#fef0f0;color:#f56c6c }
.report-form { margin-top:10px;border-top:1px dashed #ebeef5;padding-top:10px }
.form-row { display:flex;align-items:center;gap:8px;margin-bottom:8px }
.form-label { width:50px;font-size:13px;color:#606266;flex-shrink:0 }
.form-input { flex:1;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px }
.param-block { margin-top:10px;background:#f8f9fb;border-radius:6px;padding:10px }
.param-title { font-size:13px;font-weight:600;margin-bottom:8px;color:#606266 }
.param-critical { color:#f56c6c;font-weight:700;margin-right:4px }
</style>
