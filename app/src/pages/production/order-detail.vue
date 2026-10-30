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
            <!-- v1.1.131: 单号后直接带商品名称 -->
            <view class="bill-no">{{ order.billNo }} · {{ order.productName || '—' }}</view>
            <view class="muted">{{ order.billDate }}</view>
          </view>
          <view :class="['tag', 'tag-' + (order.billStatus || 'DRAFT').toLowerCase()]">
            {{ statusTag(order.billStatus) }}
          </view>
        </view>
        <view class="product-line">
          <text v-if="order.spec" class="muted">({{ order.spec }})</text>
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
            <text class="info-label">克重 (g/个)</text>
            <text class="info-value">{{ order.gramWeight || '-' }}</text>
          </view>
          <!-- v1.1.133 总重量 = 计划数量 × 克重 ÷ 1000 (kg), 开工展开的领料数量按它分摊 -->
          <view class="info-item">
            <text class="info-label">总重量</text>
            <text class="info-value">{{ totalWeightKg }}</text>
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
        <view v-if="reqTotalKg" class="req-row">
          <text style="flex:1" class="muted">领料合计 (按 kg)</text>
          <text class="req-qty">{{ reqTotalKg }} · 应等于总重量</text>
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
              <text class="step-desc">确认生产环境合格 → 录原料批号并拍照 → 展开领料 + 工序链</text>
              <view v-if="order.releaseName" class="release-who">👤 开工: <text class="release-val">{{ order.releaseName }}</text></view>
              <view v-if="blowConfirm" class="start-facts">
                <text class="start-fact">原料批号: {{ blowConfirm.rawBatchNos || '-' }}</text>
                <text class="start-fact">批号照片: {{ parsePhotos(blowConfirm.photoUrls).length }} 张</text>
              </view>
              <button v-if="canRelease()" class="step-btn" @click="openStartSheet('BLOW')">开工</button>
            </view>
          </view>
          <!-- ② 首检/巡检 -->
          <view class="flow-step">
            <view class="step-no">②</view>
            <view class="step-body">
              <text class="step-name">首检 / 巡检 · 检验员</text>
              <text class="step-desc">逐检验项打分 + 拍照, 现场签合格放行</text>
              <view v-if="canInspect()" class="step-btns">
                <button class="step-btn small" @click="goScan('/pages/scan/fai', 'BLOW')">做首检</button>
                <button class="step-btn small" @click="goScan('/pages/scan/pat', 'BLOW')">做巡检</button>
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
          <!-- ④ 制袋开工 / 首检 / 巡检 / 报工 -->
          <view class="flow-step">
            <view class="step-no">④</view>
            <view class="step-body">
              <text class="step-name">制袋 · 开工 / 首检 / 巡检 / 报工</text>
              <text class="step-desc">先确认制袋车间生产环境合格并领用该单半成品, 首检合格后报工</text>
              <view v-if="bagProcess" class="start-facts">
                <text class="start-fact">工序: {{ bagProcess.processName }} · 状态 {{ procStatusLabel(bagProcess.status) }}</text>
              </view>
              <view v-if="bagConfirm" class="start-facts">
                <text class="start-fact">领用半成品: {{ bagConfirm.requisitionNo || '-' }}</text>
              </view>
              <view class="step-btns">
                <button v-if="canBagStart()" class="step-btn" @click="openStartSheet('BAG')">开工</button>
                <button v-if="canInspect()" class="step-btn small" @click="goScan('/pages/scan/fai', 'BAG')">制袋首检</button>
                <button v-if="canInspect()" class="step-btn small" @click="goScan('/pages/scan/pat', 'BAG')">制袋巡检</button>
                <button v-if="canReport()" class="step-btn" @click="goScan('/pages/production/report', 'BAG')">工序报工</button>
              </view>
              <view v-if="bagProcess && !bagStarted" class="gate-hint">未开工工序不能报工, 请先点「开工」确认生产环境</view>
            </view>
          </view>
          <!-- ⑤ 成品检验 -->
          <view class="flow-step">
            <view class="step-no">⑤</view>
            <view class="step-body">
              <text class="step-name">成品检验 OQC · 检验员</text>
              <text class="step-desc">成品检打分 + 拍照, 现场签合格</text>
              <button v-if="canInspect()" class="step-btn" @click="goScan('/pages/scan/oqc')">做成品检</button>
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

      <!-- v1.1.132 XK 开工记录 (生产环境确认 + 投料批号 + 批号照片留证) -->
      <view v-if="startRecords.length" class="card">
        <view class="section-title">开工记录 (XK 留痕)</view>
        <view v-for="rec in startRecords" :key="rec.id" class="ws-item">
          <view class="ws-head">
            <text class="ws-no">{{ rec.confirmNo }}</text>
            <text class="ws-stage">{{ rec.stage === 'BAG' ? '制袋' : '吹膜' }} · {{ rec.workshop }}</text>
          </view>
          <view class="kv"><text class="k">确认人 / 时间</text><text class="v">{{ rec.confirmName || '-' }} · {{ fmtTime(rec.confirmTime) }}</text></view>
          <view class="kv"><text class="k">生产环境</text><text class="v ok">合格 · 已生成 2 份环境检查记录</text></view>
          <view v-if="rec.rawBatchNos" class="kv"><text class="k">原料批号</text><text class="v">{{ rec.rawBatchNos }}</text></view>
          <view v-if="parsePhotos(rec.photoUrls).length" class="ws-photos">
            <image
              v-for="(u, i) in parsePhotos(rec.photoUrls)"
              :key="i"
              :src="u"
              mode="aspectFill"
              class="ws-photo"
              @click="previewPhotos(parsePhotos(rec.photoUrls), i)"
            />
          </view>
          <view v-if="rec.requisitionNo" class="kv"><text class="k">领用半成品</text><text class="v">{{ rec.requisitionNo }} (待仓管审核)</text></view>
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
        <button v-if="releasable" class="btn btn-success" @click="openStartSheet('BLOW')">开工</button>
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

      <!-- v1.1.132 开工确认弹窗: 生产环境 → (吹膜) 原料批号 + 批号照片 → 开工 -->
      <view v-if="sheet.visible" class="mask">
        <view class="ws-modal" @click.stop>
          <text class="ws-title">{{ sheet.stage === 'BAG' ? '制袋开工确认' : '吹膜开工确认' }}</text>
          <view class="ws-sub">{{ sheet.stage === 'BAG' ? '制袋车间' : '吹膜车间' }} · {{ order.billNo }} · {{ order.productName || '—' }}</view>

          <text class="ws-label">1. 生产环境卫生是否合格?</text>
          <view class="ws-checks">
            <view class="ws-check-item">· 地面 / 设备台面清洁, 无异物与积水</view>
            <view class="ws-check-item">· 车间温湿度符合工艺要求</view>
            <view class="ws-check-item">· 人员穿戴洁净服, 工位无个人物品</view>
          </view>
          <view class="ws-radio-row">
            <view :class="['ws-radio', 'ok', sheet.env === true && 'on']" @click="sheet.env = true">✓ 合格</view>
            <view :class="['ws-radio', 'bad', sheet.env === false && 'on']" @click="sheet.env = false">✗ 不合格</view>
          </view>
          <view v-if="sheet.env === false" class="ws-warn">生产环境不合格禁止开工。请先整理整改, 再重新确认。</view>

          <template v-if="sheet.stage === 'BLOW'">
            <text class="ws-label">2. 本次投入的原料批号</text>
            <view v-for="(b, i) in sheet.batches" :key="'b' + i" class="ws-batch-row">
              <input v-model="sheet.batches[i]" class="ws-input" placeholder="如 L20261028-03 / 原料袋上批号" />
              <text v-if="sheet.batches.length > 1" class="ws-del" @click="sheet.batches.splice(i, 1)">删除</text>
            </view>
            <view class="ws-add" @click="sheet.batches.push('')">＋ 添加批号</view>

            <text class="ws-label">3. 拍下原料批号标识 (至少 1 张)</text>
            <view class="ws-photo-row">
              <view v-for="(u, i) in sheet.photos" :key="'p' + i" class="ws-thumb">
                <image :src="u" mode="aspectFill" class="ws-photo" @click="previewPhotos(sheet.photos, i)" />
                <text class="ws-thumb-del" @click="sheet.photos.splice(i, 1)">×</text>
              </view>
              <view v-if="sheet.photos.length < 3" class="ws-shoot" @click="onShoot">📷 拍照 / 相册</view>
            </view>
          </template>

          <text class="ws-label">{{ sheet.stage === 'BAG' ? '2' : '4' }}. 备注 (选填)</text>
          <input v-model="sheet.remark" class="ws-input" placeholder="如 2# 吹膜机, 换网后开工" />

          <view class="ws-note">
            确认开工后自动生成{{ sheet.stage === 'BAG' ? '「制袋车间」' : '「吹膜车间」' }} 2 份生产环境检查合格记录{{ sheet.stage === 'BAG' ? ', 并按该单已入库半成品生成领料单' : ', 投料批号同步登记到批次追溯' }}。
          </view>
          <view class="ws-actions">
            <text class="ws-cancel" @click="closeStartSheet">取消</text>
            <button class="ws-submit" :disabled="!canSubmitStart || sheet.submitting" @click="onStartSubmit">
              {{ sheet.submitting ? '提交中...' : '确认开工' }}
            </button>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { Share } from '@capacitor/share'
