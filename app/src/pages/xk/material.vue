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
        <input class="form-input" v-model="form.reportNo" /></view>
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
        <view class="batch-row">
          <input class="form-input" v-model="form.batchNo" />
          <view class="scan-btn" @click="onScan">📷 扫码</view>
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
  </view>
</template>

<script>
import { materialDraft, submitMaterial } from '@/api/xk'
import { addPhotoTo, photosToJson, photosFromJson } from '@/utils/photo.js'

export default {
  data() {
    return {
      form: {
        billNo: '', billDate: '2025-10-10', supplier: '', inboundDate: '2025-10-10',
        reportNo: '', warehouse: '', product: '',
        productLine: '食品袋', batchNo: '', qty: 0, location: '',
        photos: []
      },
      submitting: false
    }
  },
  computed: {
    checks() {
      return [
        { key: 'batch',    label: '原料批号已填写',     ok: !!this.form.batchNo },
        { key: 'supplier', label: '供应商及进货日期完整', ok: !!this.form.supplier && !!this.form.inboundDate },
        { key: 'report',   label: '检验报告已关联',     ok: !!this.form.reportNo },
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
.scan-btn { background: #1e6091; color: #fff; padding: 6px 10px; border-radius: 4px; font-size: 12px; }

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
