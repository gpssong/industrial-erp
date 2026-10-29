<template>
  <ProcessInspection v-if="processMode" :order-id="processOrderId" :process-id="processId" :stage="processStage" inspection-type="FAI" />
  <view v-else class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 首检·半成品检验</view>
      <view class="strip-title">首检 (FAI) 现场检验</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 选半成品入库单 (FAI 挂 PRD_SEMI_IN) -->
      <view class="card">
        <view class="section-title">关联半成品入库单</view>
        <picker :range="semiInOptions" :disabled="submitting || uploading" @change="onPickSemiIn">
          <view class="picker-box" :class="{ active: semiInId }">
            <text v-if="pickedSemi">{{ pickedSemi.billNo }} · {{ pickedSemi.productName || pickedSemi.spec || '半成品' }}</text>
            <text v-else-if="semiInList.length">请选择半成品入库单</text>
            <text v-else class="muted">暂无半成品入库单 (请仓管先建单)</text>
          </view>
        </picker>
        <view v-if="pickedSemi && !faiChecked" class="muted form-hint">
          请逐项记录实测并明确判定，全部合格且记录齐全后才可签合格放行。
        </view>
        <view v-if="faiSigned" class="ok-hint">FAI 已签合格，门禁放行（只读）</view>
        <view v-else-if="faiChecked" class="muted form-hint">FAI 已签核 {{ fai.result }}，记录只读</view>
      </view>
      <view v-if="recordLoading" class="card empty-muted">正在加载首检及模板…</view>
      <view v-else-if="loadError" class="card empty-muted">
        <view>{{ loadError }}</view>
        <button class="btn btn-save" :disabled="submitting || uploading" @click="retryLoad">重试加载</button>
      </view>
      <view v-if="!canCheck" class="card empty-muted">仅可查看，保存及签核需要检验签核权限。</view>

      <!-- 综合判定 -->
      <view class="card verdict-card">
        <view class="verdict-line">
          <text class="verdict-label">综合判定</text>
          <view :class="['verdict-tag', 'verdict-' + (faiSigned ? 'pass' : faiChecked ? 'fail' : 'hold')]">
            {{ faiSigned ? '合格 PASS' : faiChecked ? '已签核 ' + fai.result : '判定: 待定' }}
          </view>
        </view>
        <view v-if="faiId" class="muted">检验单 {{ fai.billNo }} · 抽检 {{ fai.sampleQty || 0 }}</view>
      </view>

      <!-- 检验项明细 -->
      <view v-if="items.length" class="card">
        <view class="section-title">检验项 ({{ items.length }})</view>
        <view v-for="it in items" :key="it.id || it.lineNo" class="qa-row">
          <view class="qa-head">
            <text class="qa-name">{{ it.itemName }}</text>
            <picker :range="['待检', '合格', '不合格']" :value="it.passFlag === 1 ? 1 : it.passFlag === 0 ? 2 : 0" :disabled="formReadOnly" @change="setItemJudgment(it, $event)">
              <view :class="['qa-toggle', it.passFlag === 0 ? 'qa-fail' : it.passFlag === 1 ? 'qa-pass' : 'qa-pending']">
                {{ it.passFlag === 0 ? '不合格' : it.passFlag === 1 ? '合格' : '待检 · 请选择' }}
              </view>
            </picker>
          </view>
          <view v-if="it.requirement" class="qa-req muted">要求: {{ it.requirement }}</view>
          <view v-if="it.method" class="qa-req muted">方法: {{ it.method }}</view>
          <view v-if="it.judgmentBasis" class="qa-req muted">判定依据: {{ it.judgmentBasis }}</view>
          <view class="qa-result">
            <input class="qa-input" v-model="it.resultValue" :disabled="formReadOnly" :placeholder="'实测 / 报告号 / 不适用原因' + (it.unit ? ' (' + it.unit + ')' : '')" />
          </view>
        </view>
        <view class="muted form-hint">食品接触报告类项目核验有效报告，不是现场进行迁移试验。待检或空实测不能签合格。</view>
      </view>
      <view v-else-if="semiInId && !recordLoading && !loadError" class="card empty-muted">该入库单未配置检验项模板，不能签核，请联系质量管理人员配置后重试。</view>

      <!-- 照片 (FAI 已存在时绑定到其附件; 未建 FAI 时随签合格 photoUrls 写入) -->
      <view class="card">
        <view class="section-title">照片 ({{ photos.length }}/6)</view>
        <view class="photo-grid">
          <view v-for="(p, i) in photos" :key="i" class="photo-cell">
            <image :src="base(p)" class="photo-img" mode="aspectFill" @click="onPreview(p)" />
            <text v-if="!formReadOnly" class="photo-del" @click="onDelPhoto(i)">×</text>
          </view>
          <view v-if="photos.length < 6 && !formReadOnly" class="photo-add" @click="onUploadPhoto">+ 照片</view>
        </view>
        <view v-if="!faiId && photos.length" class="muted" style="margin-top:6px">照片随「签合格」一并写入检验单</view>
      </view>

      <!-- 固定底部双按钮 -->
      <view class="foot">
        <button class="btn btn-save" :disabled="formReadOnly || !items.length" @click="onSaveDraft">保存检验</button>
        <button class="btn btn-sign" :disabled="!ready" @click="onSignPass">
          {{ submitting ? '处理中...' : faiChecked ? '已签核（只读）' : '签合格并放行' }}
        </button>
      </view>
    </template>
  </view>
