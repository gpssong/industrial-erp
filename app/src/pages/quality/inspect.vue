<template>
  <view class="container">
    <!-- 类型 tab -->
    <view class="tabs">
      <view v-for="t in TYPES" :key="t.value"
        :class="['tab', type === t.value ? 'tab-active' : '']"
        @click="type = t.value">{{ t.label }}</view>
    </view>

    <!-- IQC: 扫采购入库单 -->
    <template v-if="type === 'IQC'">
      <view class="card scan-card">
        <view class="scan-title">扫采购入库单飞鹅标签 (billNo)</view>
        <view class="scan-sub muted">扫飞鹅打印的采购入库标签, 或手输入库单号 (如 RKP202610100001)</view>
        <input class="scan-input" v-model="billNo" placeholder="输入采购入库单号"
          confirm-type="done" @confirm="onLoadReceipt" />
        <view class="scan-actions">
          <button class="btn-primary" @click="onScan">📷 扫一扫</button>
          <button class="btn-plain" @click="onLoadReceipt">定位入库单</button>
        </view>
        <view v-if="scanMsg" class="muted">{{ scanMsg }}</view>
      </view>

      <view v-if="loaded" class="card">
        <view class="row"><view><view class="bill-no">{{ receipt.billNo }}<text v-if="productNames"> · {{ productNames }}</text></view>
          <view class="muted">供应商 {{ receipt.supplierName || receipt.supplierId || '—' }}</view></view>
          <view class="tag tag-draft">IQC 草稿</view></view>
        <view class="muted form-hint">逐行录入抽检/合格/不良 + 缺陷</view>
        <view class="detail" v-for="(d, i) in draft" :key="i">
          <view class="detail-name">{{ d.productName || ('明细 ' + (i + 1)) }}</view>
          <view class="form-row"><text class="form-label">抽检</text>
            <input class="form-input" v-model="d.sampleQty" type="digit" placeholder="0" /></view>
          <view class="form-row"><text class="form-label">合格</text>
            <input class="form-input" v-model="d.qualifiedQty" type="digit" placeholder="0" /></view>
          <view class="form-row"><text class="form-label">不良</text>
            <input class="form-input" v-model="d.unqualifiedQty" type="digit" placeholder="0" /></view>
        </view>
        <button class="btn-primary submit" :disabled="submitting" @click="onSubmit">提交 IQC 草稿</button>
      </view>
      <view v-else class="empty">扫单或输入单号后定位</view>
    </template>

    <!-- FAI/PAT/OQC: 简单表单 -->
    <view v-else class="card">
      <view class="muted form-hint">{{ HINT[type] }}</view>
      <view class="form-row"><text class="form-label">产品ID</text>
        <input class="form-input" v-model="form.productId" type="number" placeholder="选填" /></view>
      <view class="form-row"><text class="form-label">批次/卷号</text>
        <input class="form-input" v-model="form.batchNo" placeholder="选填" /></view>
      <view class="form-row"><text class="form-label">抽检数</text>
        <input class="form-input" v-model="form.sampleQty" type="digit" placeholder="0" /></view>
      <view class="form-row"><text class="form-label">合格数</text>
        <input class="form-input" v-model="form.qualifiedQty" type="digit" placeholder="0" /></view>
      <view class="form-row"><text class="form-label">不良数</text>
        <input class="form-input" v-model="form.unqualifiedQty" type="digit" placeholder="0" /></view>
      <button class="btn-primary submit" :disabled="submitting" @click="onSubmitQuick">提交{{ type }}草稿</button>
    </view>

    <view v-if="!loading && !loaded && type === 'IQC'" class="empty muted">等待定位入库单</view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const TYPES = [
  { value: 'IQC', label: '来料检验' },
  { value: 'FAI', label: '首件检验' },
  { value: 'PAT', label: '巡检' },
  { value: 'OQC', label: '成品检验' }
]
const HINT = {
  IQC: '扫采购入库单定位, 逐行录入来料检验',
  FAI: '首件检验: 录入产品/批次/抽检数, 提交草稿, PC 端审核',
  PAT: '巡检: 录入产品/批次/抽检数, 提交草稿, PC 端审核',
  OQC: '成品检验: 录入产品/批次/抽检数, 提交草稿, PC 端审核'
}

const type = ref('IQC')
const billNo = ref('')
const scanMsg = ref('')
const loading = ref(false)
const loaded = ref(false)
const submitting = ref(false)
const receipt = ref({})
const draft = ref([])
// v1.1.131: 明细行商品名去重后拼给入库单号用 (单据头没有品名列)
const productNames = computed(() => {
  const names = ((receipt.value && receipt.value.details) || []).map(d => d.productName).filter(Boolean)
  return [...new Set(names)].join('、')
})
const form = reactive({
  productId: '', batchNo: '', sampleQty: '', qualifiedQty: '', unqualifiedQty: ''
})

function num(v) { const n = Number(v || 0); return isFinite(n) ? n : 0 }

function onScan() {
  scanMsg.value = ''
  doScan({
    onResult: (text) => {
      const code = String(text).split(':').pop().trim()
      billNo.value = code
      onLoadReceipt()
    },
    onCancel: () => { scanMsg.value = '已取消' },
    onError: (e) => { scanMsg.value = '扫码失败, 请手输单号 ' + (e && e.message ? e.message : '') }
  })
}

