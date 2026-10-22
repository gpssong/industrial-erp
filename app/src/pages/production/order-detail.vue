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

        <!-- v1.1.103: 单号二维码 (扫了可定位/搜索该单, 长按或点放大) -->
        <view v-if="qrCodeUrl" class="qr-block" @click="previewQrCode">
          <image :src="qrCodeUrl" mode="aspectFit" class="qr-img" />
          <view class="qr-hint">📱 扫码查看此生产单 · 点击放大</view>
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

      <!-- v1.1.101 App XK 吹膜生产全流程 — 生产流程 6 步卡 (按状态 + 角色显隐) -->
      <view class="card flow-card">
        <view class="section-title">生产流程 (XK)</view>
        <view class="flow-steps">
          <!-- ① 开工 -->
          <view class="flow-step" :class="{ done: stepDone(1) }">
            <view class="step-no">{{ stepDone(1) ? '✓' : '①' }}</view>
            <view class="step-body">
              <text class="step-name">开工 · 吹膜</text>
              <text class="step-desc">展开领料 + 工序链, 状态转生产中</text>
              <view v-if="order.releaseName" class="release-who">👤 开工: <text class="release-val">{{ order.releaseName }}</text></view>
              <button v-if="canRelease()" class="step-btn" @click="onRelease">开工</button>
            </view>
          </view>
          <!-- ② 首检/巡检 -->
          <view class="flow-step">
            <view class="step-no">②</view>
            <view class="step-body">
              <text class="step-name">首检 / 巡检 · 检验员</text>
              <text class="step-desc">逐检验项打分 + 拍照, 现场签合格放行</text>
              <view v-if="canInspect()" class="step-btns">
                <button class="step-btn small" @click="goScan('/pages/scan/fai')">做首检</button>
                <button class="step-btn small" @click="goScan('/pages/scan/pat')">做巡检</button>
              </view>
            </view>
          </view>
          <!-- ③ 半成品入库 -->
          <view class="flow-step">
            <view class="step-no">③</view>
            <view class="step-body">
              <text class="step-name">半成品入库 · 仓管</text>
              <text class="step-desc">FAI 首检合格后入半成品仓</text>
              <button v-if="canSemiIn()" class="step-btn" @click="goScan('/pages/scan/semi-in')">建半成品入库</button>
            </view>
          </view>
          <!-- ④ 制袋工序报工 -->
          <view class="flow-step">
            <view class="step-no">④</view>
            <view class="step-body">
              <text class="step-name">制袋 · 报工完工</text>
              <text class="step-desc">制袋工序扫码报工, 累计良/不良</text>
              <button v-if="canReport()" class="step-btn" @click="goScan('/pages/production/report')">工序报工</button>
            </view>
          </view>
          <!-- ⑤ 成品检验 -->
          <view class="flow-step">
            <view class="step-no">⑤</view>
            <view class="step-body">
              <text class="step-name">成品检验 OQC · 检验员</text>
              <text class="step-desc">成品检打分 + 拍照, 现场签合格</text>
              <button v-if="canInspect()" class="step-btn" @click="goScanOqc()">做成品检</button>
            </view>
          </view>
          <!-- ⑥ 成品入库 -->
          <view class="flow-step">
            <view class="step-no">⑥</view>
            <view class="step-body">
              <text class="step-name">成品入库 · 仓管</text>
              <text class="step-desc">工序全完工 + OQC 放行后入成品仓</text>
              <button v-if="canFinishedIn()" class="step-btn" @click="goScan('/pages/scan/finished-in')">建成品入库</button>
            </view>
          </view>
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
import { registerPlugin } from '@capacitor/core'
// v1.1.29+: 自定义原生分享 plugin (绕开 Capacitor Share 6 + FileProvider 路径兼容问题)
const NativeShare = registerPlugin('NativeShare')
// v1.1.103: QR 码生成 (扫了可直接定位/搜索该生产单)
import QRCode from 'qrcode'
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
// v1.1.101: XK 生产流程 6 步 — 角色/perm 可见性
const p = ref([])
// v1.1.103: 单号二维码 (QR 码内容 = billNo, 扫码能定位/搜索该单)
const qrCodeUrl = ref('')

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
      // v1.1.103: 生成单号二维码 (扫了能定位/搜索该单)
      genQrCode(r.billNo)
    } else {
      order.value = null
      requisitionDetails.value = []
      qrCodeUrl.value = ''
    }
    p.value = getPermissions()
    checkPerms()
  } catch (e) {
    order.value = null
    loadError.value = true
    qrCodeUrl.value = ''
    // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg, catch 不重复弹
    console.warn('[order-detail.loadOrder] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
}

// v1.1.103: 生成单号 QR 码 (dataURL, 160px, H5/App 通用)
// 内容: 单号 billNo (扫码能在搜索框直接定位该单, 简单实用)
async function genQrCode(billNo) {
  if (!billNo) { qrCodeUrl.value = ''; return }
  try {
    const dataUrl = await QRCode.toDataURL(String(billNo), {
      width: 160,
      margin: 1,
      errorCorrectionLevel: 'M',  // M 适合短文本, 抗污损中等
      color: { dark: '#1e6091', light: '#ffffff' }  // 主色蓝, 贴合 UI
    })
    qrCodeUrl.value = dataUrl
  } catch (err) {
    console.warn('[order-detail.genQrCode] 失败:', err)
    qrCodeUrl.value = ''
  }
}

// v1.1.103: 点击 QR 码放大预览 (H5 走 uni.previewImage, App 走原生预览)
function previewQrCode() {
  if (!qrCodeUrl.value) return
  if (typeof uni !== 'undefined' && uni.previewImage) {
    uni.previewImage({ urls: [qrCodeUrl.value], current: qrCodeUrl.value, indicator: 'number' })
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
    // v1.1.110: 必须传 orderId.value, 否则 loadOrder(id) 内 `if (!id)` 命中 → 清空 order → 显示「生产单不存在」
    loadOrder(orderId.value)
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
    // v1.1.61 R13: 拦截器已弹后端 msg, catch 不重复弹. 没 showLoading, 不调 hideLoading
    console.warn('[order-detail.onDelete] 失败:', e)
    if (e && e.code === 401) return
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
    // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg (如"打印机离线"), catch 不重复弹
    console.warn('[order-detail.onFeiePrint] 失败:', e)
    if (e && e.code === 401) return
  }
}

// v1.1.11+: 分享生产单
// v1.1.29+: 改用自定义 NativeSharePlugin — 直接传 PDF URL, 原生 plugin 用 HttpURLConnection 下载到
//             Context.getFilesDir()/share-<ts>.pdf, 然后 FileProvider 转 content:// URI 分享.
//             完全绕开 uni.saveFile / plus.io 返回 webview blob 路径的坑
async function onShare() {
  if (!orderId.value) {
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '生产单未加载', icon: 'none' })
    return
  }
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '生成 PDF...', mask: true })
  try {
    const url = api.prdOrderPdfUrl(String(orderId.value))
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    // v1.1.29+: NativeShare 自下载 PDF + 启 share sheet, JS 端无需处理文件路径
    await NativeShare.sharePdf({
      title: '生产单 ' + (order.value && order.value.billNo ? order.value.billNo : ''),
      text: (order.value && order.value.productName ? order.value.productName : '') + ' 生产加工单',
      url: url
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

// ============ v1.1.101 XK 生产流程 6 步 — 显隐 + 跳转 ============
function hasPerm(code) { return isAdmin() || p.value.includes(code) }
function stepDone(n) {
  const s = order.value && order.value.billStatus
  // ① 开工 = 已 RELEASED 及以后
  return n === 1 ? !!s && s !== 'DRAFT' : false
}
function canRelease() {
  return hasPerm('production:order:release') && order.value && order.value.billStatus === 'DRAFT'
}
function canInspect() {
  return hasPerm('quality:inspection:check') && order.value && ['RELEASED', 'PRODUCING'].includes(order.value.billStatus)
}
function canSemiIn() {
  return hasPerm('production:semi-in:add') && order.value && order.value.billStatus !== 'DRAFT'
}
function canReport() {
  return hasPerm('production:report:add') && order.value && ['RELEASED', 'PRODUCING'].includes(order.value.billStatus)
}
function canFinishedIn() {
  return hasPerm('production:finished-in:add') && order.value && order.value.billStatus !== 'DRAFT'
}
function goScan(path) {
  navigateTo(path + '?orderId=' + encodeURIComponent(orderId.value))
}
function goScanOqc() {
  // 成品检 = OQC, 复用巡检页做成品检打分草稿; 深度签放行走 finished-in 门禁
  navigateTo('/pages/scan/pat?orderId=' + encodeURIComponent(orderId.value))
}

onMounted(() => { applyTabBar() })
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 32px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.bill-no { font-weight: bold; font-size: 17px; color: #303133; }
.muted { color: #999; font-size: 12px; }
.product-line { margin-top: 8px; font-size: 14px; color: #303133; }
.product-name { font-weight: 500; }
.qty { color: #1e6091; font-weight: bold; font-size: 16px; margin-left: 4px; }
.bom-line { margin-top: 8px; font-size: 13px; }
.bom-name { color: #67c23a; font-weight: 500; }
/* v1.1.103: 单号二维码块 (居中, 160px, 蓝主题) */
.qr-block { display: flex; flex-direction: column; align-items: center; margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f0f0; }
.qr-img { width: 160px; height: 160px; background: #fff; padding: 4px; border: 1px solid #eee; border-radius: 6px; }
.qr-hint { font-size: 11px; color: #909399; margin-top: 6px; }
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
/* v1.1.101 XK 生产流程 6 步卡 */
.flow-card { background: #fff; }
.flow-steps { display: flex; flex-direction: column; gap: 10px; }
.flow-step { display: flex; gap: 10px; padding: 10px; border: 1px solid #ebeef5; border-radius: 8px; }
.flow-step.done { border-color: #67c23a; background: #f0f9eb; }
.step-no { width: 26px; height: 26px; flex: none; border-radius: 50%; background: #1e6091; color: #fff; font-size: 13px; display: flex; align-items: center; justify-content: center; font-weight: 600; }
.flow-step.done .step-no { background: #67c23a; }
.step-body { flex: 1; }
.step-name { font-size: 14px; font-weight: 500; color: #303133; display: block; }
.step-desc { font-size: 11px; color: #909399; display: block; margin: 2px 0 6px; }
.step-btn { margin-top: 4px; background: #1e6091; color: #fff; border: none; border-radius: 6px; height: 32px; line-height: 32px; font-size: 13px; padding: 0 14px; }
.step-btn:active { background: #2980b9; }
.step-btn.small { display: inline-block; margin-right: 6px; background: #ecf5fb; color: #1e6091; }
.step-btns { display: flex; flex-wrap: wrap; gap: 4px; }
.release-who { font-size: 12px; color: #606266; margin-top: 4px; }
.release-val { font-weight: 600; color: #1e6091; }
</style>
