<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">日志由系统自动写入且不可编辑 · 保存期限不少于 2 年</view>
      </view>
    </view>

    <view class="tab-bar">
      <view :class="['tab', tab === 'log' ? 'tab-active' : '']" @click="tab = 'log'">审计日志</view>
      <view :class="['tab', tab === 'archive' ? 'tab-active' : '']" @click="tab = 'archive'">记录归档</view>
    </view>

    <view class="card">
      <view v-if="tab === 'log'">
        <view v-for="l in logs" :key="l.id" class="log-item">
          <view class="log-head">
            <text :class="['act-tag', 'act-' + actionType(l.action)]">{{ actionLabel(l.action) }}</text>
            <text class="log-bill">{{ l.billNo }}</text>
          </view>
          <view class="log-change">{{ l.change }}</view>
          <view class="log-foot">
            <text class="log-op">{{ l.operator }}</text>
            <text class="log-time">{{ l.time }}</text>
            <text class="log-integrity">🔒 已固化</text>
          </view>
        </view>
      </view>

      <view v-else>
        <view v-for="a in archive" :key="a.billNo" class="archive-item">
          <view class="archive-head">
            <text class="archive-bill">{{ a.billNo }}</text>
            <text class="archive-type">{{ bizTypeLabel(a.bizType) }}</text>
          </view>
          <view class="archive-meta">创建: {{ a.createTime }} · {{ a.createBy }} · {{ a.sizeKb }} KB</view>
          <view class="archive-path">{{ a.ossPath }}</view>
        </view>
      </view>
    </view>

    <view class="footer-space"></view>
  </view>
</template>

<script>
import { auditLogs } from '@/api/xk'

export default {
  data() {
    return {
      tab: 'log', logs: [], archive: [
        { billNo: 'RK-20250618-0086', bizType: 'INBOUND', createTime: '2025-06-18 14:26', createBy: '张伟', sizeKb: 2480, ossPath: '/upload/sc/material/RK-20250618-0086/' },
        { billNo: 'WO-250618-08', bizType: 'PRODUCE', createTime: '2025-06-18 09:15', createBy: '陈军', sizeKb: 1120, ossPath: '/upload/sc/production/WO-250618-08/' },
        { billNo: 'WB-20250618-012', bizType: 'MAINTAIN', createTime: '2025-06-18 10:18', createBy: '王建国', sizeKb: 3680, ossPath: '/upload/sc/maintenance/WB-20250618-012/' }
      ]
    }
  },
  onShow() { this.load() },
  methods: {
    actionLabel(a) { return { PDF: '生成 PDF', EDIT: '修改', UPLOAD: '上传附件', VOID: '作废' }[a] || a },
    actionType(a) { return { PDF: 'success', EDIT: 'warning', UPLOAD: 'info', VOID: 'danger' }[a] || 'info' },
    bizTypeLabel(b) { return { INBOUND: '原料入库', PRODUCE: '生产报工', MAINTAIN: '设备维保', SAFETY: '卫生检查' }[b] || b },
    async load() { const r = await auditLogs(); if (r && r.code === 200) this.logs = r.data }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 80px; background: #f0f2f5; min-height: 100vh; }
.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.tab-bar { display: flex; background: #fff; border-radius: 6px; padding: 4px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.tab { flex: 1; text-align: center; padding: 8px; font-size: 13px; color: #666; border-radius: 4px; }
.tab-active { background: #1e6091; color: #fff; }

.card { background: #fff; border-radius: 6px; padding: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }

.log-item { padding: 10px 0; border-bottom: 1px dashed #eee; }
.log-item:last-child { border-bottom: none; }
.log-head { display: flex; align-items: center; gap: 6px; }
.act-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; color: #fff; }
.act-success { background: #27ae60; }
.act-warning { background: #e67e22; }
.act-info { background: #95a5a6; }
.act-danger { background: #c0392b; }
.log-bill { font-size: 12px; color: #1e6091; font-family: monospace; font-weight: 600; }
.log-change { font-size: 12px; color: #333; margin: 4px 0; line-height: 1.5; }
.log-foot { display: flex; gap: 8px; font-size: 10px; color: #888; align-items: center; }
.log-op { color: #555; }
.log-time { color: #999; }
.log-integrity { color: #27ae60; margin-left: auto; }

.archive-item { padding: 10px 0; border-bottom: 1px dashed #eee; }
.archive-item:last-child { border-bottom: none; }
.archive-head { display: flex; justify-content: space-between; align-items: center; }
.archive-bill { font-size: 13px; color: #1e6091; font-weight: 600; font-family: monospace; }
.archive-type { font-size: 10px; padding: 1px 6px; background: #f0f0f0; color: #666; border-radius: 3px; }
.archive-meta { font-size: 11px; color: #888; margin-top: 4px; }
.archive-path { font-size: 10px; color: #999; background: #f5f5f5; padding: 2px 4px; border-radius: 2px; margin-top: 4px; font-family: monospace; }

.footer-space { height: 20px; }
</style>
