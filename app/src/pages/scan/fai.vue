<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 首检·半成品检验</view>
      <view class="strip-title">首检 (FAI) 现场检验</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 选半成品入库单 (FAI 挂 PRD_SEMI_IN) -->
      <view class="card">
        <view class="section-title">关联半成品入库单</view>
        <picker :range="semiInOptions" @change="onPickSemiIn">
          <view class="picker-box" :class="{ active: semiInId }">
            <text v-if="pickedSemi">{{ pickedSemi.billNo }} · {{ pickedSemi.productName || pickedSemi.spec || '半成品' }}</text>
            <text v-else-if="semiInList.length">请选择半成品入库单</text>
            <text v-else class="muted">暂无半成品入库单 (请仓管先建单)</text>
          </view>
        </picker>
        <view v-if="pickedSemi && !faiSigned" class="muted form-hint">
          检验员现场逐检验项打分 + 拍照 → 一键签合格放行, 仓管随即能过门禁入库
        </view>
        <view v-if="faiSigned" class="ok-hint">✓ FAI 已签合格, 门禁放行</view>
      </view>

      <!-- 综合判定 -->
      <view class="card verdict-card">
        <view class="verdict-line">
          <text class="verdict-label">综合判定</text>
          <view :class="['verdict-tag', 'verdict-' + (faiSigned ? 'pass' : 'hold')]">
            {{ faiSigned ? '✓ 合格 PASS' : '判定: 待定' }}
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
            <view :class="['qa-toggle', it.passFlag === 0 ? 'qa-fail' : 'qa-pass']" @click="toggleItem(it)">
              {{ it.passFlag === 0 ? '不合格' : '合格' }}
            </view>
          </view>
          <view v-if="it.requirement" class="qa-req muted">要求: {{ it.requirement }}</view>
          <view class="qa-result">
            <input class="qa-input" v-model="it.resultValue" :placeholder="'实测值' + (it.unit ? ' (' + it.unit + ')' : '')" />
          </view>
        </view>
      </view>
      <view v-else class="card empty-muted" v-if="semiInId">该入库单无检验项模板, 直接签合格即可</view>

      <!-- 照片 (FAI 已存在时绑定到其附件; 未建 FAI 时随签合格 photoUrls 写入) -->
      <view class="card">
        <view class="section-title">照片 ({{ photos.length }}/6)</view>
        <view class="photo-grid">
          <view v-for="(p, i) in photos" :key="i" class="photo-cell">
            <image :src="base(p)" class="photo-img" mode="aspectFill" @click="onPreview(p)" />
            <text class="photo-del" @click="onDelPhoto(i)">×</text>
          </view>
          <view v-if="photos.length < 6" class="photo-add" @click="onUploadPhoto">📷</view>
        </view>
        <view v-if="!faiId && photos.length" class="muted" style="margin-top:6px">照片随「签合格」一并写入检验单</view>
      </view>

      <!-- 固定底部双按钮 -->
      <view class="foot">
        <button class="btn btn-save" :disabled="!semiInId || submitting" @click="onSaveDraft">保存检验</button>
        <button class="btn btn-sign" :disabled="!semiInId || submitting || itemsFail>0" @click="onSignPass">
          {{ submitting ? '处理中...' : '签合格并放行' }}
        </button>
      </view>
    </template>
  </view>
</template>

<script>
import * as qmsApi from '../../api/qms.js'
import api from '../../api/index.js'
import { pickAndUploadImage } from '../../utils/upload.js'
import { getPermissions, isAdmin } from '../../utils/permission.js'

