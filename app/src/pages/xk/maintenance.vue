<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">附件随业务单据归档 · 不支持脱离单据单独删除</view>
      </view>
    </view>

    <!-- 工单列表 -->
    <view class="card">
      <view class="card-title">🔧 维保工单 ({{ orders.length }})</view>
      <view v-for="o in orders" :key="o.id" :class="['order-item', current && current.id === o.id ? 'order-active' : '']" @click="onSelect(o)">
        <view class="order-head">
          <text :class="['pri-tag', 'pri-' + stKey(o.status)]">{{ stLabel(o.status) }}</text>
          <text class="order-no">{{ o.orderNo }}</text>
        </view>
        <view class="order-device">{{ o.equipmentCode }} · {{ o.equipmentName || '-' }}</view>
        <view class="order-problem">{{ o.maintContent || '' }}</view>
        <view class="order-meta">📅 {{ o.planDate || '-' }} · {{ o.sourceType || 'MANUAL' }}</view>
      </view>
      <view v-if="!orders.length" class="empty-mini">暂无维保工单</view>
    </view>

    <view v-if="current" class="card">
      <view class="card-title">📝 维保处理记录</view>
      <view class="form-row"><text class="form-label">设备编号</text>
        <input class="form-input" v-model="form.deviceCode" /></view>
      <view class="form-row"><text class="form-label">维保人员</text>
        <input class="form-input" v-model="form.operator" /></view>
      <view class="form-row"><text class="form-label">开始时间</text>
        <input class="form-input" v-model="form.startTime" /></view>
      <view class="form-row"><text class="form-label">完成时间</text>
        <input class="form-input" v-model="form.endTime" /></view>
      <view class="form-row column">
        <text class="form-label">处理说明</text>
        <textarea class="form-textarea" v-model="form.description" placeholder="请描述故障原因、备件更换、处理措施..."></textarea>
      </view>

      <view class="photo-block">
        <view class="photo-head">
          <text>维保现场照片</text>
          <text class="photo-tag">{{ photos.length }}/6</text>
        </view>
        <view class="photo-grid">
          <view v-for="(p, i) in photos" :key="i" class="photo-item">
            <image :src="p" mode="aspectFill" class="photo-img" />
            <view class="photo-lock">🔒</view>
          </view>
          <view v-for="i in (6 - photos.length)" :key="`e${i}`" class="photo-item photo-empty" @click="onAddPhoto">
            <text class="cam-icon">📷</text>
            <text class="cam-text">拍照 / 上传</text>
          </view>
        </view>
        <view class="binding-note">🔒 附件随业务单据归档, 不支持脱离单据单独删除</view>
      </view>

      <button class="btn-primary" @click="onSubmit" :disabled="submitting">提交工单</button>
    </view>

    <view v-else class="empty">请选择左侧工单</view>
    <view class="footer-space"></view>
  </view>
</template>

<script>
import { maintenanceOrders } from '@/api/xk'
import api from '@/api/index.js'