import { registerPlugin } from '@capacitor/core'
// v1.1.29+: 自定义原生分享 plugin (绕开 Capacitor Share 6 + FileProvider 路径兼容问题)
const NativeShare = registerPlugin('NativeShare')
// v1.1.103: QR 码生成 (扫了可直接定位/搜索该生产单)
import QRCode from 'qrcode'
import api from '../../api/index.js'
import { pickAndUploadImage } from '../../utils/upload.js'
import { applyTabBar, isAdmin, getPermissions, getAppPermissions } from '../../utils/permission.js'
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
// v1.1.132 XK 开工确认: 工序实例 (判制袋是否已开工) + 开工台账 + 弹窗状态
const processes = ref([])
const startRecords = ref([])
const sheet = reactive({
  visible: false,
  stage: 'BLOW',
  env: null,
  batches: [''],
  photos: [],
  remark: '',
  submitting: false
})

const bagProcess = computed(() => processes.value.find(x => String(x.processName || '').includes('制袋')) || null)
const blowConfirm = computed(() => startRecords.value.find(r => r.stage === 'BLOW') || null)
const bagConfirm = computed(() => startRecords.value.find(r => r.stage === 'BAG') || null)
// 制袋「已开工」= 有过开工确认台账 或 工序状态已脱离未开工 (1 进行中 / 2 完成)
const bagStarted = computed(() => !!bagConfirm.value
  || (!!bagProcess.value && Number(bagProcess.value.status || 0) >= 1))
