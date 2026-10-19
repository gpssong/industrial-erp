<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">原料→成品/成品→原料 双向追溯 · 服务器时间戳</view>
      </view>
    </view>

    <view class="dir-row">
      <view :class="['dir-btn', direction === 'RM_TO_FG' ? 'dir-active' : '']" @click="setDir('RM_TO_FG')">原料 → 成品</view>
      <view :class="['dir-btn', direction === 'FG_TO_RM' ? 'dir-active' : '']" @click="setDir('FG_TO_RM')">成品 → 原料</view>
    </view>

    <view class="search-row">
      <input v-model="search" placeholder="搜索批号 / 供应商" class="search-input" />
    </view>

    <view class="card">
      <view class="card-title">📦 批次列表 ({{ batches.length }})</view>
      <view v-for="b in filtered" :key="b.batchNo" class="batch-item" @click="onSelect(b)">
        <view class="batch-head">
          <text class="batch-no">{{ b.batchNo }}</text>
          <text :class="['qc-tag', 'qc-' + b.qcStatus]">{{ qcLabel(b.qcStatus) }}</text>
        </view>
        <view class="batch-line">{{ b.productName }}</view>
        <view class="batch-line muted">{{ b.supplier }} · {{ b.receiptDate }} · {{ b.qty }} {{ b.unit }}</view>
      </view>
    </view>

    <view v-if="timeline" class="card">
      <view class="card-title">🕒 {{ timeline.batchNo }} 流转时间线</view>
      <view class="timeline">
        <view v-for="(s, i) in timeline.stations" :key="i" class="tl-item">
          <view class="tl-dot"></view>
          <view class="tl-content">
            <view class="tl-head">
              <text class="tl-title">{{ s.title }}</text>
              <text class="tl-time">{{ s.time }}</text>
            </view>
            <view class="tl-op">操作人: {{ s.operator }}</view>
            <view class="tl-remark">{{ s.remark }}</view>
          </view>
        </view>
      </view>
    </view>

    <view class="footer-space"></view>
  </view>
</template>

<script>
import { traceBatches, traceTimeline } from '@/api/xk'

export default {
  data() {
    return {
      direction: 'RM_TO_FG', search: '', batches: [], timeline: null
    }
  },
  computed: {
    filtered() {
      if (!this.search) return this.batches
      const q = this.search.toLowerCase()
      return this.batches.filter(b => b.batchNo.toLowerCase().includes(q) || (b.supplier || '').toLowerCase().includes(q))
    }
  },
  onShow() { this.load() },
  methods: {
    qcLabel(s) { return { PASS: '合格', HOLD: '待定', FAIL: '不合格' }[s] || s },
    async load() { const r = await traceBatches(); if (r && r.code === 200) this.batches = r.data },
    setDir(d) { this.direction = d; this.timeline = null; this.load() },
    async onSelect(b) { const r = await traceTimeline(b.batchNo); if (r && r.code === 200) this.timeline = r.data }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 80px; background: #f0f2f5; min-height: 100vh; }

.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.dir-row { display: flex; background: #fff; border-radius: 6px; padding: 4px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.dir-btn { flex: 1; text-align: center; padding: 8px; font-size: 13px; color: #666; border-radius: 4px; }
.dir-active { background: #1e6091; color: #fff; }

.search-row { background: #fff; border-radius: 6px; padding: 8px 12px; margin-bottom: 12px; }
.search-input { font-size: 14px; width: 100%; }

.card { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.card-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; }

.batch-item { padding: 10px 0; border-bottom: 1px dashed #eee; }
.batch-item:last-child { border-bottom: none; }
.batch-head { display: flex; justify-content: space-between; align-items: center; }
.batch-no { color: #1e6091; font-weight: 600; font-family: monospace; font-size: 13px; }
.qc-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; }
.qc-PASS { background: #e1f5e1; color: #27ae60; }
.qc-HOLD { background: #fff3e0; color: #e67e22; }
.qc-FAIL { background: #ffebee; color: #c0392b; }
.batch-line { font-size: 13px; color: #333; margin-top: 2px; }
.batch-line.muted { font-size: 11px; color: #888; }

.timeline { padding-left: 16px; position: relative; }
.timeline::before { content: ''; position: absolute; left: 4px; top: 0; bottom: 0; width: 2px; background: #e0e0e0; }
.tl-item { position: relative; padding-bottom: 16px; }
.tl-dot { position: absolute; left: -16px; top: 4px; width: 10px; height: 10px; border-radius: 50%; background: #1e6091; border: 2px solid #fff; box-shadow: 0 0 0 1px #1e6091; }
.tl-content { background: #fafafa; padding: 8px 10px; border-radius: 4px; }
.tl-head { display: flex; justify-content: space-between; }
.tl-title { font-weight: 600; color: #333; font-size: 13px; }
.tl-time { color: #999; font-size: 11px; }
.tl-op { font-size: 11px; color: #888; margin-top: 2px; }
.tl-remark { font-size: 12px; color: #555; margin-top: 4px; line-height: 1.5; }

.footer-space { height: 20px; }
</style>