export default {
  data() {
    return {
      loading: true,
      semiInList: [],
      semiInId: null,
      faiId: null,
      fai: {},
      items: [],
      attachments: [],
      photos: [],
      submitting: false,
      faiSigned: false,
      apiBase: ''
    }
  },
  computed: {
    semiInOptions() { return this.semiInList.map((x) => x.billNo || ('#' + x.id)) },
    pickedSemi() { return this.semiInList.find((x) => String(x.id) === String(this.semiInId)) },
    itemsFail() { return this.items.filter((x) => x.passFlag === 0).length }
  },
  onLoad(query) {
    try { this.apiBase = (localStorage.getItem('erp_api_base') || '').replace(/\/$/, '') || (typeof window !== 'undefined' ? window.location.origin + '/api' : '') } catch (e) {}
    this.loadSemiIn()
    if (query && query.semiInId) { this.semiInId = String(query.semiInId); this.bootstrap() }
  },
  methods: {
    base(p) { return p && p.fileUrl && p.fileUrl.startsWith('http') ? p.fileUrl : (this.apiBase.replace(/\/api$/, '') + '/' + (p.fileUrl || '')) },
    async loadSemiIn() {
      try {
        const r = await api.prdSemiInPage({ pageNum: 1, pageSize: 50, billStatus: 'DRAFT' })
        this.semiInList = (r && r.records) || (r && r.data && r.data.records) || []
        this.loading = false
      } catch (e) {
        this.loading = false
        if (e && e.code === 401) return
        uni.showToast({ title: '加载半成品入库单失败', icon: 'none' })
      }
    },
    onPickSemiIn(e) {
      const idx = Number(e.detail.value)
      const item = this.semiInList[idx]
      if (item) { this.semiInId = String(item.id); this.bootstrap() }
    },
    _pendingPhotoUrls: null,
    async bootstrap() {
      this.fai = {}; this.items = []; this.attachments = []; this.photos = []; this.faiSigned = false; this.faiId = null
      this._pendingPhotoUrls = null
      // 一期: FAI 由签合格时后端兜底建. 若该入库单已有 FAI (PC 端已建), 后续版本再定位载入明细;
      // 现在直接提供「按 inspectionType=FAI + sourceBillId=semiInId」的最小打分 → 签合格闭环.
    },
    toggleItem(it) { it.passFlag = it.passFlag === 0 ? 1 : 0 },
    async onUploadPhoto() {
      if (this.photos.length >= 6) { uni.showToast({ title: '最多 6 张', icon: 'none' }); return }
      try {
        const r = await pickAndUploadImage()
        // 有 FAI → 直接绑附件; 无 FAI → 暂存 URL, 随签合格写入
        if (this.faiId) {
          await qmsApi.iqcBindAttachment(this.faiId, {
            fileUrl: r.url, fileName: r.filename, fileSizeKb: Math.round((r.size || 0) / 1024), attachType: 'LABEL_PHOTO'
          })
          const list = await qmsApi.iqcAttachments(this.faiId)
          this.attachments = list || []
          this.photos = this.attachments.filter((a) => ['LABEL_PHOTO', 'PACK_PHOTO', 'SAMPLE_PHOTO', 'REPORT'].includes(a.attachType))
        } else {
          this.photos.push({ fileUrl: r.url })
          this._pendingPhotoUrls = this.photos.map((p) => p.fileUrl).join(',')
        }
      } catch (e) { console.warn('upload fail', e) }
    },
    onPreview(p) { uni.previewImage({ urls: [this.base(p)], current: this.base(p) }) },
    onDelPhoto(i) { this.photos.splice(i, 1) },
    async onSaveDraft() {
      if (!this.semiInId) { uni.showToast({ title: '请先选半成品入库单', icon: 'none' }); return }
      this.submitting = true
      try {
        if (this.faiId && this.items.length) {
          await qmsApi.iqcSaveItems(this.faiId, this.items)
          uni.showToast({ title: '检验记录已保存', icon: 'success' })
        } else {
          uni.showToast({ title: '无检验项模板, 可直接签合格', icon: 'none' })
        }
      } catch (e) {
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none' })
      } finally { this.submitting = false }
    },
    async onSignPass() {
      if (!this.semiInId) { uni.showToast({ title: '请先选半成品入库单', icon: 'none' }); return }
      if (this.itemsFail > 0) { uni.showToast({ title: '存在不合格检验项', icon: 'none' }); return }
      this.submitting = true
      try {
        if (this.faiId && this.items.length) await qmsApi.iqcSaveItems(this.faiId, this.items)
        await qmsApi.signFaiForSemiIn({
          semiInId: this.semiInId,
          faiId: this.faiId || undefined,
          photoUrls: this._pendingPhotoUrls || undefined
        })
        this.faiSigned = true
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
.container { padding: 12px; padding-bottom: 84px; }
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
.verdict-hold { background: #f4f4f5; color: #909399; }
.qa-row { border-top: 1px solid #f0f0f0; padding: 10px 0; }
.qa-row:first-of-type { border-top: none; }
.qa-head { display: flex; justify-content: space-between; align-items: center; }
.qa-name { font-size: 14px; font-weight: 500; color: #303133; }
.qa-toggle { padding: 2px 12px; border-radius: 12px; font-size: 12px; }
.qa-pass { background: #e1f3d8; color: #67c23a; }
.qa-fail { background: #fde2e2; color: #f56c6c; }
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
.foot { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; box-shadow: 0 -2px 8px rgba(0,0,0,.06); display: flex; gap: 8px; }
.btn { flex: 1; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-save { background: #ecf5fb; color: #1e6091; }
.btn-sign { background: #1e6091; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>