const canSubmitStart = computed(() => {
  if (sheet.env !== true) return false
  if (sheet.stage !== 'BLOW') return true
  const batches = (sheet.batches || []).map(s => String(s || '').trim()).filter(s => s)
  return batches.length > 0 && (sheet.photos || []).length > 0
})
// v1.1.133 总重量 (kg) = 计划数量 × 克重 ÷ 1000. 以后端 order.totalWeightKg 为准
// (PrdOrderService.totalWeightKg 唯一算法出口); 字段缺失时前端同式兜底, 不必等 jar.
const totalWeightKg = computed(() => {
  const o = order.value || {}
  if (o.totalWeightKg != null && o.totalWeightKg !== '') return Number(o.totalWeightKg).toFixed(3) + ' kg'
  const qty = Number(o.planQty) || 0
  const gram = Number(o.gramWeight) || 0
  if (qty <= 0 || gram <= 0) return '—'
  return (qty * gram / 1000).toFixed(3) + ' kg'
})
// 领料明细中 kg 行的合计, 用来自查「领料数量 = 总重量」(半成品按卷等非 kg 行不计入)
const reqTotalKg = computed(() => {
  let sum = 0
  ;(requisitionDetails.value || []).forEach(d => {
    if (String(d.unitName || '').toLowerCase() === 'kg') sum += Number(d.qty) || 0
  })
  return sum > 0 ? sum.toFixed(3) : ''
})

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
      // v1.1.132: 工序实例 + 开工台账 (silent, 无 production:report:list 时静默 fail-open)
      loadProcesses()
      loadStartRecords()
    } else {
      order.value = null
      requisitionDetails.value = []
      qrCodeUrl.value = ''
      processes.value = []
      startRecords.value = []
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

// ============ v1.1.132 XK 开工确认 (生产环境 + 原料批号 + 批号照片) ============
async function loadProcesses() {
  try {
    const rows = await api.prdOrderProgress(String(orderId.value), { silent: true })
    processes.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    console.warn('[order-detail.loadProcesses] 失败:', e)
    processes.value = []
  }
}

async function loadStartRecords() {
  try {
    const rows = await api.prdOrderStartRecords(String(orderId.value))
    startRecords.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    console.warn('[order-detail.loadStartRecords] 失败:', e)
    startRecords.value = []
  }
}

function procStatusLabel(s) {
  const n = Number(s || 0)
  return n === 1 ? '进行中' : n === 2 ? '已完成' : n === 3 ? '已回退' : '未开工'
}

function fmtTime(t) {
  const s = String(t || '')
  return s.length >= 16 ? s.slice(0, 16).replace('T', ' ') : (s || '-')
}

/** photoUrls 后端存 JSON 数组字符串; 容错也接受逗号分隔 */
function parsePhotos(raw) {
  const s = String(raw || '').trim()
  if (!s) return []
  if (s.startsWith('[')) {
    try {
      const arr = JSON.parse(s)
      return Array.isArray(arr) ? arr.filter(x => x) : []
    } catch (e) { return [] }
  }
  return s.split(',').map(x => x.trim()).filter(x => x)
}

function previewPhotos(urls, i) {
  if (!urls || !urls.length) return
  if (typeof uni !== 'undefined' && uni.previewImage) {
    uni.previewImage({ urls, current: urls[i] })
  }
}

function openStartSheet(stage) {
  if (stage === 'BAG' && !bagProcess.value) {
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: '该单还没有制袋工序, 请先开工吹膜展开工序链', icon: 'none' })
    }
    return
  }
  sheet.stage = stage
  sheet.env = null
  sheet.batches = ['']
  sheet.photos = []
  sheet.remark = ''
  sheet.submitting = false
  sheet.visible = true
}

