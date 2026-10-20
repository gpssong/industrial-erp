<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">原始巡检记录不可删除 · 问题修正仅可新增整改</view>
      </view>
    </view>

    <view v-if="alertCount > 0" class="alert">
      <text class="alert-icon">⚠</text>
      <view class="alert-info">
        <view class="alert-title">发现 {{ alertCount }} 项安全隐患</view>
        <view class="alert-sub">请完成整改并上传整改后照片</view>
      </view>
    </view>

    <view class="card">
      <view class="card-title">XK 车间卫生核查项目</view>
      <view class="card-sub">吹膜洁净区 · 食品接触包装 V3.2 · 完成 {{ doneCount }}/{{ items.length }}</view>
      <view v-for="(it, i) in items" :key="i" :class="['check-item', it.status === 'PROBLEM' ? 'check-problem' : '']">
        <view class="check-head">
          <text class="check-no">{{ String(i + 1).padStart(2, '0') }}</text>
          <text class="check-text">{{ it.text }}</text>
        </view>
        <view class="check-actions">
          <text :class="['check-btn', it.status === 'OK' ? 'check-active' : '']" @click="it.status = 'OK'">符合</text>
          <text :class="['check-btn', it.status === 'PROBLEM' ? 'check-active-warn' : '']" @click="it.status = 'PROBLEM'">发现问题</text>
        </view>
      </view>
    </view>

    <view v-if="alertCount > 0" class="card">
      <view class="card-title">🛠 卫生问题整改记录</view>
      <view class="form-row"><text class="form-label">问题等级</text>
        <view class="form-static">一般不符合项</view></view>
      <view class="form-row"><text class="form-label">整改负责人</text>
        <input class="form-input" v-model="reform.owner" /></view>
      <view class="form-row"><text class="form-label">整改期限</text>
        <input class="form-input" v-model="reform.deadline" /></view>
      <view class="form-row"><text class="form-label">整改状态</text>
        <view class="form-static">整改中</view></view>

      <view class="photo-section">
        <view class="photo-head">
          <text>整改前照片</text><text class="photo-tag">{{ photosBefore.length }}/6</text>
        </view>
        <view class="photo-grid">
          <view v-for="(p, i) in photosBefore" :key="i" class="photo-item">
            <image :src="p" mode="aspectFill" class="photo-img" />
            <view class="photo-lock">🔒</view>
          </view>
          <view v-for="i in (6 - photosBefore.length)" :key="`b${i}`" class="photo-item photo-empty" @click="addPhoto('before')">
            <text class="cam-icon">📷</text>
          </view>
        </view>
      </view>

      <view class="photo-section">
        <view class="photo-head">
          <text>整改后照片</text><text class="photo-tag">{{ photosAfter.length }}/6</text>
        </view>
        <view class="photo-grid">
          <view v-for="(p, i) in photosAfter" :key="i" class="photo-item">
            <image :src="p" mode="aspectFill" class="photo-img" />
            <view class="photo-lock">🔒</view>
          </view>
          <view v-for="i in (6 - photosAfter.length)" :key="`a${i}`" class="photo-item photo-empty" @click="addPhoto('after')">
            <text class="cam-icon">📷</text>
          </view>
        </view>
      </view>

      <view class="binding-note">🔒 附件随业务单据归档, 不支持脱离单据单独删除</view>
      <button class="btn-primary" @click="onSubmit">提交检查</button>
    </view>

    <view class="footer-space"></view>
  </view>
</template>

<script>
import { safetyCheck } from '@/api/xk'
import { addPhotoTo, photosFromJson } from '@/utils/photo.js'

