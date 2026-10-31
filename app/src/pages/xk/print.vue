<template>
  <view class="container">
    <view class="xk-strip no-print">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录 · 单据打印</view>
        <view class="xk-sub">仅可打印已审核入库单 · 打印时自动登记单号与操作人 · 留存 {{ retentionYears }} 年</view>
      </view>
    </view>

    <!-- 选单 -->
    <view class="setting-block no-print">
      <view class="setting-title">
        <text>选择入库单</text>
        <text class="link" @click="loadAll">↻ 刷新</text>
      </view>

      <view v-if="listLoading" class="hint">加载中...</view>
      <view v-else-if="listError" class="hint error">
        {{ listError }}
        <text class="link" @click="loadAll">重试</text>
      </view>
      <view v-else-if="!receipts.length" class="hint">
        暂无已审核入库单（采购入库单审核通过后才可在此打印）
      </view>
      <picker v-else mode="selector" :range="receiptOptions" :value="pickIndex" @change="onPick">
        <view class="picker-row">
          <text class="picker-value">{{ currentBill ? currentBill.label : '请选择入库单' }}</text>
          <text class="picker-arrow">▾</text>
        </view>
      </picker>

      <view v-if="billLoading" class="hint">单据明细加载中...</view>
      <view v-else-if="billError" class="hint error">{{ billError }}</view>
    </view>

    <!-- 打印设置 (真实生效) -->
    <view class="setting-block no-print">
      <view class="setting-title">打印设置</view>
      <view class="form-row">
        <text class="form-label">包含原料标签照片</text>
        <switch :checked="includePhotos" color="#1e6091" @change="includePhotos = $event.detail.value" />
      </view>
      <view class="form-row">
        <text class="form-label">显示签署栏</text>
        <switch :checked="includeSign" color="#1e6091" @change="includeSign = $event.detail.value" />
      </view>
      <view class="form-row">
        <text class="form-label">页脚服务器时间戳</text>
        <switch :checked="includeServerTime" color="#1e6091" @change="includeServerTime = $event.detail.value" />
      </view>
    </view>

    <!-- 单据预览 -->
    <view v-if="bill" class="paper-preview no-print-bg">
      <view class="paper">
        <view class="paper-head">
          <view class="logo">
            <view class="logo-icon">📊</view>
            <view>
              <view class="logo-name">华塑智造</view>
              <view class="logo-en">HUASU INTELLIGENT MANUFACTURING</view>
            </view>
          </view>
          <text :class="['stamp', bill.status === 'CHECKED' ? '' : 'stamp-gray']">
            {{ bill.status === 'CHECKED' ? '已审核 · 只读' : '未审核' }}
          </text>
        </view>

        <view class="paper-title">原料入库检验单</view>
        <view class="paper-meta">
          唯一编号: {{ bill.billNo || '—' }} · 生成时间: {{ generatedText }} · 操作人: {{ bill.operator || '—' }}
        </view>

        <view class="paper-table">
          <view class="pt-row">
            <text class="pt-th">供应商</text><text class="pt-td">{{ bill.supplier || '—' }}</text>
            <text class="pt-th">入库日期</text><text class="pt-td">{{ bill.billDate || '—' }}</text>
          </view>
          <view class="pt-row">
            <text class="pt-th">仓库</text><text class="pt-td">{{ bill.warehouse || '—' }}</text>
            <text class="pt-th">制单人</text><text class="pt-td">{{ bill.operator || '—' }}</text>
          </view>
          <view class="pt-row">
            <text class="pt-th">检验报告</text><text class="pt-td">{{ bill.reportNo || '—' }}</text>
            <text class="pt-th">检验结果</text><text class="pt-td">{{ resultText }}</text>
          </view>
        </view>

        <view class="detail-table">
          <view class="dt-head">
            <text class="dt-th name">原料名称</text>
            <text class="dt-th">批号</text>
            <text class="dt-th">数量</text>
            <text class="dt-th">库位</text>
          </view>
          <view v-for="(d, i) in bill.details" :key="i" class="dt-body">
            <text class="dt-td name">{{ d.productName }}</text>
            <text class="dt-td">{{ d.batchNo || '—' }}</text>
            <text class="dt-td">{{ d.qty != null ? d.qty : '—' }}{{ d.unitName || '' }}</text>
            <text class="dt-td">{{ d.location || '—' }}</text>
          </view>
          <view v-if="!bill.details.length" class="dt-body">
            <text class="dt-td" style="flex:1; text-align:center; color:#999">该入库单没有明细</text>
          </view>
        </view>

        <view v-if="includePhotos" class="photos-section">
          <view class="section-title">📷 原料标签及到货照片</view>
          <view v-if="bill.photos.length" class="photo-row">
            <view v-for="(p, i) in bill.photos" :key="i" class="photo-cell">
              <image :src="p" mode="aspectFill" class="photo" />
              <view class="photo-cap">照片 {{ i + 1 }}</view>
            </view>
          </view>
          <view v-else class="photo-empty-note">该入库单未上传标签照片（在「原料入库」页拍照上传后再打印）</view>
        </view>

        <view v-if="bill.remark" class="note-block">
          <text class="note-label">备注: </text>{{ bill.remark }}
        </view>

        <view v-if="includeSign" class="sign-table">
          <view class="sign-row">
            <text class="sign-th">仓库员</text><text class="sign-td">{{ bill.operator || '—' }}</text>
            <text class="sign-th">质量 QA</text><text class="sign-td">{{ bill.inspector || '—' }}</text>
          </view>
        </view>

        <view class="paper-footer">
          <text>系统生成 · 不可篡改 · 保存年限 {{ retentionYears }} 年</text>
          <text v-if="includeServerTime" class="block">服务器时间 {{ serverTimeText || '—' }}（XK16-204 R5）</text>
        </view>
      </view>
    </view>

    <view v-else-if="!listLoading && !billLoading && receipts.length" class="paper-preview no-print-bg">
      <view class="paper-empty-note">请选择一张入库单</view>
    </view>

    <view class="bottom-actions no-print">
      <button class="btn-primary" :disabled="!bill || printing" @click="onPrint">🖨 打印单据</button>
    </view>
    <view class="footer-space"></view>
  </view>
