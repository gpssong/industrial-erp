<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">唯一流水号自动生成 · 标签照片绑定批号</view>
      </view>
    </view>

    <view class="form-block">
      <view class="block-title">01 入库基本信息</view>
      <view class="form-row"><text class="form-label">入库单号</text>
        <input class="form-input" :value="form.billNo" disabled /></view>
      <view class="form-row"><text class="form-label">入库日期 *</text>
        <picker mode="date" :value="form.billDate" @change="e => form.billDate = e.detail.value" class="form-picker">
          <view class="form-input">{{ form.billDate || '请选择' }}</view>
        </picker></view>
      <view class="form-row"><text class="form-label">供应商 *</text>
        <view class="form-input form-static">{{ form.supplier }}</view></view>
      <view class="form-row"><text class="form-label">进货日期 *</text>
        <picker mode="date" :value="form.inboundDate" @change="e => form.inboundDate = e.detail.value" class="form-picker">
          <view class="form-input">{{ form.inboundDate || '请选择' }}</view>
        </picker></view>
      <view class="form-row"><text class="form-label">检验报告 *</text>
        <input class="form-input" :value="form.reportNo" disabled
          :placeholder="form.reportNo ? '' : '系统自动生成 (保存后回填)'" /></view>
      <view class="form-row"><text class="form-label">仓库</text>
        <view class="form-input form-static">{{ form.warehouse }}</view></view>
    </view>

    <view class="form-block">
      <view class="block-title">02 批次与数量</view>
      <view class="form-row"><text class="form-label">原料名称 *</text>
        <view class="form-input form-static">{{ form.product }}</view></view>
      <view class="form-row"><text class="form-label">适用产品线</text>
        <view class="form-input form-static">{{ form.productLine }}</view></view>
      <view class="form-row"><text class="form-label">原料批号 *</text>
        <view class="batch-col">
          <view class="batch-row">
            <input class="form-input" v-model="form.batchNo" />
            <view class="scan-btn" @click="onScan">📷 扫码</view>
            <view class="scan-btn ocr-btn" :class="{ 'ocr-disabled': !ocrEnabled || ocrLoading }"
              @click="runOcr">{{ ocrLoading ? '识别中' : '🔍 拍照框选识别' }}</view>
          </view>
          <view v-if="ocrCandidates.length" class="cand-row">
            <text class="cand-tip">候选</text>
            <text v-for="(c, ci) in ocrCandidates" :key="ci" class="cand"
                  :class="{ on: form.batchNo === c }" @click="form.batchNo = c">{{ c }}</text>
          </view>
        </view></view>
      <view class="form-row"><text class="form-label">入库数量 (kg) *</text>
        <input class="form-input" v-model="form.qty" type="number" /></view>
      <view class="form-row"><text class="form-label">库位 *</text>
        <input class="form-input" v-model="form.location" /></view>
    </view>

    <view class="form-block">
      <view class="block-title">
        <text>03 原料标签照片</text>
        <text class="photo-tag">{{ form.photos.length }}/6</text>
      </view>
      <view class="photo-grid">
        <view v-for="(p, i) in form.photos" :key="i" class="photo-item">
          <image :src="p" mode="aspectFill" class="photo-img" />
          <view class="photo-lock">🔒</view>
        </view>
        <view v-for="i in (6 - form.photos.length)" :key="`e${i}`" class="photo-item photo-empty" @click="onAddPhoto">
          <text class="cam-icon">📷</text>
          <text class="cam-text">拍照 / 上传</text>
        </view>
      </view>
      <view class="binding-note">🔒 附件随业务单据归档, 不支持脱离单据单独删除</view>
    </view>

    <!-- XK 信息完整度 -->
    <view class="score-card">
      <view class="score-head">
        <text class="shield" style="color:#1e6091">✓</text>
        <text class="score-title">XK 信息完整度</text>
      </view>
      <view class="score-num">{{ score }}%</view>
      <view class="score-bar"><view class="score-fill" :style="{ width: score + '%' }"></view></view>
      <view class="check-list">
        <view v-for="c in checks" :key="c.key" class="check-item">
          <text :class="c.ok ? 'check-ok' : 'check-pending'">{{ c.ok ? '✓' : '○' }}</text>
          <text class="check-text">{{ c.label }}</text>
        </view>
      </view>
    </view>

    <view class="bottom-actions">
      <button class="btn-plain" @click="onSave">保存草稿</button>
      <button class="btn-primary" @click="onSubmit" :disabled="submitting">✓ 提交入库</button>
    </view>
    <view class="footer-space"></view>

    <!-- v1.1.177: 全屏框选识别区域 (画在本地临时图上，原图仍整张入单据) -->
    <RegionCropper :show="cropShow" :src="cropSrc" @confirm="onCropConfirm" @cancel="onCropCancel" />
  </view>
