<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 成品检验·出厂</view>
      <view class="strip-title">成品检验 (OQC) 现场检验</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <view v-else-if="loadError" class="card empty-muted">
      <view>{{ loadError }}</view>
      <button class="btn btn-save" :disabled="submitting || uploading" @click="bootstrap">重试加载</button>
    </view>
    <template v-else>
      <!-- 检验单信息 (按生产工单开单) -->
      <view class="card">
        <view class="section-title">{{ head.billNo || '成品检验单' }}</view>
        <view class="kv"><text class="k">生产工单</text><text class="v">{{ orderNo }}</text></view>
        <view class="kv"><text class="k">成品</text><text class="v">{{ head.productName || head.productCode || '-' }}{{ head.spec ? ' · ' + head.spec : '' }}</text></view>
        <view class="kv"><text class="k">出厂判型</text><text class="v">{{ stageText }}</text></view>
        <view v-if="signed" class="ok-hint">出厂检验已签合格，记录只读</view>
        <view v-else-if="checked" class="muted form-hint">该单已签核 {{ head.result }}，记录只读</view>
        <view v-else class="muted form-hint">按企业标准 Q/ZPC 1-2017 逐项实测并明确判定；数量与照片齐全后才能签合格放行。</view>
      </view>

      <view v-if="!canCheck" class="card empty-muted">仅可查看，保存及签核需要检验签核权限。</view>

      <!-- 批号 + 检验数量 -->
      <view class="card">
        <view class="section-title">批次与数量</view>
        <view class="kv">
          <text class="k">成品批号</text>
          <input class="qa-input kv-input" v-model="batchNo" :disabled="formReadOnly" placeholder="实物批号，可修改" />
        </view>
        <view class="qty-grid">
          <view class="qty-cell">
            <text class="k">抽检数</text>
            <input class="qa-input" type="digit" v-model="sampleQty" :disabled="formReadOnly" placeholder="0" />
          </view>
          <view class="qty-cell">
            <text class="k">合格数</text>
            <input class="qa-input" type="digit" v-model="qualifiedQty" :disabled="formReadOnly" placeholder="0" />
          </view>
          <view class="qty-cell">
            <text class="k">不良数</text>
            <input class="qa-input" type="digit" v-model="unqualifiedQty" :disabled="formReadOnly" placeholder="0" />
          </view>
        </view>
        <view class="muted form-hint">XK16-204 出厂检验记录须含检验数量与合格数量；合格数 + 不良数 = 抽检数。</view>
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
        <view class="muted form-hint">卫生指标类项目核验第三方检测报告，不在现场做迁移试验；待检或空实测不能签合格。</view>
      </view>
      <view v-else class="card empty-muted">该成品未配置出厂检验模板，不能签核，请联系质量管理人员配置后重试。</view>

      <!-- 不合格处置 -->
      <view v-if="itemsFail" class="card">
        <view class="section-title">不合格处置 ({{ itemsFail }} 项判不合格)</view>
        <textarea class="qa-textarea" v-model="failReason" :disabled="formReadOnly" placeholder="填写不合格现象、涉及卷/只数与处置意见（签不合格必填，签核后该批次冻结）" />
      </view>

      <!-- 照片 -->
      <view class="card">
        <view class="section-title">现场照片 ({{ photos.length }}/6)</view>
        <view class="photo-grid">
          <view v-for="(p, i) in photos" :key="i" class="photo-cell">
            <image :src="base(p)" class="photo-img" mode="aspectFill" @click="onPreview(p)" />
            <text v-if="!formReadOnly" class="photo-del" @click="onDelPhoto(i)">×</text>
          </view>
          <view v-if="photos.length < 6 && !formReadOnly" class="photo-add" @click="onUploadPhoto">+ 照片</view>
        </view>
        <view v-if="!photos.length && !formReadOnly" class="muted form-hint">签核前至少拍 1 张成品/合格证现场照。</view>
      </view>

      <!-- 固定底部双按钮 -->
      <view class="foot">
        <button class="btn btn-save" :disabled="formReadOnly || !items.length" @click="onSaveDraft">保存检验</button>
        <button :class="['btn', itemsFail ? 'btn-fail' : 'btn-sign']" :disabled="!ready" @click="onSign">
          {{ submitting ? '处理中...' : checked ? '已签核（只读）' : itemsFail ? '签不合格·冻结批次' : '签合格并放行' }}
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