</template>

<script>
import * as qmsApi from '../../api/qms.js'
import api from '../../api/index.js'
import { pickAndUploadImage } from '../../utils/upload.js'
import { getApiBaseOverride } from '../../utils/apiBase.js'
import { getAppPermissions, isAdmin } from '../../utils/permission.js'
import ProcessInspection from '../../components/ProcessInspection.vue'

export default {
  components: { ProcessInspection },
  data() {
    return {
      processMode: false, processOrderId: '', processId: '', processStage: '',
      recordLoading: false, loadError: '', requestSeq: 0, uploading: false,
      loading: true,
      semiInList: [],
      semiInId: null,
      faiId: null,
      fai: {},
      items: [],
      attachments: [],
      photos: [],
      pendingPhotoUrls: null,
      submitting: false,
      faiSigned: false,
      apiBase: ''
    }
  },
  computed: {
    // v1.1.131: 单号后带商品名称, 选单时不用再回查是哪个半成品
    semiInOptions() {
      return this.semiInList.map((x) => {
        const no = x.billNo || ('#' + x.id)
        const name = x.productName || x.productCode || ''
        return name ? no + ' · ' + name : no
      })
    },
    pickedSemi() { return this.semiInList.find((x) => String(x.id) === String(this.semiInId)) },
    canCheck() { return isAdmin() || getAppPermissions().includes('quality:inspection:check') },
    faiChecked() { return this.fai.status === 'CHECKED' || this.faiSigned },
    formReadOnly() { return !this.canCheck || this.loading || this.recordLoading || this.submitting || this.uploading || this.faiChecked || !this.faiId || !!this.loadError },
    itemsFail() { return this.items.filter((x) => x.passFlag !== 1 || !String(x.resultValue == null ? '' : x.resultValue).trim()).length },
    ready() { return !this.formReadOnly && this.items.length > 0 && this.itemsFail === 0 }
  },
  onLoad(query = {}) {
    this.processOrderId = query.orderId == null ? '' : String(query.orderId)
    this.processId = query.processId == null ? '' : String(query.processId)
    this.processStage = query.stage == null ? '' : String(query.stage).toUpperCase()
    this.processMode = this.processStage === 'BAG' || !!this.processId
    if (this.processMode) return
    try { this.apiBase = getApiBaseOverride() || (typeof window !== 'undefined' ? window.location.origin + '/api' : '') } catch (e) {}
    this.loadSemiIn()
    if (query.semiInId) { this.semiInId = String(query.semiInId); this.bootstrap() }
  },
  onUnload() { this.requestSeq += 1 },
  methods: {
    base(p) { return p && p.fileUrl && p.fileUrl.startsWith('http') ? p.fileUrl : (this.apiBase.replace(/\/api$/, '') + '/' + (p.fileUrl || '')) },
    async loadSemiIn() {
      try {
        const r = await api.prdSemiInPage({ pageNum: 1, pageSize: 50, billStatus: 'DRAFT' })
        this.semiInList = (r && r.records) || (r && r.data && r.data.records) || []
        this.loading = false
      } catch (e) {
        this.loading = false
        if (!this.semiInId) this.loadError = '加载半成品入库单失败'
        if (e && e.code === 401) return
        uni.showToast({ title: '加载半成品入库单失败', icon: 'none' })
      }
    },
    retryLoad() {
      if (this.submitting || this.uploading || this.recordLoading) return
      if (this.semiInId) this.bootstrap()
      else { this.loadError = ''; this.loadSemiIn() }
    },
    onPickSemiIn(e) {
      if (this.submitting || this.uploading) return
      const idx = Number(e.detail.value)
      const item = this.semiInList[idx]
      if (item) { this.semiInId = String(item.id); this.bootstrap() }
    },
    async bootstrap() {
      const seq = ++this.requestSeq
      this.fai = {}; this.items = []; this.attachments = []; this.photos = []; this.faiSigned = false; this.faiId = null
      this.pendingPhotoUrls = null; this.loadError = ''; this.recordLoading = false
      // 选单即定位/补建 FAI；新模板与未明确判定的检验项保持待检。
      if (!this.semiInId) return
      this.recordLoading = true
      try {
        const r = await qmsApi.ensureFaiForSemiIn(this.semiInId)
        if (seq !== this.requestSeq) return
        if (!r || !r.head || !r.head.id || !Array.isArray(r.items)) throw new Error('首检数据不完整，请重试')
        this.fai = r.head
        this.faiId = String(r.head.id)
        this.items = r.items.map((x) => ({ ...x, id: x.id == null ? null : String(x.id),
          passFlag: x.passFlag == null || x.passFlag === '' || (r.head.status !== 'CHECKED' && Number(x.passFlag) === 1 && !String(x.resultValue == null ? '' : x.resultValue).trim()) ? -1 : ([0, 1].includes(Number(x.passFlag)) ? Number(x.passFlag) : -1),
          resultValue: x.resultValue == null ? '' : String(x.resultValue) }))
        this.faiSigned = r.head.status === 'CHECKED' && ['PASS', 'PASS_DISPOSITION'].includes(r.head.result)
      } catch (e) {
        if (seq !== this.requestSeq) return
        this.loadError = (e && (e.msg || e.message)) || '加载首检模板失败'
        console.warn('[fai.bootstrap] ensure FAI 失败:', e)
      } finally { if (seq === this.requestSeq) this.recordLoading = false }
    },
    setItemJudgment(it, event) {
      if (!this.formReadOnly) it.passFlag = [-1, 1, 0][Number(event.detail.value)] ?? -1
    },
    async onUploadPhoto() {
      if (this.formReadOnly) return
      if (this.photos.length >= 6) { uni.showToast({ title: '最多 6 张', icon: 'none' }); return }
      const seq = this.requestSeq
      this.uploading = true
      try {
        const r = await pickAndUploadImage()
        if (seq !== this.requestSeq) return
        // 有 FAI → 直接绑附件; 无 FAI → 暂存 URL, 随签合格写入
        if (this.faiId) {
          await qmsApi.iqcBindAttachment(this.faiId, {
            fileUrl: r.url, fileName: r.filename, fileSizeKb: Math.round((r.size || 0) / 1024), attachType: 'LABEL_PHOTO'
          })
          const list = await qmsApi.iqcAttachments(this.faiId)
          if (seq !== this.requestSeq) return
          this.attachments = list || []
          this.photos = this.attachments.filter((a) => ['LABEL_PHOTO', 'PACK_PHOTO', 'SAMPLE_PHOTO', 'REPORT'].includes(a.attachType))
        } else {
          this.photos.push({ fileUrl: r.url })
          this.pendingPhotoUrls = this.photos.map((p) => p.fileUrl).join(',')
        }
      } catch (e) { console.warn('upload fail', e) }
      finally { if (seq === this.requestSeq) this.uploading = false }
    },
    onPreview(p) { uni.previewImage({ urls: [this.base(p)], current: this.base(p) }) },
    onDelPhoto(i) { if (!this.formReadOnly) this.photos.splice(i, 1) },
    async onSaveDraft() {
      if (this.formReadOnly || !this.items.length) return
      if (!this.semiInId) { uni.showToast({ title: '请先选半成品入库单', icon: 'none' }); return }
      this.submitting = true
      try {
        if (this.faiId && this.items.length) {
          await qmsApi.iqcSaveItems(this.faiId, this.items)
          uni.showToast({ title: '检验记录已保存', icon: 'success' })
        } else {
          uni.showToast({ title: '缺少检验项模板，不能保存或签核', icon: 'none' })
        }
      } catch (e) {
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none' })
      } finally { this.submitting = false }
    },
    async onSignPass() {
      if (!this.ready) { uni.showToast({ title: '须有模板、逐项记录齐全且全部合格，已签核记录不可修改', icon: 'none' }); return }
      if (!this.semiInId) { uni.showToast({ title: '请先选半成品入库单', icon: 'none' }); return }
      this.submitting = true
      try {
        if (this.faiId && this.items.length) await qmsApi.iqcSaveItems(this.faiId, this.items)
        await qmsApi.signFaiForSemiIn({
          semiInId: this.semiInId,
          faiId: this.faiId || undefined,
          photoUrls: this.pendingPhotoUrls || undefined
        })
        this.faiSigned = true
        this.fai.status = 'CHECKED'; this.fai.result = 'PASS'
        uni.showToast({ title: 'FAI 已签合格, 门禁放行', icon: 'success' })
        this.loadSemiIn()
      } catch (e) {
        console.warn('[fai.signPass] 失败:', e)
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '签合格失败', icon: 'none' })
      } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped>
.container { padding: 12px; padding-bottom: calc(100px + env(safe-area-inset-bottom)); }
.xk-strip { background: #1e6091; color: #fff; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.strip-bread { font-size: 11px; opacity: .8; }
.strip-title { font-size: 16px; font-weight: 600; margin-top: 2px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.muted { color: #909399; font-size: 12px; }
.form-hint { margin-top: 8px; }
.picker-box { height: 40px; line-height: 40px; border: 1px solid #dcdfe6; border-radius: 6px; padding: 0 10px; font-size: 14px; color: #303133; }
.picker-box.active { border-color: #1e6091; background: #ecf5fb; }
.ok-hint { margin-top: 10px; color: #67c23a; font-size: 13px; font-weight: 600; }
.verdict-card { background: #f0f7ff; }
.verdict-line { display: flex; justify-content: space-between; align-items: center; }
.verdict-label { font-size: 15px; font-weight: 600; color: #303133; }
.verdict-tag { padding: 3px 12px; border-radius: 12px; font-size: 13px; }
.verdict-pass { background: #e1f3d8; color: #67c23a; }
.verdict-fail { background: #fde2e2; color: #b63838; }
.verdict-hold { background: #f4f4f5; color: #909399; }
.qa-row { border-top: 1px solid #f0f0f0; padding: 10px 0; }
.qa-row:first-of-type { border-top: none; }
.qa-head { display: flex; justify-content: space-between; align-items: center; }
.qa-name { font-size: 14px; font-weight: 500; color: #303133; }
.qa-toggle { padding: 6px 12px; border-radius: 12px; font-size: 12px; }
.qa-pass { background: #e1f3d8; color: #398637; }
.qa-fail { background: #fde2e2; color: #b63838; }
.qa-pending { background: #edf0f5; color: #596477; }
.qa-req { margin-top: 4px; }
.qa-result { margin-top: 6px; }
.qa-input { height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; box-sizing: border-box; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.photo-cell { position: relative; }
.photo-img { width: 100%; height: 72px; border-radius: 6px; background: #f4f4f5; }
.photo-del { position: absolute; top: 2px; right: 4px; background: rgba(0,0,0,.5); color: #fff; width: 18px; height: 18px; border-radius: 50%; text-align: center; line-height: 18px; font-size: 12px; }
.photo-add { height: 72px; border: 1px dashed #c0c4cc; border-radius: 6px; display: flex; align-items: center; justify-content: center; font-size: 20px; color: #c0c4cc; }
.empty { text-align: center; color: #999; padding: 60px; font-size: 14px; }
.empty-muted { color: #909399; font-size: 13px; }
.foot { position: fixed; z-index: 10; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; padding-bottom: calc(10px + env(safe-area-inset-bottom)); box-shadow: 0 -2px 8px rgba(0,0,0,.06); display: flex; gap: 8px; }
.btn { flex: 1; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-save { background: #ecf5fb; color: #1e6091; }
.btn-sign { background: #1e6091; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>