</template>

<script>
import { materialDraft, submitMaterial, ocrStatus, ocrLabelBatchNo } from '@/api/xk'
import { addPhotoTo, photosToJson, photosFromJson, MAX_PHOTOS } from '@/utils/photo.js'
import { chooseImageOnce, uploadFile, uploadFileObject } from '@/utils/upload.js'
import { captureRegionAndRecognize } from '@/utils/ocrCapture.js'
import { cropToJpegFile } from '@/utils/ocrCrop.js'
import RegionCropper from '@/components/RegionCropper.vue'

export default {
  components: { RegionCropper },
  data() {
    return {
      form: {
        billNo: '', billDate: '2025-10-10', supplier: '', inboundDate: '2025-10-10',
        reportNo: '', warehouse: '', product: '',
        productLine: '食品袋', batchNo: '', qty: 0, location: '',
        photos: []
      },
      submitting: false,
      // v1.1.158: 视觉 OCR 识别批号 (按钮启用取决于后端是否配了视觉模型 key)
      ocrEnabled: false,
      ocrLoading: false,
      // v1.1.177: 框选识别区域 + 识别回来的候选批号条 (候选不进 form, 免得跟着单据 payload 一起提交)
      ocrCandidates: [],
      cropShow: false,
      cropSrc: '',
      cropSettle: null
    }
  },
  computed: {
    checks() {
      return [
        { key: 'batch',    label: '原料批号已填写',     ok: !!this.form.batchNo },
        { key: 'supplier', label: '供应商及进货日期完整', ok: !!this.form.supplier && !!this.form.inboundDate },
        // v1.1.158: 检验报告号改由后端自动生成 (BG-YL-日期-流水), 前端不再手填 → 视为系统保障项
        { key: 'report',   label: '检验报告号系统自动生成', ok: true },
        { key: 'photo',    label: '标签照片已绑定',     ok: this.form.photos.length > 0 }
      ]
    },
    score() {
      const total = this.checks.length
      const ok = this.checks.filter(c => c.ok).length
      return Math.round((ok / total) * 100)
    }
  },
  onShow() { this.load() },
  methods: {
    async load() {
      const r = await materialDraft()
      if (r && r.code === 200) {
        this.form.billNo = r.billNo
        this.form.supplier = r.supplier
        this.form.product = r.product
        this.form.batchNo = r.batchNo
        this.form.qty = r.qty
        this.form.location = r.location
        this.form.reportNo = r.reportNo
        this.form.warehouse = r.warehouse
        this.form.productLine = r.productLine
        // v1.1.106: 后端 labelPhotos 存的是 URL 数组 (JSON 字符串)
        this.form.photos = photosFromJson(r.labelPhotos)
      }
      // v1.1.158: 探测视觉模型是否配置 (决定「识别批号」按钮能否用)
      const s = await ocrStatus()
      this.ocrEnabled = !!(s && s.data && s.data.enabled)
    },
    // v1.1.177: 现拍一张 → 框选批号所在区域 → 只识别这张。
    // 取代老行为 (把 form.photos 全部照片整批丢给识别：既没按品名定向，又按张计费还互相串号)。
    async runOcr() {
      if (this.ocrLoading) return
      if (!this.ocrEnabled) { uni.showToast({ title: '未配置视觉模型, 请到 PC「系统设置→对接方式」填 OCR key', icon: 'none' }); return }
      this.ocrLoading = true
      const self = this
      // 批号写回 form.batchNo, 候选写回 data.ocrCandidates (不进 form, 免得跟着提交 payload 落库)
      const target = {
        productName: this.form.product,
        get batchNo() { return self.form.batchNo },
        set batchNo(v) { self.form.batchNo = v },
        set ocrCandidates(v) { self.ocrCandidates = v }
      }
      try {
        await captureRegionAndRecognize({
          ocrEnabled: this.ocrEnabled,
          pickImage: chooseImageOnce,
          uploadImage: uploadFile,
          pickRegion: this.pickRegion,
          cropAndUpload: this.cropAndUpload,
          saveOriginal: async (original) => {
            if (self.form.photos.length >= MAX_PHOTOS) throw new Error(`标签照片已满 ${MAX_PHOTOS} 张`)
            self.form.photos.push(original.url)
          },
          ocrLabelBatchNo,
          toast: (title, duration) => uni.showToast({ title, icon: 'none', duration: duration || 1500 })
        }, target)
      } finally {
        this.ocrLoading = false
      }
    },
    // 全屏框选：resolve 归一化 rect (null=整张)，取消时 reject 带 cancel 让编排静默收尾
    pickRegion(tempFilePath) {
      return new Promise((resolve, reject) => {
        this.cropSrc = tempFilePath
        this.cropSettle = { resolve, reject }
        this.cropShow = true
      })
    },
    onCropConfirm(rect) {
      this.cropShow = false
      const s = this.cropSettle
      this.cropSettle = null
      if (s) s.resolve(rect || null)
    },
    onCropCancel() {
      this.cropShow = false
      const s = this.cropSettle
      this.cropSettle = null
      if (s) s.reject({ errMsg: 'regionPicker:fail cancel' })
    },
    // 裁后小图另存一张只喂 OCR，不进单据照片 (凭证必须是整张标签)
    cropAndUpload(tempFilePath, rect) {
      return cropToJpegFile(tempFilePath, rect).then((file) => uploadFileObject(file))
    },
    // v1.1.106: 真选图 + 真上传 (原来直接 push 一个 unsplash 外链, 用户照片从来没传过)
    onAddPhoto() { return addPhotoTo(this.form.photos) },
    onSave() { uni.showToast({ title: '已保存草稿', icon: 'success' }) },
    async onSubmit() {
      if (this.score < 100) { uni.showToast({ title: 'XK 信息完整度未达 100%', icon: 'none' }); return }
      this.submitting = true
      // v1.1.106: 照片 URL 数组回传后端 (PurReceipt.labelPhotos)
      const payload = Object.assign({}, this.form, {
        labelPhotos: photosToJson(this.form.photos)
      })
      delete payload.photos
      const r = await submitMaterial(payload)
      this.submitting = false
      uni.showToast({ title: r.message, icon: 'success' })
    }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 100px; background: #f0f2f5; min-height: 100vh; }

.xk-strip {
  background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px;
  padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px;
}
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.form-block { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.block-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; display: flex; justify-content: space-between; }
.photo-tag { background: #e1f5e1; color: #27ae60; font-size: 12px; padding: 2px 8px; border-radius: 10px; }

.form-row { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px dashed #eee; }
.form-row:last-child { border-bottom: none; }
.form-label { width: 100px; font-size: 13px; color: #555; flex-shrink: 0; }
.form-input { flex: 1; font-size: 14px; color: #222; min-height: 24px; }
.form-static { color: #666; }
.form-picker { flex: 1; }
.batch-row { display: flex; flex: 1; gap: 6px; align-items: center; }
.batch-col { flex: 1; min-width: 0; }
.cand-row { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-top: 6px; }
.cand-tip { font-size: 10px; color: #98a2b3; }
.cand { font-size: 11px; color: #1a2b3c; background: #f2f5f9; border: 1px solid #e2e8f0;
  border-radius: 10px; padding: 3px 8px; word-break: break-all; }
.cand.on { color: #fff; background: #27ae60; border-color: #27ae60; font-weight: 600; }
.scan-btn { background: #1e6091; color: #fff; padding: 6px 10px; border-radius: 4px; font-size: 12px; }
.ocr-btn { background: #27ae60; }
.ocr-disabled { opacity: 0.45; }

.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.photo-item { aspect-ratio: 1; border-radius: 4px; overflow: hidden; position: relative; background: #f5f5f5; border: 1px solid #e0e0e0; }
.photo-img { width: 100%; height: 100%; }
.photo-lock { position: absolute; top: 2px; right: 2px; background: rgba(0,0,0,0.5); color: #fff; padding: 1px 3px; border-radius: 2px; font-size: 9px; }
.photo-empty {
  border: 2px dashed #c0c0c0; background: #fafafa; color: #888;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
}
.cam-icon { font-size: 22px; }
.cam-text { font-size: 10px; }
.binding-note { font-size: 11px; color: #888; margin-top: 8px; padding: 6px 8px; background: #f9f9f9; border-radius: 4px; }

.score-card { background: #fff; border-radius: 6px; padding: 14px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.score-head { display: flex; align-items: center; gap: 6px; }
.score-title { font-size: 14px; font-weight: 600; color: #333; }
.score-num { font-size: 32px; font-weight: bold; color: #1e6091; text-align: center; margin: 8px 0; }
.score-bar { height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden; margin-bottom: 12px; }
.score-fill { height: 100%; background: #1e6091; transition: width 0.3s; }
.check-list { display: flex; flex-direction: column; gap: 4px; }
.check-item { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.check-ok { color: #27ae60; font-weight: bold; }
.check-pending { color: #ccc; }
.check-text { color: #333; }

.bottom-actions { position: fixed; bottom: 60px; left: 0; right: 0; display: flex; gap: 8px; padding: 8px 12px; background: #fff; border-top: 1px solid #eee; z-index: 9; }
.btn-plain { flex: 1; background: #fff; color: #555; border: 1px solid #d0d0d0; border-radius: 4px; padding: 10px; font-size: 14px; }
.btn-primary { flex: 1; background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 10px; font-size: 14px; font-weight: 500; }
.btn-primary[disabled] { opacity: 0.6; }

.footer-space { height: 20px; }
</style>
