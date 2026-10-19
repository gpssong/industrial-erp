<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 工序巡检</view>
      <view class="strip-title">巡检 (PAT) 现场检验</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 选生产工单 (PAT 挂 prd_order / 工序) -->
      <view class="card">
        <view class="section-title">关联生产工单</view>
        <picker :range="orderOptions" @change="onPickOrder">
          <view class="picker-box" :class="{ active: orderId }">
            <text v-if="pickedOrder">{{ pickedOrder.billNo }} · {{ pickedOrder.productName || '工单' }}</text>
            <text v-else-if="orderList.length">请选择生产工单</text>
            <text v-else class="muted">暂无生产工单</text>
          </view>
        </picker>
      </view>

      <!-- 抽检/合格/不良 -->
      <view class="card">
        <view class="section-title">巡检记录</view>
        <view class="form-row"><text class="form-label">批次/卷号</text>
          <input class="form-input" v-model="form.batchNo" placeholder="选填" /></view>
        <view class="form-row"><text class="form-label">抽检数</text>
          <input class="form-input" v-model="form.sampleQty" type="digit" placeholder="0" /></view>
        <view class="form-row"><text class="form-label">合格数</text>
          <input class="form-input" v-model="form.qualifiedQty" type="digit" placeholder="0" /></view>
        <view class="form-row"><text class="form-label">不良数</text>
          <input class="form-input" v-model="form.unqualifiedQty" type="digit" placeholder="0" /></view>
      </view>

      <!-- 照片 -->
      <view class="card">
        <view class="section-title">照片 ({{ photos.length }}/6)</view>
        <view class="photo-grid">
          <view v-for="(p, i) in photos" :key="i" class="photo-cell">
            <image :src="p.fileUrl" class="photo-img" mode="aspectFill" />
            <text class="photo-del" @click="onDelPhoto(i)">×</text>
          </view>
          <view v-if="photos.length < 6" class="photo-add" @click="onUploadPhoto">📷</view>
        </view>
      </view>

      <view class="foot">
        <button class="btn btn-sign" :disabled="!orderId || submitting" @click="onSubmit">
          {{ submitting ? '处理中...' : '提交巡检草稿' }}
        </button>
      </view>
    </template>
  </view>
</template>

<script>
import api from '../../api/index.js'
import { pickAndUploadImage } from '../../utils/upload.js'

export default {
  data() {
    return {
      loading: true,
      orderList: [],
      orderId: null,
      form: { batchNo: '', sampleQty: '', qualifiedQty: '', unqualifiedQty: '' },
      photos: [],
      submitting: false
    }
  },
  computed: {
    orderOptions() { return this.orderList.map((x) => x.billNo || ('#' + x.id)) },
    pickedOrder() { return this.orderList.find((x) => String(x.id) === String(this.orderId)) }
  },
  onLoad(query) {
    this.loadOrders()
    if (query && query.orderId) this.orderId = String(query.orderId)
  },
  methods: {
    num(v) { const n = Number(v || 0); return isFinite(n) ? n : 0 },
    async loadOrders() {
      try {
        const r = await api.prdOrderPage({ pageNum: 1, pageSize: 100 })
        this.orderList = (r && r.records) || (r && r.data && r.data.records) || []
        this.loading = false
      } catch (e) {
        this.loading = false
        if (e && e.code === 401) return
        uni.showToast({ title: '加载工单失败', icon: 'none' })
      }
    },
    onPickOrder(e) {
      const item = this.orderList[Number(e.detail.value)]
      if (item) this.orderId = String(item.id)
    },
    async onUploadPhoto() {
      if (this.photos.length >= 6) { uni.showToast({ title: '最多 6 张', icon: 'none' }); return }
      try { this.photos.push({ fileUrl: (await pickAndUploadImage()).url }) } catch (e) { console.warn('upload', e) }
    },
    onDelPhoto(i) { this.photos.splice(i, 1) },
    async onSubmit() {
      if (!this.orderId) { uni.showToast({ title: '请先选生产工单', icon: 'none' }); return }
      this.submitting = true
      try {
        const head = {
          inspectionType: 'PAT',
          sourceBillType: 'PRD_ORDER',
          sourceBillId: Number(this.orderId),
          productId: this.pickedOrder ? this.pickedOrder.productId : null,
          productCode: this.pickedOrder ? this.pickedOrder.productCode : null,
          productName: this.pickedOrder ? this.pickedOrder.productName : null,
          batchNo: this.form.batchNo || null,
          sampleQty: this.num(this.form.sampleQty),
          qualifiedQty: this.num(this.form.qualifiedQty),
          unqualifiedQty: this.num(this.form.unqualifiedQty),
          result: 'HOLD', status: 'DRAFT',
          photoUrls: this.photos.length ? this.photos.map((p) => p.fileUrl).join(',') : undefined,
          remark: 'App 巡检 (PAT) 草稿'
        }
        const defects = []
        if (this.num(this.form.unqualifiedQty) > 0) {
          defects.push({ lineNo: 1, defectType: '巡检不良', severity: 'MINOR',
            qty: this.num(this.form.unqualifiedQty), disposal: 'REWORK' })
        }
        await api.qmsInspectionCreate({ head, defects })
        uni.showToast({ title: '巡检草稿已提交', icon: 'success' })
        Object.assign(this.form, { batchNo: '', sampleQty: '', qualifiedQty: '', unqualifiedQty: '' })
        this.photos = []
      } catch (e) {
        console.warn('[pat.submit] 失败:', e)
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none' })
      } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 84px; }
.xk-strip { background: #1e6091; color: #fff; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.strip-bread { font-size: 11px; opacity: .8; }
.strip-title { font-size: 16px; font-weight: 600; margin-top: 2px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,.05); }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.muted { color: #909399; font-size: 12px; }
.picker-box { height: 40px; line-height: 40px; border: 1px solid #dcdfe6; border-radius: 6px; padding: 0 10px; font-size: 14px; color: #303133; }
.picker-box.active { border-color: #1e6091; background: #ecf5fb; }
.form-row { display: flex; align-items: center; margin-bottom: 6px; }
.form-label { width: 64px; font-size: 13px; color: #606266; }
.form-input { flex: 1; height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; box-sizing: border-box; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.photo-cell { position: relative; }
.photo-img { width: 100%; height: 72px; border-radius: 6px; background: #f4f4f5; }
.photo-del { position: absolute; top: 2px; right: 4px; background: rgba(0,0,0,.5); color: #fff; width: 18px; height: 18px; border-radius: 50%; text-align: center; line-height: 18px; font-size: 12px; }
.photo-add { height: 72px; border: 1px dashed #c0c4cc; border-radius: 6px; display: flex; align-items: center; justify-content: center; font-size: 20px; color: #c0c4cc; }
.empty { text-align: center; color: #999; padding: 60px; font-size: 14px; }
.foot { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; box-shadow: 0 -2px 8px rgba(0,0,0,.06); }
.btn { width: 100%; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-sign { background: #1e6091; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>
