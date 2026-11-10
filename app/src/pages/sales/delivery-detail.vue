<template>
  <view class="container">
    <view v-if="loading" class="empty">加载中...</view>
    <view v-else-if="loadError" class="empty">加载失败</view>
    <view v-else-if="!order || !order.id" class="empty">出库单不存在</view>

    <template v-else>
      <!-- 顶部主表卡 -->
      <view class="card">
        <view class="row">
          <view style="flex:1;min-width:0">
            <!-- v1.1.131: 单号后带商品名称 (取明细行商品名, 多行去重) -->
            <view class="bill-no">{{ order.billNo }}<text v-if="productNames"> · {{ productNames }}</text></view>
            <view class="muted">{{ order.billDate }}</view>
          </view>
          <view :class="['tag', 'tag-' + (order.billStatus || 'DRAFT').toLowerCase()]">
            {{ statusTag(order.billStatus) }}
          </view>
        </view>
        <view class="info-grid">
          <view class="info-item">
            <text class="info-label">客户</text>
            <text class="info-value">{{ order.customerName || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">仓库</text>
            <text class="info-value">{{ order.warehouseName || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">总数量</text>
            <text class="info-value">{{ formatNum(order.totalQty) }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">总金额</text>
            <text class="info-value">¥ {{ formatMoney(order.totalAmount) }}</text>
          </view>
          <view v-if="order.totalAmountTax != null" class="info-item">
            <text class="info-label">含税金额</text>
            <text class="info-value">¥ {{ formatMoney(order.totalAmountTax) }}</text>
          </view>
          <view v-if="order.address" class="info-item">
            <text class="info-label">收货地址</text>
            <text class="info-value">{{ order.address }}</text>
          </view>
        </view>
      </view>

      <!-- 商品明细 -->
      <view class="card">
        <view class="section-title">商品明细 ({{ order.details ? order.details.length : 0 }})</view>
        <view v-if="!order.details || !order.details.length" class="muted" style="padding: 16px 0">
          暂无明细
        </view>
        <view v-for="(d, i) in order.details || []" :key="i" class="detail-row">
          <view class="detail-line-1">
            <text class="detail-line-no">{{ d.lineNo || (i + 1) }}</text>
            <text class="detail-name">{{ d.productName || '—' }}</text>
          </view>
          <view class="detail-line-2 muted">
            <text v-if="d.productCode">{{ d.productCode }} </text>
            <text v-if="d.spec">{{ d.spec }} </text>
            <text v-if="d.model">{{ d.model }} </text>
            <text v-if="d.colorNo">{{ d.colorNo }} </text>
            <text v-if="d.unitName">{{ d.unitName }}</text>
          </view>
          <view class="detail-line-3">
            <text class="muted">× {{ formatNum(d.qty) }}</text>
            <text v-if="d.price" class="muted"> × ¥ {{ formatMoney(d.price) }}</text>
            <text class="detail-amount">¥ {{ formatMoney(d.amount) }}</text>
          </view>
          <view v-if="d.batchNo || d.locationName" class="detail-line-3 muted" style="font-size:11px">
            <text v-if="d.batchNo">批次: {{ d.batchNo }} </text>
            <text v-if="d.locationName">库位: {{ d.locationName }}</text>
          </view>
        </view>
      </view>

      <!-- 备注 -->
      <view v-if="order.remark" class="card">
        <view class="section-title">备注</view>
        <view class="remark">{{ order.remark }}</view>
      </view>

      <!-- 审核/反审核按钮 (v1.1.55+ 拆分 perm, DRAFT 看 :check, CHECKED 看 :uncheck)
           v1.1.58+ 用户反馈: 完全隐藏无 perm 时的按钮区, 不要显示"无权限"提示.
           之前 v-if='!canCheck/!canUncheck' 还渲染提示文字, 体验割裂. 现在改为:
           - 任一 perm (check 或 uncheck) 有 → 显示按钮区
           - 都没 → 整张 card 不渲染 (用户看不到任何痕迹)
           v1.1.59 R11 hotfix: v-if 里 setup 函数必须显式调用 `()` — 函数引用永远 truthy, 编译为 D/A 引用而非 D()/A() 调用. -->
      <view v-if="(order.billStatus==='DRAFT' && canCheck()) || (order.billStatus==='CHECKED' && canUncheck())"
            class="card action-card">
        <!-- DRAFT 状态: 仅审核按钮 (要 :check perm) -->
        <template v-if="order.billStatus==='DRAFT'">
          <view class="row" style="gap:8px">
            <button class="btn-action btn-check"
              :disabled="busy" @click="onAudit('check')">审核</button>
          </view>
        </template>
        <!-- CHECKED 状态: 仅反审核按钮 (要 :uncheck perm) -->
        <template v-else-if="order.billStatus==='CHECKED'">
          <view class="row" style="gap:8px">
            <button class="btn-action btn-uncheck"
              :disabled="busy" @click="onAudit('uncheck')">反审核</button>
          </view>
        </template>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { getAppPermissions, isAdmin } from '../../utils/permission.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const order = ref({})
// v1.1.131: 明细行商品名去重后拼给单号用 (单据头没有品名列)
const productNames = computed(() => {
  const names = ((order.value && order.value.details) || []).map(d => d.productName).filter(Boolean)
  return [...new Set(names)].join('、')
})
const loadError = ref(false)
const loading = ref(true)
const busy = ref(false)

// v1.1.55+: 拆分为 check / uncheck 两个独立 perm
//   - 审核   → sales:delivery:check
//   - 反审核 → sales:delivery:uncheck (sql/35 seed 新增, 老板/主管专属)
// 反审核是更高级操作 (回退库存/AR), 业务上不应和审核混在一起
//
// v1.1.55 hotfix 2026-09-21 晚: 必须用 getAppPermissions() 而非 getPermissions()
//   后端 selectPermsByUserId 不分端 (PC+APP 混合), 仓管员 (WAREHOUSE_MGR) PC 端有 uncheck
//   但 APP 端无 uncheck — 用混合数组会让 App 按钮误显示.
//   getAppPermissions() 从 erp_menus (按 APP 严格过滤) 派生, 才是 App 端正确判断依据.
const CHECK_PERM = 'sales:delivery:check'
const UNCHECK_PERM = 'sales:delivery:uncheck'
function canCheck() {
  if (isAdmin()) return true
  return getAppPermissions().includes(CHECK_PERM)
}
function canUncheck() {
  if (isAdmin()) return true
  return getAppPermissions().includes(UNCHECK_PERM)
}

function statusTag(s) {
  const map = { DRAFT: '草稿', CHECKED: '已审核' }
  return map[s] || s || '—'
}
function formatNum(v) { return v == null ? '—' : Number(v).toLocaleString() }
function formatMoney(v) {
  if (v == null) return '—'
  return Number(v).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

// v1.1.54+: 二次确认 + 调后端 /{id}/check 或 /{id}/uncheck, 与 PC 端 onCheck/onUncheck 行为对齐
// v1.1.174: 审核走「自动批次分配」路径, 与 PC 端 autoAllocateAll 口径一致 (savedBatches 优先 → 其余按库存 qty DESC 补齐, 跨行去重).
// 库存不足时弹提示阻止审核; 分配成功则把 {details:[{detailId,allocations:[{batchNo,qty}]}]} 发给后端.
async function buildAutoAllocations() {
  const wh = order.value.warehouseId
  const details = order.value.details || []
  const stockCache = {}
  const used = {}
  const lines = []
  let anyShort = false
  for (const det of details) {
    const pid = det.productId
    if (!(pid in stockCache)) {
      try {
        const r = await api.stockBatches(wh, pid)
        stockCache[pid] = (r && (r.data || r)) || []
      } catch (e) { stockCache[pid] = [] }
    }
    const saved = det.batchNo ? String(det.batchNo).split('|').filter(x => x !== '') : []
    const need = Number(det.qty || 0)
    const opts = (stockCache[pid] || [])
      .filter(b => Number(b.qty) > 0 || saved.includes(b.batchNo || ''))
    // 与 PC 端 fillLine 一致: saved 优先 (按 saved 顺序), 其余按接口返回顺序 (qty DESC)
    opts.sort((a, b) => {
      const ai = saved.indexOf(a.batchNo || '')
      const bi = saved.indexOf(b.batchNo || '')
      return (ai < 0 ? 9999 : ai) - (bi < 0 ? 9999 : bi)
    })
    let remaining = need
    const allocations = []
    for (const o of opts) {
      if (remaining <= 0) break
      const bn = o.batchNo || ''
      const k = pid + '|' + bn
      const left = +(((Number(o.qty) || 0) - (used[k] || 0))).toFixed(4)
      if (left <= 0) continue
      const take = +Math.min(remaining, left).toFixed(4)
      used[k] = +(((used[k] || 0) + take)).toFixed(4)
      remaining = +(remaining - take).toFixed(4)
      allocations.push({ batchNo: bn, qty: take })
    }
    if (remaining > 0.0001) anyShort = true
    lines.push({
      detailId: det.id,
      productName: det.productName || det.productCode || String(pid),
      unitName: det.unitName || '',
      need,
      allocations,
      shortage: +remaining.toFixed(4)
    })
  }
  return { lines, anyShort }
}

function formatAllocationSummary(lines) {
  return lines.map(l => {
    const allocs = l.allocations.filter(a => a.qty > 0)
    if (!allocs.length) return `· ${l.productName}: ${l.need}${l.unitName} (无批次, 按库存扣)`
    const parts = allocs.map(a => `${a.batchNo || '无批次'} ${a.qty}${l.unitName}`).join(', ')
    return `· ${l.productName}: ${l.need}${l.unitName} → ${parts}`
  }).join('\n')
}

function formatShortage(lines) {
  return lines
    .filter(l => l.shortage > 0)
    .map(l => `· ${l.productName}: 缺 ${l.shortage}${l.unitName}`)
    .join('\n')
}

function onAudit(action) {
  if (busy.value) return
  const isUncheck = action === 'uncheck'
  if (isUncheck) {
    // 反审核保持原路径 (无需批次分配)
    uni.showModal({
      title: '反审核确认',
      content: `确认反审核销售出库单 ${order.value.billNo}?\n\n此操作仅回退单据状态至「草稿」, 不会自动回退已扣减的库存或应收账款. 如需调整, 请走后继红冲单.`,
      confirmText: '确认反审核',
      cancelText: '取消',
      confirmColor: '#e6a23c',
      success: async (res) => {
        if (!res.confirm) return
        busy.value = true
        try {
          await api.salesDeliveryUncheck(order.value.id)
          uni.showToast({ title: '反审核成功', icon: 'success' })
          await reload()
        } catch (e) {
          console.warn('[sales-delivery-detail.onAudit uncheck] 失败:', e)
          if (e && e.code === 401) return
        } finally { busy.value = false }
      }
    })
    return
  }
  // 审核: 先算自动批次分配
  busy.value = true
  uni.showLoading({ title: '匹配批次...', mask: true })
  buildAutoAllocations().then(({ lines, anyShort }) => {
    uni.hideLoading()
    busy.value = false
    if (anyShort) {
      uni.showModal({
        title: '库存不足',
        content: '以下商品可用库存不足以完成本次出库, 请先入库或调整单据数量:\n\n' + formatShortage(lines),
        showCancel: false,
        confirmText: '知道了'
      })
      return
    }
    uni.showModal({
      title: '审核确认',
      content: `确认审核销售出库单 ${order.value.billNo}?\n\n已按库存自动匹配批次:\n${formatAllocationSummary(lines)}\n\n审核后将扣减库存 / 更新成本 / 生成应收.`,
      confirmText: '确认审核',
      cancelText: '取消',
      confirmColor: '#67c23a',
      success: async (res) => {
        if (!res.confirm) return
        busy.value = true
        try {
          const payload = {
            details: lines.map(l => ({
              detailId: l.detailId,
              allocations: l.allocations.filter(a => a.qty > 0).map(a => ({ batchNo: a.batchNo || '', qty: a.qty }))
            }))
          }
          await api.salesDeliveryCheck(order.value.id, payload)
          uni.showToast({ title: '审核成功', icon: 'success' })
          await reload()
        } catch (e) {
          console.warn('[sales-delivery-detail.onAudit check] 失败:', e)
          if (e && e.code === 401) return
        } finally { busy.value = false }
      }
    })
  }).catch((e) => {
    uni.hideLoading()
    busy.value = false
    console.warn('[sales-delivery-detail.buildAutoAllocations] 失败:', e)
    uni.showModal({ title: '批次匹配失败', content: (e && (e.msg || e.message)) || '加载批次库存失败, 请稍后重试', showCancel: false })
  })
}

async function reload() {
  const id = order.value.id
  loading.value = true
  try {
    const r = await api.salesDeliveryDetail(id)
    order.value = (r && (r.data || r)) || {}
    loadError.value = false
  } catch (e) {
    loadError.value = true
    // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg, catch 不重复弹
    console.warn('[sales-delivery-detail.reload] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
}

onLoad(async (q) => {
  // v1.1.80+: 模块停用守卫
  if (!guardPage('sales')) return
  loading.value = true
  try {
    const id = q && q.id
    if (!id) throw new Error('缺少单据 ID')
    const r = await api.salesDeliveryDetail(id)
    order.value = (r && (r.data || r)) || {}
    loadError.value = false
  } catch (e) {
    loadError.value = true
    // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg, catch 不重复弹
    console.warn('[sales-delivery-detail.onLoad] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.bill-no { font-weight: bold; font-size: 15px; color: #303133; word-break: break-all; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.info-grid { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.info-item { display: flex; gap: 8px; font-size: 13px; }
.info-label { color: #999; min-width: 64px; flex-shrink: 0; }
.info-value { color: #303133; flex: 1; word-break: break-all; }
.muted { color: #999; font-size: 12px; }
.tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.tag-draft { background: #f4f4f5; color: #909399; }
.tag-checked { background: #d9ecff; color: #1890ff; }
.detail-row { padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.detail-row:last-child { border-bottom: none; }
.detail-line-1 { display: flex; gap: 8px; align-items: center; }
.detail-line-no { color: #999; font-size: 12px; min-width: 24px; }
.detail-name { color: #303133; font-size: 14px; font-weight: 500; flex: 1; }
.detail-line-2 { margin-top: 4px; }
.detail-line-3 { margin-top: 6px; display: flex; gap: 8px; justify-content: space-between; align-items: center; }
.detail-amount { color: #1e6091; font-weight: bold; font-size: 14px; }
.remark { color: #303133; font-size: 14px; line-height: 1.6; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }

/* v1.1.54+: 审核/反审核按钮 */
.action-card { padding: 12px; }
.btn-action { flex: 1; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; }
.btn-check { background: #67c23a; }
.btn-uncheck { background: #e6a23c; }
.btn-action:disabled { opacity: 0.5; }
</style>