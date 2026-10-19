<template>
  <view class="container">
    <!-- 选产品 (产品ID 数字输入, 简化版; 未来加 base_product 选择器) -->
    <view class="card search-bar">
      <view class="search-row">
        <input class="input" v-model="productId" type="number" placeholder="产品ID (留空=通用工序)" @confirm="loadDefs" />
        <button class="btn-search" @click="loadDefs">查工序</button>
      </view>
    </view>

    <!-- 工序定义列表 -->
    <view v-if="!loadingDefs && !defs.length" class="empty">未找到工序定义, 请在 PC 端「工序配置」维护</view>
    <view class="card proc-card" v-for="d in defs" :key="d.id"
      @click="onOpen(d)">
      <view class="row">
        <view>
          <view class="proc-name">#{{ d.processNo }} {{ d.processName }}</view>
          <view class="muted">{{ d.workshop || '通用' }} · v{{ d.version || '1.0' }}</view>
        </view>
        <view class="proc-caret">›</view>
      </view>
    </view>

    <!-- 选中工序 → 3 tab -->
    <view v-if="current" class="card drawer">
      <view class="drawer-head row">
        <view class="proc-name">{{ current.processName }} · v{{ current.version || '1.0' }}</view>
        <view class="close-x" @click="current = null">✕</view>
      </view>

      <view class="tabs">
        <view v-for="t in ['参数', 'SOP 文档']" :key="t"
          :class="['tab', tab === t ? 'tab-active' : '']" @click="tab = t">{{ t }}</view>
      </view>

      <!-- 参数 tab -->
      <template v-if="tab === '参数'">
        <view v-if="!params.length" class="empty">该工序暂无工艺参数</view>
        <view class="param-row" v-for="p in params" :key="p.id">
          <view class="row">
            <view class="param-key">{{ p.paramKey }}<text v-if="p.isCritical === 1" class="crit">关键</text></view>
            <view class="param-val">{{ p.paramValue }}<text v-if="p.unit" class="unit"> {{ p.unit }}</text></view>
          </view>
          <view v-if="p.upperLimit != null || p.lowerLimit != null" class="muted">
            上下限 {{ p.lowerLimit ?? '-' }} ~ {{ p.upperLimit ?? '-' }}
          </view>
        </view>
      </template>

      <!-- SOP 文档 tab -->
      <template v-else>
        <view v-if="!sops.length" class="empty">该工序暂无 SOP 文档</view>
        <view class="sop-row" v-for="s in sops" :key="s.id">
          <view class="row">
            <view class="sop-title">v{{ s.version }} <text :class="['sop-st', 'st-' + s.status]">{{ s.status }}</text></view>
            <button v-if="s.status !== 'ACTIVE'" class="btn-mini" @click="onActivate(s)">激活</button>
          </view>
          <view class="muted">生效 {{ s.effectiveDate || '—' }}</view>
          <view class="sop-body" v-if="s.id === viewingId">{{ s.content || '(无内容)' }}</view>
          <button v-else class="btn-mini show" @click="onView(s)">查看内容</button>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const TYPES = [] // placeholder
const HINT = {}

const productId = ref('')
const loadingDefs = ref(false)
const defs = ref([])
const current = ref(null)
const tab = ref('参数')
const params = ref([])
const sops = ref([])
const viewingId = ref(null)

async function loadDefs() {
  loadingDefs.value = true
  try {
    const pid = productId.value ? Number(productId.value) : null
    const r = await api.mesProcessDefList(pid)
    defs.value = r || []
  } catch (e) {
    defs.value = []
    console.warn('[sop.loadDefs] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loadingDefs.value = false
  }
}

async function onOpen(d) {
  current.value = d
  tab.value = '参数'
  viewingId.value = null
  await Promise.all([loadParams(d.id), loadSops(d.id)])
}

async function loadParams(processDefId) {
  try {
    params.value = await api.pdmParamList(processDefId) || []
  } catch (e) {
    params.value = []
    console.warn('[sop.loadParams] 失败:', e)
  }
}

async function loadSops(processDefId) {
  try {
    sops.value = await api.pdmSopList(processDefId) || []
  } catch (e) {
    sops.value = []
    console.warn('[sop.loadSops] 失败:', e)
  }
}

function onView(s) { viewingId.value = s.id }

async function onActivate(s) {
  try {
    uni.showLoading({ title: '激活中' })
    await api.pdmSopActivate(s.id)
    uni.hideLoading()
    uni.showToast({ title: '已激活 ' + s.version, icon: 'success' })
    loadSops(current.value.id)
  } catch (e) {
    uni.hideLoading()
    console.warn('[sop.activate] 失败:', e)
    uni.showToast({ title: (e && e.msg) || '激活失败', icon: 'none' })
  }
}

onMounted(() => {
  if (!guardPage('pdm')) {
    uni.showToast({ title: '该模块已停用', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/dashboard/index' }), 800)
    return
  }
  loadDefs()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.search-bar { padding: 10px; }
.search-row { display: flex; gap: 6px; align-items: center; }
.input { flex: 1; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn-search { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 0 14px; height: 36px; font-size: 14px; }
.proc-card { border-left: 3px solid #1e6091; }
.row { display: flex; justify-content: space-between; align-items: center; }
.proc-name { font-weight: bold; font-size: 15px; color: #303133; }
.proc-caret { color: #c0c4cc; font-size: 20px; }
.muted { color: #909399; font-size: 12px; }
.drawer { border-left: 3px solid #e6a23c; }
.drawer-head { margin-bottom: 8px; }
.close-x { color: #c0c4cc; font-size: 18px; padding: 0 6px; }
.tabs { display: flex; gap: 6px; margin-bottom: 10px; }
.tab { flex: 1; text-align: center; padding: 8px 4px; font-size: 13px; border-radius: 6px; background: #f4f4f5; color: #606266; }
.tab-active { background: #1e6091; color: #fff; }
.param-row { padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.param-key { font-weight: 500; color: #303133; font-size: 14px; }
.crit { margin-left: 6px; font-size: 11px; color: #f56c6c; }
.param-val { font-size: 14px; color: #1e6091; }
.unit { font-size: 11px; color: #909399; }
.sop-row { padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.sop-title { font-weight: 500; font-size: 14px; }
.sop-st { margin-left: 8px; font-size: 11px; padding: 1px 6px; border-radius: 8px; }
.st-ACTIVE { background: #e1f3d8; color: #67c23a; }
.st-DRAFT { background: #faecd8; color: #e6a23c; }
.st-DEPRECATED { background: #f4f4f5; color: #909399; }
.sop-body { margin-top: 8px; padding: 10px; background: #f7f8fa; border-radius: 6px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.btn-mini { background: #f4f4f5; color: #606266; border: 1px solid #dcdfe6; border-radius: 4px; padding: 2px 10px; font-size: 12px; height: 24px; line-height: 20px; }
.btn-mini.show { background: #1e6091; color: #fff; border-color: #1e6091; }
.empty { text-align: center; color: #999; padding: 30px; font-size: 13px; }
</style>