</template>

<script>
// v1.1.147: 本页原为演示页 — 单号/供应商/数量全靠硬编码, 照片是外链, 印章写死「已审核」,
//   点打印只弹「请用 PC 端」。现改为: 取真实已审核采购入库单 (含明细/照片/制单人/检验员) +
//   XK 留存年限与服务器时间; H5 端直接 window.print() 出单 (带打印样式), 打印后登记审计.
import { receiptList, receiptDetail, printPreview, printAudit } from '@/api/xk'

const RESULT_TEXT = { PASS: '合格', FAIL: '不合格', PASS_DISPOSITION: '让步接收' }

export default {
  data() {
    return {
      receipts: [],
      pickIndex: 0,
      listLoading: false,
      listError: '',
      billLoading: false,
      billError: '',
      bill: null,
      retentionYears: '2',
      serverTimeText: '',
      includePhotos: true,
      includeSign: true,
      includeServerTime: true,
      printing: false
    }
  },
  computed: {
    receiptOptions() { return this.receipts.map(r => r.label) },
    currentBill() { return this.receipts[this.pickIndex] || null },
    generatedText() { return this.serverTimeText || new Date().toLocaleString('zh-CN', { hour12: false }) },
    resultText() {
      if (!this.bill || !this.bill.inspectionResult) return '—'
      return RESULT_TEXT[this.bill.inspectionResult] || this.bill.inspectionResult
    }
  },
  onShow() {
    if (!this.receipts.length) this.loadAll()
  },
  methods: {
    async loadAll() {
      this.listLoading = true
      this.listError = ''
      const p = await printPreview()
      if (p && p.code === 200) {
        this.retentionYears = p.data.retentionYears || '2'
        this.serverTimeText = p.data.serverTime ? String(p.data.serverTime).replace('T', ' ').slice(0, 16) : ''
      }
      const r = await receiptList()
      if (r && r.code === 200) {
        this.receipts = r.data
        if (this.receipts.length) {
          const keep = this.currentBill ? this.receipts.findIndex(x => x.id === this.currentBill.id) : -1
          this.pickIndex = keep >= 0 ? keep : 0
          await this.loadBill()
        } else {
          this.bill = null
        }
      } else {
        this.listError = (r && r.msg) || '入库单列表加载失败'
      }
      this.listLoading = false
    },
    async loadBill() {
      const cur = this.currentBill
      if (!cur) { this.bill = null; return }
      this.billLoading = true
      this.billError = ''
      const r = await receiptDetail(cur.id)
      if (r && r.code === 200) this.bill = r.data
      else { this.bill = null; this.billError = (r && r.msg) || '入库单明细加载失败' }
      this.billLoading = false
    },
    onPick(e) {
      const idx = Number(e.detail.value)
      if (isNaN(idx) || idx === this.pickIndex) return
      this.pickIndex = idx
      this.loadBill()
    },
    async printAuditLog() {
      if (!this.bill || !this.bill.billNo) return
      await printAudit(this.bill.billNo)   // 无 xk:audit:archive 时静默失败, 不挡打印
    },
    onPrint() {
      if (!this.bill || !this.bill.billNo) {
        uni.showToast({ title: '暂无可打印的入库单', icon: 'none' })
        return
      }
      // #ifdef H5
      this.printAuditLog()
      this.printing = true
      setTimeout(() => {
        try { window.print() } catch (e) { uni.showToast({ title: '调起打印失败', icon: 'none' }) }
        this.printing = false
      }, 80)
      // #endif
      // #ifndef H5
      uni.showModal({
        title: '打印说明',
        content: 'App 内暂不支持调起系统打印。请用手机浏览器打开 H5 版本页直接打印/另存 PDF，或在 PC 端「单据与打印」出单。',
        showCancel: false
      })
      // #endif
    }
  }
}
</script>

