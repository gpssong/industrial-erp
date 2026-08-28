<template>
  <view class="container">
    <view v-if="loading" class="empty">加载中...</view>
    <view v-else-if="loadError" class="empty">加载失败</view>
    <view v-else-if="!order || !order.id" class="empty">生产单不存在</view>

    <template v-else>
      <!-- 顶部产品卡 -->
      <view class="card">
        <view class="row">
          <view style="flex:1">
            <view class="bill-no">{{ order.billNo }}</view>
            <view class="muted">{{ order.billDate }}</view>
          </view>
          <view :class="['tag', 'tag-' + (order.billStatus || 'DRAFT').toLowerCase()]">
            {{ statusTag(order.billStatus) }}
          </view>
        </view>
        <view class="product-line">
          <text class="product-name">{{ order.productName || '—' }}</text>
          <text v-if="order.spec" class="muted"> ({{ order.spec }})</text>
          <text class="qty"> × {{ order.planQty || 0 }} {{ order.unitName || '' }}</text>
        </view>

        <!-- BOM 名 (详情才有) -->
        <view v-if="order.bomName || order.bomCode" class="bom-line">
          <text class="muted">配方: </text>
          <text class="bom-name">{{ order.bomName || '—' }}</text>
          <text v-if="order.bomCode" class="muted"> ({{ order.bomCode }})</text>
        </view>

        <!-- 商品规格网格 -->
        <view class="info-grid">
          <view class="info-item">
            <text class="info-label">色号</text>
            <text class="info-value">{{ order.colorNo || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">长度(mm)</text>
            <text class="info-value">{{ order.thickness || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">宽度(mm)</text>
            <text class="info-value">{{ order.width || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">厚度</text>
            <text class="info-value">{{ order.density || '-' }}</text>
          </view>
          <view class="info-item">
            <text class="info-label">克重</text>
            <text class="info-value">{{ order.gramWeight || '-' }}g</text>
          </view>
          <view class="info-item">
            <text class="info-label">材质</text>
            <text class="info-value">{{ order.material || '-' }}</text>
          </view>
        </view>
      </view>

      <!-- 安排卡 -->
      <view class="card">
        <view class="section-title">生产安排</view>
        <view class="kv"><text class="k">车间</text><text class="v">{{ order.workshop || '-' }}</text></view>
        <view class="kv"><text class="k">负责人</text><text class="v">{{ order.leader || '-' }}</text></view>
        <view class="kv"><text class="k">计划损耗</text><text class="v">{{ order.lossRate || 0 }}%</text></view>
        <view class="kv"><text class="k">开工日期</text><text class="v">{{ order.startDate || '-' }}</text></view>
        <view class="kv"><text class="k">完工日期</text><text class="v">{{ order.endDate || '-' }}</text></view>
      </view>

      <!-- 实际数据卡 (RELEASED/FINISHED 后才有) -->
      <view v-if="order.actualQty || order.goodQty || order.lossQty" class="card">
        <view class="section-title">实际数据</view>
        <view class="kv"><text class="k">实际数量</text><text class="v">{{ order.actualQty || 0 }}</text></view>
        <view class="kv"><text class="k">良品数</text><text class="v">{{ order.goodQty || 0 }}</text></view>
        <view class="kv"><text class="k">损耗数</text><text class="v">{{ order.lossQty || 0 }}</text></view>
      </view>

      <!-- 领料明细 (如已开工) -->
      <view v-if="requisitionDetails && requisitionDetails.length" class="card">
        <view class="section-title">领料明细</view>
        <view v-for="(d, i) in requisitionDetails" :key="i" class="req-row">
          <text style="flex:1">{{ d.productName || '—' }} <text v-if="d.productCode" class="muted">({{ d.productCode }})</text></text>
          <text class="req-qty">{{ d.qty || 0 }} {{ d.unitName || '' }}</text>
        </view>
      </view>

      <!-- 备注 -->
      <view v-if="order.remark" class="card">
        <view class="section-title">备注</view>
        <view class="remark">{{ order.remark }}</view>
      </view>

      <!-- 操作区 (DRAFT 才有) -->
      <view v-if="editable || releasable || deletable" class="card action-card">
        <button v-if="editable" class="btn btn-primary" @click="onEdit">编辑生产单</button>
        <button v-if="releasable" class="btn btn-success" @click="onRelease">开工</button>
        <button v-if="deletable" class="btn btn-danger" @click="onDelete">删除</button>
      </view>

      <!-- 飞鹅云打印 (有 perm) -->
      <view v-if="hasFeiePrintPerm()" class="card">
        <button class="btn btn-feie" @click="onFeiePrint">🖨 飞鹅云打印</button>
      </view>

      <!-- v1.1.11+: 分享生产单 (PDF 下载后调原生分享菜单) -->
      <view v-if="canShare()" class="card">
        <button class="btn btn-share" @click="onShare">📤 分享生产单</button>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { Share } from '@capacitor/share'
import api from '../../api/index.js'
import { applyTabBar, isAdmin, getPermissions } from '../../utils/permission.js'
import { navigateTo } from '../../utils/nav.js'

const STATUS_MAP = {
  DRAFT: '草稿', RELEASED: '已开工', PRODUCING: '生产中',
  FINISHED: '已完成', CLOSED: '已关闭'
}

const order = ref(null)  // 初始 null, 加载中/未加载时不会触发模板 null 异常
const loadError = ref(false)
const requisitionDetails = ref([])
const loading = ref(false)
const editable = ref(false)
const releasable = ref(false)
const deletable = ref(false)
const orderId = ref(null)  // v1.1.11+ 修: ref 替代 let, 避免 reLaunch 之间模块级 let 缓存

function statusTag(s) { return STATUS_MAP[s] || s || '—' }

function checkPerms() {
  if (!order.value) {
    editable.value = releasable.value = deletable.value = false
    return
  }
  const isDraft = order.value.billStatus === 'DRAFT'
  if (!isDraft) {
    editable.value = releasable.value = deletable.value = false
    return
  }
  const perms = getPermissions()
  const admin = isAdmin()
  editable.value = admin || perms.includes('production:order:edit')
  releasable.value = admin || perms.includes('production:order:release')
  deletable.value = admin || perms.includes('production:order:delete')
}

function hasFeiePrintPerm() {
  const perms = getPermissions()
  return isAdmin() || perms.includes('production:order:feie-print')
}

// v1.1.11+: 分享按钮 perm — 跟详情页本身权限一致 (有 list 就能看能分享)
function canShare() {
  const perms = getPermissions()
  return isAdmin() || perms.includes('production:order:list')
}

async function loadOrder(id) {
  if (!id) {
    order.value = null
    loading.value = false
    return
  }
  loading.value = true
  try {
    // v1.1.11+: 用 String 避免 JS Number 精度丢失 (后端 Long > 2^53)
    const r = await api.prdOrderDetail(String(id))
    if (r && typeof r === 'object' && r.id) {
      order.value = r
      requisitionDetails.value = (r.requisitionDetails) || []
    } else {
      order.value = null
      requisitionDetails.value = []
    }
    checkPerms()
  } catch (e) {
    order.value = null
    loadError.value = true
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: (e && e.msg) || (e && e.message) || '加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
}

function askConfirm(content) {
  return new Promise((resolve) => {
    if (typeof uni === 'undefined' || !uni.showModal) { resolve(true); return }
    uni.showModal({ title: '提示', content, success: (res) => resolve(res.confirm), fail: () => resolve(false) })
  })
}

function onEdit() {
  navigateTo('/pages/production/order-add?id=' + orderId.value)
}

async function onRelease() {
  const ok = await askConfirm('开工后将自动展开领料单, 确定开工?')
  if (!ok) return
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '开工中...', mask: true })
  try {
    await api.prdOrderRelease(orderId.value)
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '开工成功', icon: 'success' })
    loadOrder()
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    if (typeof uni !== 'undefined' && uni.showModal) {
      uni.showModal({ title: '开工失败', content: e.message || '请检查BOM配置', showCancel: false })
    }
  }
}

async function onDelete() {
  const ok = await askConfirm('确定删除该生产单? 此操作不可恢复')
  if (!ok) return
  try {
    await api.prdOrderDelete(orderId.value)
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '已删除', icon: 'success' })
    setTimeout(() => navigateTo('/pages/production/order-list'), 800)
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: e.message || '删除失败', icon: 'none' })
  }
}