function closeStartSheet() {
  if (sheet.submitting) return
  sheet.visible = false
}

async function onShoot() {
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '上传照片...', mask: true })
  try {
    const r = await pickAndUploadImage()
    if (r && r.url) sheet.photos.push(r.url)
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    console.warn('[order-detail.onShoot] 上传失败:', e)
    if (e && e.code === 401) return
    if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: '照片上传失败, 请重试', icon: 'none' })
  }
}

async function onStartSubmit() {
  if (!canSubmitStart.value || sheet.submitting) return
  const isBag = sheet.stage === 'BAG'
  const batches = (sheet.batches || []).map(s => String(s || '').trim()).filter(s => s)
  const payload = isBag
    ? { processId: String(bagProcess.value.id), envQualified: true, remark: sheet.remark || null }
    : { envQualified: true, rawBatchNos: batches, photoUrls: sheet.photos.slice(), remark: sheet.remark || null }
  sheet.submitting = true
  if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: '开工确认中...', mask: true })
  try {
    const r = isBag
      ? await api.prdOrderBagStart(String(orderId.value), payload)
      : await api.prdOrderBlowStart(String(orderId.value), payload)
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    sheet.visible = false
    const unknown = (r && r.unknownBatchNos) || []
    const reqNo = r && r.requisitionNo
    const msg = isBag
      ? ('制袋已开工' + (reqNo ? ', 领料单 ' + reqNo + ' 待仓管审核' : ''))
      : ('吹膜已开工' + (unknown.length ? ', 批号 ' + unknown.join('/') + ' 系统暂无来料记录' : ''))
    if (typeof uni !== 'undefined' && uni.showModal) {
      uni.showModal({
        title: '开工成功',
        content: msg + '。已生成 2 份生产环境检查合格记录。',
        showCancel: false
      })
    }
    loadOrder(orderId.value)
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    console.warn('[order-detail.onStartSubmit] 失败:', e)
    if (e && e.code === 401) return
    if (typeof uni !== 'undefined' && uni.showModal) {
      uni.showModal({ title: '开工失败', content: e.message || '请检查生产环境与原料批号', showCancel: false })
    }
  } finally {
    sheet.submitting = false
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
// v1.1.132: 制袋开工 — 工序链已展开 (吹膜开工过) 且该工序未开工
function canBagStart() {
  return !!bagProcess.value && !bagStarted.value
    && order.value && ['RELEASED', 'PRODUCING'].includes(order.value.billStatus)
    && (hasPerm('production:order:release') || hasPerm('production:report:add'))
}
function canInspect() {
  return (isAdmin() || getAppPermissions().includes('quality:inspection:check'))
    && order.value && ['RELEASED', 'PRODUCING'].includes(order.value.billStatus)
}
function canSemiIn() {
  return hasPerm('production:semi-in:add') && order.value && order.value.billStatus !== 'DRAFT'
}
function canReport() {
  // v1.1.132: 配置了制袋工序链的单, 未开工不给报工 (无工序实例的存量单维持原行为)
  return hasPerm('production:report:add') && order.value && ['RELEASED', 'PRODUCING'].includes(order.value.billStatus)
    && (!bagProcess.value || bagStarted.value)
}
function canFinishedIn() {
  return hasPerm('production:finished-in:add') && order.value && order.value.billStatus !== 'DRAFT'
}
function goScan(path, stage = '') {
  navigateTo(path + '?orderId=' + encodeURIComponent(orderId.value) + (stage ? '&stage=' + stage : ''))
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

/* v1.1.132 XK 开工确认: 台账卡 + 弹窗 */
.mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 999; display: flex; align-items: center; justify-content: center; }
.start-facts { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
.start-fact { font-size: 12px; color: #1e6091; }
.gate-hint { font-size: 11px; color: #e6a23c; margin-top: 6px; }
.ws-item { padding: 8px 0; border-top: 1px dashed #eee; }
.ws-item:first-of-type { border-top: none; }
.ws-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.ws-no { font-size: 13px; font-weight: 600; color: #303133; }
.ws-stage { font-size: 11px; color: #fff; background: #67c23a; border-radius: 10px; padding: 2px 8px; }
.v.ok { color: #67c23a; }
.ws-photos { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.ws-photo { width: 64px; height: 64px; border-radius: 6px; background: #f5f7fa; border: 1px solid #eee; }
.ws-modal { background: #fff; border-radius: 12px; width: 88%; max-width: 420px; max-height: 84vh; overflow-y: auto; padding: 16px; }
.ws-title { font-size: 16px; font-weight: bold; color: #303133; display: block; }
.ws-sub { font-size: 12px; color: #909399; margin: 4px 0 12px; }
.ws-label { font-size: 13px; font-weight: 600; color: #303133; display: block; margin: 12px 0 6px; }
.ws-checks { background: #f8f9fa; border-radius: 6px; padding: 8px 10px; }
.ws-check-item { font-size: 12px; color: #606266; line-height: 1.8; }
.ws-radio-row { display: flex; gap: 10px; margin-top: 8px; }
.ws-radio { flex: 1; text-align: center; padding: 9px 0; border-radius: 6px; font-size: 14px; border: 1px solid #dcdfe6; color: #606266; }
.ws-radio.ok.on { background: #f0f9eb; border-color: #67c23a; color: #67c23a; font-weight: 600; }
.ws-radio.bad.on { background: #fef0f0; border-color: #f56c6c; color: #f56c6c; font-weight: 600; }
.ws-warn { margin-top: 8px; font-size: 12px; color: #f56c6c; background: #fef0f0; border-radius: 6px; padding: 8px 10px; }
.ws-batch-row { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ws-input { flex: 1; border: 1px solid #dcdfe6; border-radius: 6px; padding: 8px 10px; font-size: 14px; background: #fff; }
.ws-del { font-size: 12px; color: #f56c6c; padding: 0 6px; }
.ws-add { font-size: 13px; color: #1e6091; padding: 6px 0; }
.ws-photo-row { display: flex; flex-wrap: wrap; gap: 8px; }
.ws-thumb { position: relative; }
.ws-thumb-del { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; line-height: 18px; text-align: center; border-radius: 50%; background: #f56c6c; color: #fff; font-size: 13px; }
.ws-shoot { width: 88px; height: 64px; line-height: 20px; text-align: center; border: 1px dashed #1e6091; border-radius: 6px; color: #1e6091; font-size: 12px; padding-top: 12px; box-sizing: border-box; }
.ws-note { margin-top: 12px; font-size: 11px; color: #909399; line-height: 1.6; background: #f8f9fa; border-radius: 6px; padding: 8px 10px; }
.ws-actions { display: flex; gap: 10px; margin-top: 14px; }
.ws-cancel { flex: 1; text-align: center; padding: 10px 0; border-radius: 6px; background: #f4f4f5; color: #606266; font-size: 14px; }
.ws-submit { flex: 2; background: #67c23a; color: #fff; border: none; border-radius: 6px; height: 42px; line-height: 42px; font-size: 15px; }
.ws-submit[disabled] { background: #c8e6c1; color: #f0f0f0; }
</style>