<style>
.container { padding: 12px; padding-bottom: 100px; background: #f0f2f5; min-height: 100vh; }
.xk-strip { background: #f0f7ff; border: 1px solid #d0e3ff; border-radius: 6px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.xk-strip .shield { color: #1e6091; font-size: 20px; }
.xk-title { font-size: 13px; font-weight: 600; color: #1e6091; }
.xk-sub { font-size: 11px; color: #5a7da0; margin-top: 2px; }

.setting-block { background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
.setting-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; }
.link { color: #1e6091; font-size: 12px; }
.form-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed #eee; }
.form-row:last-child { border-bottom: none; }
.form-label { font-size: 13px; color: #555; }

.hint { font-size: 12px; color: #999; padding: 14px 0; text-align: center; line-height: 1.6; }
.hint.error { color: #c0392b; }

.picker-row { display: flex; align-items: center; justify-content: space-between; padding: 10px 2px 2px; border-top: 1px dashed #eee; }
.picker-value { font-size: 13px; color: #1e6091; flex: 1; }
.picker-arrow { font-size: 14px; color: #999; margin-left: 8px; }

.paper-preview { background: #e0e6ed; padding: 12px; border-radius: 6px; margin-bottom: 12px; }
.paper { background: #fff; padding: 20px 16px; border-radius: 4px; box-shadow: 0 2px 6px rgba(0,0,0,0.06); }
.paper-empty-note { font-size: 12px; color: #999; padding: 24px; text-align: center; background: #fff; border-radius: 4px; }
.paper-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.logo { display: flex; align-items: center; gap: 8px; }
.logo-icon { width: 32px; height: 32px; background: linear-gradient(135deg, #1e6091, #27ae60); border-radius: 4px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 16px; }
.logo-name { font-size: 14px; font-weight: 700; color: #1e6091; }
.logo-en { font-size: 8px; color: #999; letter-spacing: 1px; }
.stamp { background: #27ae60; color: #fff; padding: 2px 8px; border-radius: 3px; font-size: 11px; }
.stamp-gray { background: #9aa5b1; }
.paper-title { text-align: center; font-size: 18px; font-weight: 700; color: #1e6091; margin: 4px 0; }
.paper-meta { text-align: center; font-size: 10px; color: #666; margin-bottom: 10px; }

.paper-table { background: #fafafa; border-radius: 4px; padding: 4px; margin-bottom: 8px; }
.pt-row { display: flex; padding: 4px 6px; border-bottom: 1px solid #eee; }
.pt-row:last-child { border-bottom: none; }
.pt-th { width: 18%; font-size: 11px; color: #888; }
.pt-td { width: 32%; font-size: 12px; color: #222; }

.detail-table { border: 1px solid #1e6091; border-radius: 4px; margin-bottom: 10px; overflow: hidden; }
.dt-head { display: flex; background: #1e6091; color: #fff; }
.dt-th { flex: 1; text-align: center; padding: 6px; font-size: 11px; }
.dt-th.name { flex: 1.6; }
.dt-body { display: flex; background: #fff; border-top: 1px solid #eee; }
.dt-td { flex: 1; text-align: center; padding: 6px; font-size: 11px; word-break: break-all; }
.dt-td.name { flex: 1.6; text-align: left; }

.photos-section { margin-bottom: 10px; }
.section-title { font-size: 12px; font-weight: 600; color: #1e6091; margin-bottom: 6px; }
.photo-row { display: flex; gap: 6px; flex-wrap: wrap; }
.photo-cell { flex: 1; min-width: 45%; border: 1px solid #e0e0e0; }
.photo { width: 100%; height: 120px; display: block; }
.photo-cap { text-align: center; font-size: 10px; color: #666; padding: 3px 0; background: #fafafa; }
.photo-empty-note { font-size: 11px; color: #999; padding: 10px; text-align: center; border: 1px dashed #ddd; border-radius: 4px; }

.note-block { background: #f9f9f9; padding: 6px 8px; border-radius: 4px; font-size: 11px; color: #555; margin-bottom: 10px; line-height: 1.5; }
.note-label { color: #1e6091; font-weight: 600; }

.sign-table { border-top: 1px solid #e0e0e0; padding-top: 8px; }
.sign-row { display: flex; }
.sign-th { width: 17%; text-align: center; font-size: 11px; color: #888; }
.sign-td { width: 33%; text-align: center; font-size: 12px; color: #222; }

.paper-footer { margin-top: 10px; padding-top: 6px; border-top: 1px solid #eee; font-size: 9px; color: #999; text-align: center; }
.paper-footer .block { display: block; margin-top: 2px; }

.bottom-actions { position: fixed; bottom: 60px; left: 0; right: 0; padding: 8px 12px; background: #fff; border-top: 1px solid #eee; z-index: 9; }
.btn-primary { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 10px; font-size: 14px; font-weight: 500; }
.btn-primary[disabled] { background: #9aa5b1; color: #fff; }
.footer-space { height: 20px; }

/* 打印: 只留纸面, 去掉操作区与 App 外壳 */
@page { size: A4 portrait; margin: 12mm; }
@media print {
  .no-print, uni-tabbar, uni-page-head { display: none !important; }
  .no-print-bg { background: #fff !important; padding: 0 !important; }
  .container { background: #fff !important; padding: 0 !important; min-height: auto !important; }
  .paper { box-shadow: none !important; border-radius: 0 !important; padding: 0 !important; }
  .photos-section { page-break-inside: avoid; }
  .photo { max-height: 140px; }
}
</style>