async function onFeiePrint() {
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '正在发送打印...', mask: true })
  try {
    await api.feiePrint('PRD_ORDER', orderId.value)
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '打印已发送', icon: 'success' })
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: e.message || '打印失败', icon: 'none' })
  }
}

// v1.1.11+: 分享生产单 (PDF 下载 → uni.share 调原生菜单)
async function onShare() {
  if (!orderId.value) {
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '生产单未加载', icon: 'none' })
    return
  }
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '生成 PDF...', mask: true })
  try {
    const url = api.prdOrderPdfUrl(String(orderId.value))
    // 1. 下载 PDF (uni.downloadFile 在 App-Plus 平台返回本地临时文件路径)
    const d = await new Promise((resolve, reject) => {
      if (typeof uni === 'undefined' || !uni.downloadFile) {
        reject(new Error('当前环境不支持分享'))
        return
      }
      uni.downloadFile({
        url,
        success: (res) => resolve(res),
        fail: (err) => reject(new Error(err && err.errMsg ? err.errMsg : '下载失败'))
      })
    })
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    if (!d || d.statusCode !== 200) {
      throw new Error('下载失败: HTTP ' + (d && d.statusCode))
    }
    // 2. Capacitor Share 调原生菜单 (v1.1.11+ 修 uni.share 不支持问题)
    await Share.share({
      title: '生产单 ' + (order.value && order.value.billNo ? order.value.billNo : ''),
      text: (order.value && order.value.productName ? order.value.productName : '') + ' 生产加工单',
      url: d.tempFilePath,  // file://... (Capacitor 自动 FileProvider 转 content://)
      dialogTitle: '分享生产单 PDF'
    })
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '已调起分享', icon: 'success' })
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    const msg = (e && (e.message || e.errMsg)) ? (e.message || e.errMsg) : '分享失败'
    // 用户主动取消不弹错
    if (msg.indexOf('取消') < 0 && msg.indexOf('cancel') < 0 && msg.indexOf('Abort') < 0 && typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: msg, icon: 'none' })
    }
  }
}

