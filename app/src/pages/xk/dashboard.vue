<template>
  <view class="container">
    <!-- 顶部 SC 合规记录 -->
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录 · 受控记录</view>
        <view class="xk-sub">受控记录 · 禁止物理删除 · 数据留存≥2年</view>
      </view>
    </view>

    <!-- 4 KPI -->
    <view class="kpi-grid">
      <view class="kpi-card" v-for="k in kpis" :key="k.label" :style="{ borderLeftColor: k.color }">
        <view class="kpi-label">{{ k.label }}</view>
        <view class="kpi-value">{{ k.value }}<text class="unit"> {{ k.unit }}</text></view>
        <view class="kpi-sub">{{ k.sub }}</view>
      </view>
    </view>

    <!-- 实时生产看板 -->
    <view class="card">
      <view class="card-title">📊 实时生产看板</view>
      <view class="line-item" v-for="line in lines" :key="line.id">
        <view class="line-row">
          <text class="line-name">{{ line.name }}</text>
          <text class="line-wo">{{ line.wo }}</text>
        </view>
        <view class="line-sub">{{ line.product }}</view>
        <view class="line-bar">
          <view class="line-fill" :style="{ width: line.progress + '%', background: line.color }"></view>
        </view>
        <view class="line-foot">
          <text class="line-pct">{{ line.progress }}%</text>
          <text class="line-status">运行中</text>
        </view>
      </view>
    </view>

    <!-- 8 待办 -->
    <view class="card">
      <view class="card-title">🔔 待办事项 ({{ todos.length }})</view>
      <view class="todo-item" v-for="t in todos" :key="t.id">
        <view :class="['todo-level', 'lvl-' + t.level]">{{ levelLabel(t.level) }}</view>
        <view class="todo-content">
          <view class="todo-title">{{ t.title }}</view>
          <view class="todo-sub">{{ t.subtitle }}</view>
        </view>
        <view class="todo-time">{{ t.time }}</view>
      </view>
    </view>

    <!-- 4 快捷入口 -->
    <view class="card">
      <view class="card-title">⚡ 快捷入口</view>
      <view class="quick-grid">
        <view class="quick-btn" v-for="q in quicks" :key="q.path" :style="{ background: q.bg }" @click="goXk(q.path)">
          <text class="quick-icon">{{ q.icon }}</text>
          <view class="quick-title">{{ q.title }}</view>
          <view class="quick-sub">{{ q.sub }}</view>
        </view>
      </view>
    </view>

    <view class="footer-space"></view>
  </view>
</template>

<script>
import { dashboardKpi } from '@/api/xk'

export default {
  data() {
    return {
      kpis: [
        { label: '今日产量', value: '0', unit: '吨', sub: '', color: '#1e6091' },
        { label: '设备运行率', value: '0', unit: '%', sub: '', color: '#27ae60' },
        { label: '待办维保', value: '0', unit: '项', sub: '', color: '#e67e22' },
        { label: '库存预警', value: '0', unit: '种', sub: '', color: '#c0392b' }
      ],
      lines: [],
      todos: [],
      quicks: [
        { path: '/pages/scan/in',        title: '原料入库', sub: '检验入库', icon: '📷', bg: 'linear-gradient(135deg, #e3f2fd, #bbdefb)' },
        { path: '/pages/xk/trace',       title: '批次溯源', sub: '双向追溯', icon: '🔍', bg: 'linear-gradient(135deg, #e8f5e9, #c8e6c9)' },
        { path: '/pages/xk/maintenance', title: '维保反馈', sub: '现场照片', icon: '🔧', bg: 'linear-gradient(135deg, #fff3e0, #ffe0b2)' },
        { path: '/pages/xk/safety',      title: '安全巡检', sub: '整改闭环', icon: '🛡', bg: 'linear-gradient(135deg, #fce4ec, #f8bbd0)' }
      ]
    }
  },
  onShow() { this.load() },
  methods: {
    levelLabel(l) { return { URGENT: '紧急', HIGH: '高', MID: '中' }[l] || l },
    goXk(path) { uni.navigateTo({ url: path }) },
    async load() {
      const r = await dashboardKpi()
      if (r && r.code === 200) {
        const d = r.data
        this.kpis[0].value = d.todayOutput.value
        this.kpis[0].sub = '▲ ' + d.todayOutput.delta + ' 较昨日'
        this.kpis[1].value = d.equipmentRun.value
        this.kpis[1].sub = d.equipmentRun.running + ' / ' + d.equipmentRun.total + ' 台'
        this.kpis[2].value = d.pendingMaintain.value
        this.kpis[2].sub = '● ' + d.pendingMaintain.overdue + ' 项逾期'
        this.kpis[3].value = d.stockWarning.value
        this.kpis[3].sub = d.stockWarning.note
        this.lines = d.productionLines.map(l => ({ ...l, color: l.progress >= 70 ? '#27ae60' : l.progress >= 40 ? '#1e6091' : '#e67e22' }))
        this.todos = d.todos
      }
    }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 80px; background: #f0f2f5; min-height: 100vh; }

.xk-strip {
  background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px;
  padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px;
}
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.kpi-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px; }
.kpi-card {
  background: #fff; border-radius: 6px; padding: 10px 12px;
  border-left: 4px solid #1e6091;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.kpi-label { font-size: 11px; color: #888; }
.kpi-value { font-size: 22px; font-weight: bold; color: #222; margin: 2px 0; }
.kpi-value .unit { font-size: 12px; color: #888; font-weight: normal; margin-left: 2px; }
.kpi-sub { font-size: 11px; color: #999; }

.card { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.card-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; }

.line-item { padding: 8px 0; border-bottom: 1px dashed #eee; }
.line-item:last-child { border-bottom: none; }
.line-row { display: flex; justify-content: space-between; }
.line-name { font-weight: 600; color: #333; font-size: 13px; }
.line-wo { color: #999; font-size: 11px; font-family: monospace; }
.line-sub { color: #666; font-size: 11px; margin: 2px 0 6px; }
.line-bar { height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.line-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
.line-foot { display: flex; justify-content: space-between; margin-top: 4px; font-size: 11px; }
.line-pct { color: #1e6091; font-weight: 600; }
.line-status { color: #27ae60; }

.todo-item {
  display: flex; align-items: center; gap: 8px; padding: 10px 0;
  border-bottom: 1px dashed #eee;
}
.todo-item:last-child { border-bottom: none; }
.todo-level {
  flex-shrink: 0; padding: 2px 6px; border-radius: 3px;
  font-size: 10px; font-weight: 600; color: #fff; text-align: center; min-width: 36px;
}
.lvl-URGENT { background: #c0392b; }
.lvl-HIGH { background: #e67e22; }
.lvl-MID { background: #95a5a6; }
.todo-content { flex: 1; min-width: 0; }
.todo-title { font-size: 13px; color: #333; font-weight: 500; }
.todo-sub { font-size: 11px; color: #888; margin-top: 2px; }
.todo-time { font-size: 10px; color: #999; flex-shrink: 0; }

.quick-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.quick-btn {
  padding: 12px 10px; border-radius: 6px; text-align: center;
  cursor: pointer;
}
.quick-icon { font-size: 24px; }
.quick-title { font-size: 13px; font-weight: 600; color: #333; margin-top: 4px; }
.quick-sub { font-size: 10px; color: #888; margin-top: 2px; }

.footer-space { height: 60px; }
</style>