export default {
  data() {
    return {
      orderId: '',
      loading: true,
      loadError: '',
      requestSeq: 0,
      submitting: false,
      uploading: false,
      head: {},
      items: [],
      attachments: [],
      photos: [],
      orderNo: '',
      stage: '',
      batchNo: '',
      sampleQty: '',
      qualifiedQty: '',
      unqualifiedQty: '',
      failReason: ''
    }
  },
  computed: {
    oqcId() { return this.head.id == null ? '' : String(this.head.id) },
    canCheck() { return isAdmin() || getAppPermissions().includes('quality:inspection:check') },
    checked() { return this.head.status === 'CHECKED' },
    signed() { return this.checked && (this.head.result === 'PASS' || this.head.result === 'PASS_DISPOSITION') },
    formReadOnly() {
      return !this.canCheck || this.loading || this.submitting || this.uploading || this.checked || !this.oqcId || !!this.loadError
    },
    stageText() { return this.stage === 'BAG' ? '包装袋（袋模板）' : this.stage === 'BLOW' ? '吹塑膜卷（膜模板）' : '未展开工序，按膜卷模板' },
    itemsFail() { return this.items.filter((x) => Number(x.passFlag) === 0).length },
    unjudged() { return this.items.filter((x) => ![0, 1].includes(Number(x.passFlag)) || !String(x.resultValue == null ? '' : x.resultValue).trim()).length },
    qtyOk() {
      const sample = Number(this.sampleQty), good = Number(this.qualifiedQty), bad = Number(this.unqualifiedQty)
      return this.sampleQty !== '' && this.qualifiedQty !== '' && this.unqualifiedQty !== ''
        && sample > 0 && good >= 0 && bad >= 0 && good + bad === sample
    },
    ready() {
      return !this.formReadOnly && this.items.length > 0 && this.unjudged === 0 && this.qtyOk
        && this.photos.length > 0 && (!this.itemsFail || this.failReason.trim().length > 0)
    }
  },
  onLoad(query = {}) {
    this.orderId = query.orderId == null ? '' : String(query.orderId)
    this.bootstrap()
  },
  onUnload() { this.requestSeq += 1 },
  methods: {
    base(p) {
      const url = (p && p.fileUrl) || ''
      return url.startsWith('http') ? url : (this.apiBase().replace(/\/api$/, '') + '/' + url.replace(/^\//, ''))
    },
    apiBase() {
      try { return getApiBaseOverride() || (typeof window !== 'undefined' ? window.location.origin + '/api' : '') } catch (e) { return '' }
    },
    async bootstrap() {
      if (!this.orderId) { this.loading = false; this.loadError = '缺少生产单参数，请从生产单详情进入'; return }
      const seq = ++this.requestSeq
      this.loading = true; this.loadError = ''
      try {
        const r = await qmsApi.ensureOqcForOrder(this.orderId)
        if (seq !== this.requestSeq) return
        if (!r || !r.head || r.head.id == null || !Array.isArray(r.items)) throw new Error('成品检验数据不完整，请重试')
        this.head = r.head
        this.orderNo = (r.order && r.order.billNo) || r.head.sourceBillNo || ''
        this.stage = r.stage || ''
        this.batchNo = r.head.batchNo || ''
        this.sampleQty = r.head.sampleQty == null ? '' : String(r.head.sampleQty)
        this.qualifiedQty = r.head.qualifiedQty == null ? '' : String(r.head.qualifiedQty)
        this.unqualifiedQty = r.head.unqualifiedQty == null ? '' : String(r.head.unqualifiedQty)
        this.items = r.items.map((x) => ({
          ...x,
          id: x.id == null ? null : String(x.id),
          // 待检哨兵: 老草稿可能灌过默认 1, 没实测值一律回落到待检, 避免「空项签合格」
          passFlag: x.passFlag == null || x.passFlag === ''
            || (r.head.status !== 'CHECKED' && Number(x.passFlag) === 1 && !String(x.resultValue == null ? '' : x.resultValue).trim())
            ? -1 : ([0, 1].includes(Number(x.passFlag)) ? Number(x.passFlag) : -1),
          resultValue: x.resultValue == null ? '' : String(x.resultValue)
        }))
        await this.loadPhotos(seq)
      } catch (e) {
        if (seq !== this.requestSeq) return
        this.loadError = (e && (e.msg || e.message)) || '加载成品检验单失败'
        console.warn('[oqc.bootstrap] ensure OQC 失败:', e)
      } finally {
        if (seq === this.requestSeq) this.loading = false
      }
    },
    async loadPhotos(seq) {
      try {
        const list = await qmsApi.iqcAttachments(this.oqcId)
        if (seq !== this.requestSeq) return
        this.attachments = list || []
        this.photos = this.attachments.filter((a) => ['SAMPLE_PHOTO', 'LABEL_PHOTO', 'PACK_PHOTO', 'REPORT'].includes(a.attachType))
      } catch (e) {
        if (seq === this.requestSeq) { this.attachments = []; this.photos = [] }
      }
    },
    setItemJudgment(it, event) {
      if (!this.formReadOnly) it.passFlag = ([-1, 1, 0][Number(event.detail.value)] ?? -1)
    },
    async onUploadPhoto() {
      if (this.formReadOnly) return
      if (this.photos.length >= 6) { uni.showToast({ title: '最多 6 张', icon: 'none' }); return }
      const seq = this.requestSeq
      this.uploading = true
      try {
        const r = await pickAndUploadImage()
        if (seq !== this.requestSeq) return
        await qmsApi.iqcBindAttachment(this.oqcId, {
          fileUrl: r.url, fileName: r.filename, fileSizeKb: Math.round((r.size || 0) / 1024), attachType: 'SAMPLE_PHOTO'
        })
        await this.loadPhotos(seq)
      } catch (e) { console.warn('[oqc.photo] 上传失败:', e) } finally {
        if (seq === this.requestSeq) this.uploading = false
      }
    },
    onPreview(p) { uni.previewImage({ urls: [this.base(p)], current: this.base(p) }) },
    onDelPhoto(i) { if (!this.formReadOnly) this.photos.splice(i, 1) },
    async onSaveDraft() {
      if (this.formReadOnly || !this.items.length) return
      this.submitting = true
      try {
        await qmsApi.iqcSaveItems(this.oqcId, this.items)
        uni.showToast({ title: '检验记录已保存', icon: 'success' })
      } catch (e) {
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none' })
      } finally { this.submitting = false }
    },
    async onSign() {
      if (!this.ready) {
        uni.showToast({
          title: this.unjudged ? '须逐项实测并判定' : !this.qtyOk ? '抽检/合格/不良数量须填齐且合格+不良=抽检'
            : !this.photos.length ? '请至少拍 1 张现场照片' : '请填写不合格处置意见',
          icon: 'none'
        })
        return
      }
      this.submitting = true
      const failed = this.itemsFail > 0
      try {
        await qmsApi.iqcSaveItems(this.oqcId, this.items)
        await api.qmsInspectionCheck(this.oqcId, {
          result: failed ? 'FAIL' : 'PASS',
          disposition: failed ? 'FROZEN' : 'NONE',
          reason: failed ? this.failReason.trim() : '现场成品检验合格，出厂放行',
          batchNo: this.batchNo.trim(),
          sampleQty: String(this.sampleQty),
          qualifiedQty: String(this.qualifiedQty),
          unqualifiedQty: String(this.unqualifiedQty)
        })
        this.head = { ...this.head, status: 'CHECKED', result: failed ? 'FAIL' : 'PASS', batchNo: this.batchNo.trim() }
        uni.showToast({ title: failed ? '已判不合格，批次冻结' : '出厂检验合格，成品入库放行', icon: 'success' })
      } catch (e) {
        console.warn('[oqc.sign] 签核失败:', e)
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '签核失败', icon: 'none' })
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
.card { background: #fff; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.muted { color: #909399; font-size: 12px; }
.form-hint { margin-top: 8px; }
.ok-hint { margin-top: 10px; color: #67c23a; font-size: 13px; font-weight: 600; }
.kv { display: flex; justify-content: space-between; align-items: center; padding: 5px 0; font-size: 14px; }
.k { color: #909399; font-size: 13px; }
.v { color: #303133; }
.kv-input { flex: 1; margin-left: 12px; }
.qty-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 8px; }
.qty-cell { display: flex; flex-direction: column; gap: 4px; }
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
.qa-textarea { height: 76px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px; font-size: 14px; box-sizing: border-box; width: 100%; }
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
.btn-fail { background: #b63838; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>