onLoad((options) => {
  // v1.1.11+: id 是 JS Number 会丢精度 (Long > 2^53), 保留原始字符串传给后端
  // v1.1.11+ 修: onLoad 直接调 loadOrder(id), 不靠 onMounted, 避免 module-scope let 跨 reLaunch 缓存
  if (options && options.id) {
    orderId.value = String(options.id)
    loadOrder(orderId.value)
  }
})
onMounted(() => { applyTabBar() })
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 32px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.row { display: flex; justify-content: space-between; align-items: center; }
.bill-no { font-weight: bold; font-size: 17px; color: #303133; }
.muted { color: #999; font-size: 12px; }
.product-line { margin-top: 8px; font-size: 14px; color: #303133; }
.product-name { font-weight: 500; }
.qty { color: #1e6091; font-weight: bold; font-size: 16px; margin-left: 4px; }
.bom-line { margin-top: 8px; font-size: 13px; }
.bom-name { color: #67c23a; font-weight: 500; }
.info-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f0f0; }
.info-item { display: flex; flex-direction: column; }
.info-label { font-size: 11px; color: #999; }
.info-value { font-size: 13px; color: #303133; margin-top: 2px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.kv { display: flex; justify-content: space-between; padding: 6px 0; font-size: 14px; }
.k { color: #909399; }
.v { color: #303133; }
.req-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; border-top: 1px dashed #eee; }
.req-row:first-of-type { border-top: none; }
.req-qty { color: #1e6091; font-weight: 500; }
.remark { font-size: 14px; color: #606266; line-height: 1.6; white-space: pre-wrap; }
.action-card { display: flex; flex-direction: column; gap: 8px; }
.btn { display: block; width: 100%; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; cursor: pointer; }
.btn-primary { background: #1e6091; color: #fff; }
.btn-primary:active { background: #2980b9; }
.btn-success { background: #67c23a; color: #fff; }
.btn-success:active { background: #5daf34; }
.btn-danger { background: #f56c6c; color: #fff; }
.btn-danger:active { background: #dd6161; }
.btn-feie { background: #e6a23c; color: #fff; }
.btn-feie:active { background: #cf9236; }
.btn-share { background: #409eff; color: #fff; }
.btn-share:active { background: #3389e0; }
.tag { padding: 3px 12px; border-radius: 12px; font-size: 12px; }
.tag-draft { background: #f4f4f5; color: #909399; }
.tag-released { background: #e1f3d8; color: #67c23a; }
.tag-producing { background: #faecd8; color: #e6a23c; }
.tag-finished { background: #d9ecff; color: #1890ff; }
.tag-closed { background: #f4f4f5; color: #909399; }
.empty { text-align: center; color: #999; padding: 60px 20px; font-size: 14px; }
</style>