async function onLoadReceipt() {
  if (!billNo.value) { uni.showToast({ title: '请输入单号', icon: 'none' }); return }
  loading.value = true
  try {
    const r = await api.purchaseReceiptPage({ pageNum: 1, pageSize: 1, billNo: billNo.value })
    const records = (r && r.records) || []
    if (!records.length) {
      uni.showToast({ title: '未找到入库单 ' + billNo.value, icon: 'none' })
      loaded.value = false
      return
    }
    const detail = await api.purchaseReceiptDetail(records[0].id)
    receipt.value = detail || {}
    const items = detail.details || []
    draft.value = items.map(d => ({
      productId: d.productId, productName: d.productName, batchNo: d.batchNo,
      sampleQty: '', qualifiedQty: '', unqualifiedQty: ''
    }))
    if (!draft.value.length) {
      // 单据无明细 → 兜底 1 行
      draft.value = [{ productId: null, productName: '(无明细)', batchNo: '', sampleQty: '', qualifiedQty: '', unqualifiedQty: '' }]
    }
    loaded.value = true
  } catch (e) {
    loaded.value = false
    console.warn('[quality-inspect.onLoadReceipt] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '定位失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function totalOf(list) {
  return list.reduce((acc, d) => ({
    s: acc.s + num(d.sampleQty),
    q: acc.q + num(d.qualifiedQty),
    u: acc.u + num(d.unqualifiedQty)
  }), { s: 0, q: 0, u: 0 })
}

async function onSubmit() {
  const t = totalOf(draft.value)
  submitting.value = true
  try {
    const head = {
      inspectionType: 'IQC',
      sourceBillType: 'PUR_RECEIPT',
      sourceBillNo: receipt.value.billNo || billNo.value,
      supplierId: receipt.value.supplierId,
      productId: (draft.value[0] && draft.value[0].productId) || null,
      batchNo: (draft.value[0] && draft.value[0].batchNo) || null,
      sampleQty: t.s, qualifiedQty: t.q, unqualifiedQty: t.u,
      result: 'HOLD', status: 'DRAFT', remark: 'App IQC 草稿'
    }
    const defects = []
    for (const d of draft.value) {
      if (num(d.unqualifiedQty) > 0) {
        defects.push({
          lineNo: defects.length + 1, defectType: '来料不良', severity: 'MINOR',
          qty: num(d.unqualifiedQty), disposal: 'REWORK', productId: d.productId
        })
      }
    }
    await api.qmsInspectionCreate({ head, defects })
    uni.showToast({ title: 'IQC 草稿已提交', icon: 'success' })
    reset()
  } catch (e) {
    console.warn('[quality-inspect.onSubmit] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function onSubmitQuick() {
  submitting.value = true
  try {
    const head = {
      inspectionType: type.value,
      sourceBillType: 'MANUAL',
      productId: form.productId ? num(form.productId) : null,
      batchNo: form.batchNo || null,
      sampleQty: num(form.sampleQty), qualifiedQty: num(form.qualifiedQty),
      unqualifiedQty: num(form.unqualifiedQty),
      result: 'HOLD', status: 'DRAFT', remark: 'App ' + type.value + ' 草稿'
    }
    const defects = []
    if (num(form.unqualifiedQty) > 0) {
      defects.push({
        lineNo: 1, defectType: type.value + '不良', severity: 'MINOR',
        qty: num(form.unqualifiedQty), disposal: 'REWORK'
      })
    }
    await api.qmsInspectionCreate({ head, defects })
    uni.showToast({ title: type.value + ' 草稿已提交', icon: 'success' })
    reset()
  } catch (e) {
    console.warn('[quality-inspect.onQuick] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function reset() {
  billNo.value = ''
  receipt.value = {}
  draft.value = []
  Object.assign(form, { productId: '', batchNo: '', sampleQty: '', qualifiedQty: '', unqualifiedQty: '' })
  loaded.value = false
  scanMsg.value = ''
}

onMounted(() => {
  if (!guardPage('qms')) {
    uni.showToast({ title: '该模块已停用', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/dashboard/index' }), 800)
    return
  }
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.tabs { display: flex; gap: 6px; margin-bottom: 10px; }
.tab { flex: 1; text-align: center; padding: 8px 4px; font-size: 13px; border-radius: 6px; background: #f4f4f5; color: #606266; }
.tab-active { background: #1e6091; color: #fff; }
.scan-title { font-size: 14px; font-weight: 600; color: #303133; }
.scan-sub { margin-top: 4px; }
.scan-input { margin-top: 8px; height: 38px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.scan-input:focus { border-color: #1e6091; outline: none; }
.scan-actions { display: flex; gap: 8px; margin-top: 10px; }
.btn-primary { flex: 1; background: #1e6091; color: #fff; border: none; border-radius: 6px; height: 38px; font-size: 14px; }
.btn-primary[disabled] { opacity: 0.5; }
.btn-plain { flex: 1; background: #f4f4f5; color: #606266; border: none; border-radius: 6px; height: 38px; font-size: 14px; }
.muted { color: #909399; font-size: 12px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.bill-no { font-weight: bold; font-size: 15px; color: #303133; }
.tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; }
.tag-draft { background: #f4f4f5; color: #909399; }
.form-hint { margin-bottom: 10px; }
.detail { border-top: 1px solid #f0f0f0; padding-top: 10px; margin-top: 10px; }
.detail:first-of-type { border-top: none; }
.detail-name { font-size: 14px; font-weight: 500; color: #303133; margin-bottom: 6px; }
.form-row { display: flex; align-items: center; margin-bottom: 6px; }
.form-label { width: 56px; font-size: 13px; color: #606266; }
.form-input { flex: 1; height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; box-sizing: border-box; font-size: 14px; }
.submit { margin-top: 12px; width: 100%; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }
</style>