export default {
  data() {
    return {
      orders: [], current: null,
      form: { deviceCode: '', operator: '', startTime: '', endTime: '', description: '' },
      photos: [], submitting: false
    }
  },
  onShow() { this.load() },
  methods: {
    priorityLabel(p) { return { URGENT: '紧急', HIGH: '高', MID: '中' }[p] || p },
    stLabel(s) {
      const m = { PENDING: '待处理', IN_PROGRESS: '处理中', COMPLETED: '已完成', CLOSED: '已关闭' }
      return m[s] || s || '待处理'
    },
    stKey(s) {
      const m = { PENDING: 'MID', IN_PROGRESS: 'MID', COMPLETED: 'URGENT', CLOSED: 'URGENT' }
      return m[s] || 'MID'
    },
    // 从 photoUrls (逗号分隔) 还原照片数组 (无则空)
    parsePhotos(o) {
      if (!o || !o.photoUrls) return []
      return String(o.photoUrls).split(',').map((x) => x.trim()).filter(Boolean)
    },
    async load() {
      const r = await maintenanceOrders()
      if (r && r.code === 200) { this.orders = r.data || []; if (this.orders.length > 0) this.onSelect(this.orders[0]) }
    },
    onSelect(o) {
      this.current = o
      this.form = {
        deviceCode: o.equipmentCode || '',
        operator: o.operator || '',
        startTime: (o.startTime || '').replace('T', ' ').slice(0, 16),
        endTime: (o.completeTime || '').replace('T', ' ').slice(0, 16),
        description: o.description || ''
      }
      this.photos = this.parsePhotos(o)
    },
    onAddPhoto() {
      if (this.photos.length >= 6) { uni.showToast({ title: '最多 6 张', icon: 'none' }); return }
      uni.chooseImage({
        count: 1,
        success: (res) => {
          // H5/App 端: 先用本地路径占位, 真实上传需走 /system/upload/file (二期)
          this.photos.push(res.tempFilePaths[0])
        }
      })
    },
    async onSubmit() {
      if (!this.current) { uni.showToast({ title: '请选择工单', icon: 'none' }); return }
      this.submitting = true
      try {
        await api.emMaintainOrderComplete(this.current.id, 'COMPLETED', this.form.operator || '现场')
        uni.showToast({ title: '工单已完成', icon: 'success' })
        setTimeout(() => this.load(), 500)
      } catch (e) {
        if (e && e.code === 401) { uni.showToast({ title: '请先重新登录', icon: 'none' }); this.submitting = false; return }
        uni.showModal({ title: '提交失败', content: (e && e.msg) || '请重试', showCancel: false })
      } finally { this.submitting = false }
    }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 80px; background: #f0f2f5; min-height: 100vh; }

.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.card { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.card-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; }

.order-item { padding: 10px; border: 1px solid #e8e8e8; border-radius: 6px; margin-bottom: 8px; }
.order-active { border-color: #1e6091; background: #f0f7ff; }
.order-head { display: flex; align-items: center; gap: 6px; }
.pri-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; color: #fff; font-weight: 500; }
.pri-URGENT { background: #c0392b; }
.pri-HIGH { background: #e67e22; }
.pri-MID { background: #95a5a6; }
.order-no { font-size: 13px; font-weight: 600; color: #333; }
.order-device { font-size: 13px; color: #1e6091; margin-top: 4px; font-weight: 500; }
.order-problem { font-size: 12px; color: #555; line-height: 1.5; margin: 4px 0; }
.order-meta { font-size: 10px; color: #999; }

.form-row { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px dashed #eee; }
.form-row.column { flex-direction: column; align-items: stretch; }
.form-label { width: 80px; font-size: 12px; color: #555; flex-shrink: 0; }
.form-input { flex: 1; font-size: 13px; color: #222; }
.form-textarea { width: 100%; min-height: 60px; font-size: 13px; padding: 6px; box-sizing: border-box; border: 1px solid #e0e0e0; border-radius: 4px; margin-top: 4px; }

.photo-block { margin-top: 12px; }
.photo-head { display: flex; justify-content: space-between; font-size: 13px; font-weight: 600; color: #333; margin-bottom: 8px; }
.photo-tag { background: #e1f5e1; color: #27ae60; font-size: 12px; padding: 2px 8px; border-radius: 10px; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.photo-item { aspect-ratio: 1; border-radius: 4px; overflow: hidden; position: relative; background: #f5f5f5; border: 1px solid #e0e0e0; }
.photo-img { width: 100%; height: 100%; }
.photo-lock { position: absolute; top: 2px; right: 2px; background: rgba(0,0,0,0.5); color: #fff; padding: 1px 3px; border-radius: 2px; font-size: 9px; }
.photo-empty { border: 2px dashed #c0c0c0; background: #fafafa; color: #888; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; }
.cam-icon { font-size: 22px; }
.cam-text { font-size: 10px; }
.binding-note { font-size: 11px; color: #888; margin-top: 8px; padding: 6px 8px; background: #f9f9f9; border-radius: 4px; }

.btn-primary { width: 100%; background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 12px; font-size: 14px; font-weight: 500; margin-top: 12px; }
.btn-primary[disabled] { opacity: 0.6; }

.empty { text-align: center; color: #999; padding: 40px 0; }
.footer-space { height: 20px; }
</style>
