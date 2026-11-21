<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">原料→成品 / 成品→原料 全链追溯 · 台账来自生产领料与入库登记</view>
      </view>
    </view>

    <view class="dir-row">
      <view :class="['dir-btn', direction === 'RM_TO_FG' ? 'dir-active' : '']" @click="setDir('RM_TO_FG')">原料批</view>
      <view :class="['dir-btn', direction === 'FG_TO_RM' ? 'dir-active' : '']" @click="setDir('FG_TO_RM')">成品批</view>
    </view>

    <view class="search-row">
      <input v-model="search" placeholder="搜索批号 / 品名 / 来源单据" class="search-input" />
    </view>

    <view class="card">
      <view class="card-title">
        📦 {{ direction === 'RM_TO_FG' ? '原料' : '成品' }}批次台账 ({{ batches.length }})
        <text class="refresh" @click="load">↻</text>
      </view>

      <view v-if="loading" class="hint">加载中...</view>
      <view v-else-if="loadError" class="hint error">
        {{ loadError }}
        <text class="retry" @click="load">重试</text>
      </view>
      <view v-else-if="!filtered.length" class="hint">
        {{ direction === 'RM_TO_FG' ? '暂无原料批次（采购入库审核或生产领料登记后生成）' : '暂无成品批次（成品入库登记后生成）' }}
      </view>

      <view v-for="b in filtered" :key="b.batchNo"
            :class="['batch-item', selectedNo === b.batchNo ? 'batch-active' : '']" @click="onSelect(b)">
        <view class="batch-head">
          <text class="batch-no">{{ b.batchNo }}</text>
          <text :class="['status-tag', 'st-' + b.status]">{{ statusLabel(b.status) }}</text>
        </view>
        <view class="batch-line">
          <text class="type-badge">{{ typeLabel(b.batchType) }}</text>
          <text v-if="b.productName" class="product-name">{{ b.productName }}</text>
          <text v-if="b.supplierName" class="supplier-name">{{ b.supplierName }}</text>
          <text v-if="b.source" class="source">{{ b.source }}</text>
        </view>
        <view class="batch-line muted">
          <text v-if="b.qty != null">数量 {{ b.qty }}</text>
          <text :class="{ gap: b.qty != null }">{{ b.date || '—' }}</text>
        </view>
        <view v-if="b.holdReason" class="hold-reason">冻结原因: {{ b.holdReason }}</view>
      </view>
    </view>

    <view v-if="detailLoading" class="card hint">正在展开全链追溯...</view>
    <view v-else-if="detailError" class="card hint error">
      {{ detailError }}
      <text class="retry" @click="onSelect({ batchNo: selectedNo })">重试</text>
    </view>

    <view v-else-if="detail" class="card">
      <view class="card-title">
        🔗 {{ detail.batchNo }} 全链追溯
        <!-- v1.1.207: 没有客户出口时不许再挂绿色「链路完整」—— 现场据此判断这批料还没卖出去 -->
        <text :class="['link-badge', detail.complete ? 'lb-ok' : 'lb-no']">
          {{ detail.complete ? '链路完整' : '暂无销售出口' }}
        </text>
      </view>

      <view class="current">
        <view class="current-head">
          <text class="current-no">{{ detail.batchNo }}</text>
          <text v-if="detail.batch" :class="['status-tag', 'st-' + detail.batch.status]">{{ statusLabel(detail.batch.status) }}</text>
        </view>
        <view class="kv" v-if="detail.batch">
          <text class="k">批次类型</text><text class="v">{{ typeLabel(detail.batch.batchType) }}</text>
        </view>
        <view class="kv" v-if="detail.productName">
          <text class="k">品名</text><text class="v">{{ detail.productName }}</text>
        </view>
        <view class="kv" v-if="detail.supplierName">
          <text class="k">供应商</text><text class="v">{{ detail.supplierName }}</text>
        </view>
        <view class="kv" v-if="detail.batch && detail.batch.receiptBillNo">
          <text class="k">来源入库单</text><text class="v">{{ detail.batch.receiptBillNo }}</text>
        </view>
        <view class="kv" v-if="detail.batch && detail.batch.sourcePrdOrderNo">
          <text class="k">来源工单</text><text class="v">{{ detail.batch.sourcePrdOrderNo }}</text>
        </view>
        <view class="kv" v-if="detail.batch && detail.batch.qty != null">
          <text class="k">登记数量</text><text class="v">{{ detail.batch.qty }}</text>
        </view>
        <view class="kv" v-if="detail.batch && detail.batch.date">
          <text class="k">登记日期</text><text class="v">{{ detail.batch.date }}</text>
        </view>
        <view class="kv" v-if="detail.batch && detail.batch.holdReason">
          <text class="k">冻结原因</text><text class="v warn">{{ detail.batch.holdReason }}</text>
        </view>
      </view>

      <!-- v1.1.207: 「上游来料 / 下游流向」两栏明细换成一条竖向时间线 (与 PC 同口径).
           两栏只列批次链关系行, 而投料那一刻批次链是断的 —— 工单/销售单/客户全看不到,
           现场要的是「这批料用在哪张单上、发给了谁」, 所以一站一格, 能点的直接点进单据. -->
      <view class="section">
        <view class="section-title">流转时间线 ({{ detail.stations.length }} 站)</view>
        <!-- 圆点单独占一列、连线走圆点正下方: PC 用绝对定位连线时压到了文字 (v1.1.206 现场反馈),
             App 是竖版, 用列布局从根上避开同一类问题 -->
        <view v-for="(s, i) in detail.stations" :key="s.no" class="tl-step">
          <view class="tl-rail">
            <view :class="['tl-dot', s.title === '当前批次' ? 'dot-here' : '']"><text>{{ s.no }}</text></view>
            <view v-if="i < detail.stations.length - 1" class="tl-line"></view>
          </view>
          <view class="tl-text">
            <text class="tl-title">{{ s.title }}</text>
            <text v-if="s.time || s.operator" class="tl-sub">{{ subText(s) }}</text>
            <view v-if="s.remark" class="tl-remark">
              <text v-if="canOpen(s.link)" class="tl-link" @click="openStation(s.link)">{{ s.remark }}</text>
              <text v-else>{{ s.remark }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="footer-space"></view>
  </view>
</template>

<script>
import { traceBatches, traceFull } from '@/api/xk'
import { navigateTo } from '@/utils/nav.js'
import { isAdmin, getAppPermissions } from '@/utils/permission.js'

const TYPE_LABEL = { RAW: '原料', SEMI: '半成品', FG: '成品' }
const STATUS_LABEL = { ACTIVE: '合格', FROZEN: '冻结', SCRAP: '报废' }
// v1.1.207: 时间线站点跳到业务单据 —— 口径层 (utils/traceTimeline.js) 只给 link.key,
//           落到哪个页面/要哪条权限由这张表决定 (与 PC 的 STATION_LINKS 等价物一一对应).
const STATION_LINKS = {
  order: { page: '/pages/production/order-detail', perm: 'production:order:list' },
  delivery: { page: '/pages/sales/delivery-detail', perm: 'sales:delivery:list' }
}

export default {
  data() {
    return {
      direction: 'RM_TO_FG',
      search: '',
      batches: [],
      loading: false,
      loadError: '',
      selectedNo: '',
      detail: null,
      detailLoading: false,
      detailError: ''
    }
  },
  computed: {
    filtered() {
      if (!this.search) return this.batches
      const q = this.search.toLowerCase()
      return this.batches.filter(b =>
        (b.batchNo || '').toLowerCase().includes(q) ||
        (b.productName || '').toLowerCase().includes(q) ||
        (b.source || '').toLowerCase().includes(q))
    }
  },
  onShow() {
    if (!this.batches.length) this.load()
  },
  methods: {
    typeLabel(t) { return TYPE_LABEL[t] || t || '未知' },
    // 后端 mes_batch.status 只有 ACTIVE/FROZEN/SCRAP 三态（旧版误按 PASS/HOLD/FAIL 映射）
    statusLabel(s) { return STATUS_LABEL[s] || s || '-' },
    subText(s) { return [s.time, s.operator].filter(Boolean).join(' · ') },
    // 追溯页要给车间看, 死按钮比没按钮更糟 —— 没权限时这一格退化成纯文本, 而不是点了弹「无权访问」.
    // App 端必须用 getAppPermissions() (从 erp_menus 按 APP 严格过滤派生), 不是 PC+APP 混合的 getPermissions()
    canOpen(link) {
      if (!link) return false
      const cfg = STATION_LINKS[link.key]
      if (!cfg) return false
      return isAdmin() || getAppPermissions().includes(cfg.perm)
    },
    openStation(link) {
      if (!this.canOpen(link)) return
      const cfg = STATION_LINKS[link.key]
      // navigateTo 会先登记本次 path+query (v1.1.140 的 H5 深链丢参坑), 目标页再按 id 取单
      navigateTo(cfg.page + '?id=' + encodeURIComponent(String(link.id)))
    },
    async load() {
      this.loading = true
      this.loadError = ''
      const r = await traceBatches(this.direction)
      if (r && r.code === 200) this.batches = r.data
      else this.loadError = (r && r.msg) || '批次台账加载失败'
      this.loading = false
    },
    setDir(d) {
      if (this.direction === d) return
      this.direction = d
      this.search = ''
      this.selectedNo = ''
      this.detail = null
      this.detailError = ''
      this.batches = []
      this.load()
    },
    async onSelect(b) {
      if (!b || !b.batchNo) return
      this.selectedNo = b.batchNo
      this.detail = null
      this.detailError = ''
      this.detailLoading = true
      const r = await traceFull(b.batchNo)
      if (r && r.code === 200) this.detail = r.data
      else this.detailError = (r && r.msg) || '追溯链路加载失败'
      this.detailLoading = false
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

.dir-row { display: flex; background: #fff; border-radius: 6px; padding: 4px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.dir-btn { flex: 1; text-align: center; padding: 8px; font-size: 13px; color: #666; border-radius: 4px; }
.dir-active { background: #1e6091; color: #fff; }

.search-row { background: #fff; border-radius: 6px; padding: 8px 12px; margin-bottom: 12px; }
.search-input { font-size: 14px; width: 100%; }

.card { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.card-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; display: flex; align-items: center; justify-content: space-between; }
.refresh { color: #1e6091; font-size: 16px; padding: 0 4px; }

.hint { font-size: 12px; color: #999; padding: 14px 0; text-align: center; line-height: 1.6; }
.hint.small { padding: 8px 0; font-size: 11px; }
.hint.error { color: #c0392b; }
.retry { color: #1e6091; margin-left: 8px; text-decoration: underline; }

.batch-item { padding: 10px 8px; border-bottom: 1px dashed #eee; border-radius: 4px; }
.batch-item:last-child { border-bottom: none; }
.batch-active { background: #f0f7ff; }
.batch-head { display: flex; justify-content: space-between; align-items: center; }
.batch-no { color: #1e6091; font-weight: 600; font-family: monospace; font-size: 13px; }
.status-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; }
.st-ACTIVE { background: #e1f5e1; color: #27ae60; }
.st-FROZEN { background: #fff3e0; color: #e67e22; }
.st-SCRAP { background: #ffebee; color: #c0392b; }
.batch-line { font-size: 13px; color: #333; margin-top: 4px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.batch-line.muted { font-size: 11px; color: #888; }
.type-badge { background: #eef4fa; color: #1e6091; font-size: 10px; padding: 1px 6px; border-radius: 3px; }
.product-name { font-size: 13px; color: #333; font-weight: 600; }
.supplier-name { font-size: 11px; color: #888; }
.source { font-size: 11px; color: #666; }
.gap { margin-left: 8px; }
.hold-reason { font-size: 11px; color: #e67e22; margin-top: 4px; }

.current { background: #fafafa; border-radius: 4px; padding: 8px 10px; margin-bottom: 12px; }
.current-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.current-no { color: #1e6091; font-weight: 700; font-family: monospace; font-size: 14px; }
.kv { display: flex; font-size: 12px; padding: 2px 0; }
.kv .k { width: 76px; color: #888; flex: 0 0 auto; }
.kv .v { color: #333; word-break: break-all; }
.kv .v.warn { color: #e67e22; }

.section { margin-top: 10px; }
.section-title { font-size: 12px; font-weight: 600; color: #1e6091; margin-bottom: 6px; }

/* 竖向时间线: 左侧圆点+连线自成一行 flex 列, 文字在右列 —— 连线不可能盖住文字 */
.tl-step { display: flex; align-items: stretch; }
.tl-rail { width: 22px; display: flex; flex-direction: column; align-items: center; flex: 0 0 auto; }
.tl-dot { width: 20px; height: 20px; border-radius: 50%; background: #eef4fa; color: #1e6091; font-size: 10px; line-height: 20px; text-align: center; }
.dot-here { background: #1e6091; color: #fff; }
.tl-line { flex: 1; width: 2px; background: #d0e3ff; margin: 2px 0; }
.tl-text { flex: 1; padding-bottom: 12px; margin-left: 8px; min-width: 0; }
.tl-title { font-size: 13px; font-weight: 600; color: #333; display: block; }
.tl-sub { font-size: 11px; color: #888; margin-top: 2px; display: block; }
.tl-remark { font-size: 12px; color: #555; margin-top: 3px; font-family: monospace; word-break: break-all; }
.tl-link { color: #1e6091; text-decoration: underline; }

.link-badge { font-size: 10px; padding: 1px 6px; border-radius: 3px; margin-left: auto; }
.lb-ok { background: #e1f5e1; color: #27ae60; }
.lb-no { background: #fff3e0; color: #e67e22; }

.footer-space { height: 20px; }
</style>