export default {
  data() {
    return {
      items: [],
      reform: { owner: '', deadline: '' },
      // v1.1.106: 原来预置了一张 unsplash 外链 (用户会误以为是真实整改前照片),
      //          改为空数组 — 真实照片由用户自己拍照上传。
      photosBefore: [],
      photosAfter: []
    }
  },
  computed: {
    doneCount() { return this.items.filter(i => i.status === 'OK').length },
    alertCount() { return this.items.filter(i => i.status === 'PROBLEM').length }
  },
  onShow() { this.load() },
  methods: {
    async load() {
      const r = await safetyCheck()
      if (r && r.code === 200) {
        this.items = r.items
        // v1.1.106: 回显后端已存的照片 (XkSafetyCheck.reformPhotosBefore/After)
        this.photosBefore = photosFromJson(r.photosBefore)
        this.photosAfter = photosFromJson(r.photosAfter)
        if (r.reformOwner) this.reform.owner = r.reformOwner
        if (r.reformDeadline) this.reform.deadline = r.reformDeadline
      }
    },
    // v1.1.106: 真选图 + 真上传 (原来直接 push 一个 unsplash 外链)
    addPhoto(which) {
      const arr = which === 'before' ? 'photosBefore' : 'photosAfter'
      return addPhotoTo(this[arr])
    },
    onSubmit() { uni.showToast({ title: '检查单已提交, 闭环已建立', icon: 'success' }) }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 80px; background: #f0f2f5; min-height: 100vh; }
.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.alert { background: #fff8e1; border-left: 4px solid #ff9800; border-radius: 4px; padding: 10px 12px; display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.alert-icon { font-size: 22px; color: #ff9800; }
.alert-title { font-size: 13px; font-weight: 600; color: #e65100; }
.alert-sub { font-size: 11px; color: #8d6e63; margin-top: 2px; }

.card { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.card-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 4px; }
.card-sub { font-size: 11px; color: #888; margin-bottom: 10px; }

.check-item { padding: 10px; border: 1px solid #e8e8e8; border-radius: 4px; margin-bottom: 6px; background: #fafafa; }
.check-problem { background: #fff8e1; border-color: #ffe0b2; }
.check-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.check-no { background: #e0e0e0; color: #666; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 600; }
.check-problem .check-no { background: #c0392b; color: #fff; }
.check-text { flex: 1; font-size: 13px; color: #333; }
.check-actions { display: flex; gap: 6px; }
.check-btn { font-size: 11px; padding: 4px 10px; border: 1px solid #d0d0d0; border-radius: 3px; color: #888; }
.check-active { background: #1e6091; color: #fff; border-color: #1e6091; }
.check-active-warn { background: #c0392b; color: #fff; border-color: #c0392b; }

.form-row { display: flex; align-items: center; padding: 6px 0; border-bottom: 1px dashed #eee; }
.form-label { width: 80px; font-size: 12px; color: #555; }
.form-input { flex: 1; font-size: 13px; }
.form-static { flex: 1; font-size: 13px; color: #666; }

.photo-section { margin-top: 12px; }
.photo-head { display: flex; justify-content: space-between; font-size: 12px; font-weight: 600; color: #333; margin-bottom: 6px; }
.photo-tag { background: #e1f5e1; color: #27ae60; padding: 1px 6px; border-radius: 8px; font-size: 10px; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.photo-item { aspect-ratio: 1; border-radius: 4px; overflow: hidden; position: relative; background: #f5f5f5; border: 1px solid #e0e0e0; }
.photo-img { width: 100%; height: 100%; }
.photo-lock { position: absolute; top: 2px; right: 2px; background: rgba(0,0,0,0.5); color: #fff; padding: 1px 3px; border-radius: 2px; font-size: 9px; }
.photo-empty { border: 2px dashed #c0c0c0; background: #fafafa; color: #888; display: flex; align-items: center; justify-content: center; }
.cam-icon { font-size: 22px; }
.binding-note { font-size: 11px; color: #888; margin-top: 8px; padding: 6px 8px; background: #f9f9f9; border-radius: 4px; }

.btn-primary { width: 100%; background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 12px; font-size: 14px; font-weight: 500; margin-top: 12px; }
.footer-space { height: 20px; }
</style>
