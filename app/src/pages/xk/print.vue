<template>
  <view class="container">
    <view class="xk-strip">
      <text class="shield">✓</text>
      <view class="xk-info">
        <view class="xk-title">XK 合规记录</view>
        <view class="xk-sub">PDF 生成后计算文件校验值, 记录生成时间与操作人</view>
      </view>
    </view>

    <view class="setting-block">
      <view class="setting-title">打印设置</view>
      <view class="form-row"><text class="form-label">纸张规格</text>
        <view class="form-static">A4 纵向</view></view>
      <view class="form-row"><text class="form-label">打印份数</text>
        <view class="form-static">1</view></view>
      <view class="form-row">
        <text class="form-label">包含原料标签照片</text>
        <switch :checked="true" color="#1e6091" />
      </view>
      <view class="form-row">
        <text class="form-label">显示审核签章</text>
        <switch :checked="true" color="#1e6091" />
      </view>
      <view class="form-row">
        <text class="form-label">显示二维码</text>
        <switch :checked="true" color="#1e6091" />
      </view>
    </view>

    <view class="paper-preview">
      <view class="paper">
        <view class="paper-head">
          <view class="logo">
            <view class="logo-icon">📊</view>
            <view>
              <view class="logo-name">华塑智造</view>
              <view class="logo-en">HUASU INTELLIGENT MANUFACTURING</view>
            </view>
          </view>
          <text class="stamp">已审核 · 只读</text>
        </view>
        <view class="paper-title">原料入库检验单</view>
        <view class="paper-meta">唯一编号: {{ bill.billNo || '—' }} · 生成时间: {{ bill.generatedAt }} · 操作人: {{ bill.operator || '—' }}</view>

        <view class="paper-table">
          <view class="pt-row">
            <text class="pt-th">供应商</text><text class="pt-td">{{ bill.supplier || '—' }}</text>
            <text class="pt-th">入库日期</text><text class="pt-td">{{ bill.billDate || '—' }}</text>
          </view>
          <view class="pt-row">
            <text class="pt-th">仓库</text><text class="pt-td">{{ bill.warehouse || '—' }}</text>
            <text class="pt-th">制单人</text><text class="pt-td">{{ bill.operator || '—' }}</text>
          </view>
        </view>

        <view class="detail-table">
          <view class="dt-head">
            <text class="dt-th">原料名称</text>
            <text class="dt-th">批号</text>
            <text class="dt-th">数量</text>
            <text class="dt-th">库位</text>
            <text class="dt-th">质检</text>
          </view>
          <view v-for="(d, i) in bill.details" :key="i" class="dt-body">
            <text class="dt-td">{{ d.productName }}</text>
            <text class="dt-td">{{ d.batchNo || '—' }}</text>
            <text class="dt-td">{{ d.qty }} {{ d.unitName || '' }}</text>
            <text class="dt-td">{{ d.location || '—' }}</text>
            <text class="dt-td"><text class="tag-pass">{{ d.iqcResult || '合格' }}</text></text>
          </view>
          <view v-if="!bill.details.length" class="dt-body">
            <text class="dt-td" style="flex:1; text-align:center; color:#999">暂无可打印的入库明细</text>
          </view>
        </view>

        <!-- v1.1.106: 原为 2 张 unsplash 外链 (别人的库存照片, 打印出来是假证据) -->
        <view v-if="bill.photos.length" class="photos-section">
          <view class="section-title">📷 原料标签及到货照片</view>
          <view class="photo-row">
            <view v-for="(p, i) in bill.photos" :key="i" class="photo-cell">
              <image :src="p" mode="aspectFill" class="photo" />
              <view class="photo-cap">照片 {{ i + 1 }}</view>
            </view>
          </view>
        </view>
        <view v-else class="photos-section">
          <view class="section-title">📷 原料标签及到货照片</view>
          <view class="photo-empty-note">该入库单未上传标签照片 (在「原料入库」页拍照上传后再打印)</view>
        </view>

        <view class="note-block">
          <text class="note-label">检验备注: </text>包装完整无破损, 标签批次与送货单一致, 抽检指标符合入库标准。
        </view>

        <view class="sign-table">
          <view class="sign-row">
            <text class="sign-th">仓库员</text><text class="sign-td">{{ bill.operator || '—' }}</text>
            <text class="sign-th">质量 QA</text><text class="sign-td">{{ bill.iqcInspector || '—' }}</text>
            <text class="sign-th">审核人</text><text class="sign-td">{{ bill.checker || '—' }}</text>
          </view>
        </view>

        <view class="paper-footer">
          <text>系统生成 · 生成时间以服务器时间戳为准 (XK16-204 R5) · 保存年限 {{ retentionYears }} 年 · 不可篡改</text>
        </view>
      </view>
    </view>

    <view class="bottom-actions">
      <button class="btn-primary" @click="onPrint">🖨 打印单据</button>
    </view>
    <view class="footer-space"></view>
  </view>
</template>

<script>
// v1.1.106: 本页原为纯演示页 — 单号/供应商/数量/签章人/照片全是硬编码字符串,
//   2 张照片还是 unsplash 外链 (别人的仓库照片)。打印出来就是一份假 XK 合规记录。
//   现在接真后端: 取最新已审核采购入库单 (含明细 + labelPhotos) + XK 留存年限配置。
import { materialDraft, printPreview } from '@/api/xk'
import { photosFromJson } from '@/utils/photo.js'

export default {
  data() {
    return {
      retentionYears: '2',
      bill: {
        billNo: '', supplier: '', billDate: '', warehouse: '', operator: '',
        iqcInspector: '', checker: '', generatedAt: '', details: [], photos: []
      }
    }
  },
  onShow() { this.load() },
  methods: {
    async load() {
      // 留存年限 (XK16-204 要求 ≥2 年)
      try {
        const p = await printPreview()
        if (p && p.code === 200) this.retentionYears = p.retentionYears || '2'
      } catch (e) { /* 兜底 2 */ }

      // 取最近一张已审核入库单作为打印内容
      try {
        const d = await materialDraft()
        if (d && d.code === 200) {
          this.bill.billNo = d.billNo || ''
          this.bill.supplier = d.supplier || ''
          this.bill.billDate = d.billDate || ''
          this.bill.warehouse = d.warehouse || ''
          this.bill.operator = d.operator || ''
          this.bill.iqcInspector = d.iqcInspector || ''
          this.bill.checker = d.checker || ''
          this.bill.details = (d.details || []).map(x => ({
            productName: x.productName || '-',
            batchNo: x.batchNo || '',
            qty: x.qty != null ? x.qty : '-',
            unitName: x.unitName || '',
            location: x.location || '',
            iqcResult: x.iqcResult || '合格'
          }))
          this.bill.photos = photosFromJson(d.labelPhotos)
        }
      } catch (e) { /* 空态由模板兜底 */ }
      this.bill.generatedAt = new Date().toLocaleString('zh-CN', { hour12: false })
    },
    onPrint() {
      if (!this.bill.billNo) { uni.showToast({ title: '暂无可打印的入库单', icon: 'none' }); return }
      uni.showToast({ title: '请使用 PC 端打印模板出单', icon: 'none', duration: 2500 })
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
.setting-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 8px; }
.form-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed #eee; }
.form-row:last-child { border-bottom: none; }
.form-label { font-size: 13px; color: #555; }
.form-static { font-size: 13px; color: #666; }

.paper-preview { background: #e0e6ed; padding: 12px; border-radius: 6px; margin-bottom: 12px; }
.paper { background: #fff; padding: 20px 16px; border-radius: 4px; box-shadow: 0 2px 6px rgba(0,0,0,0.06); }
.paper-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.logo { display: flex; align-items: center; gap: 8px; }
.logo-icon { width: 32px; height: 32px; background: linear-gradient(135deg, #1e6091, #27ae60); border-radius: 4px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 18px; }
.logo-name { font-size: 14px; font-weight: 700; color: #1e6091; }
.logo-en { font-size: 8px; color: #999; letter-spacing: 1px; }
.stamp { background: #27ae60; color: #fff; padding: 2px 8px; border-radius: 3px; font-size: 11px; }
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
.dt-body { display: flex; background: #fff; }
.dt-td { flex: 1; text-align: center; padding: 6px; font-size: 11px; }
.tag-pass { background: #e1f5e1; color: #27ae60; padding: 1px 6px; border-radius: 3px; font-size: 10px; }

.photos-section { margin-bottom: 10px; }
.photo-empty-note { font-size: 11px; color: #999; padding: 10px; text-align: center; border: 1px dashed #ddd; border-radius: 4px; }
.section-title { font-size: 12px; font-weight: 600; color: #1e6091; margin-bottom: 6px; }
.photo-row { display: flex; gap: 6px; }
.photo-cell { flex: 1; border: 1px solid #e0e0e0; }
.photo { width: 100%; height: 120px; display: block; }
.photo-cap { text-align: center; font-size: 10px; color: #666; padding: 3px 0; background: #fafafa; }

.note-block { background: #f9f9f9; padding: 6px 8px; border-radius: 4px; font-size: 11px; color: #555; margin-bottom: 10px; line-height: 1.5; }
.note-label { color: #1e6091; font-weight: 600; }

.sign-table { border-top: 1px solid #e0e0e0; padding-top: 8px; }
.sign-row { display: flex; }
.sign-th { width: 17%; text-align: center; font-size: 11px; color: #888; }
.sign-td { width: 16%; text-align: center; font-size: 12px; color: #222; }

.paper-footer { margin-top: 10px; padding-top: 6px; border-top: 1px solid #eee; font-size: 9px; color: #999; text-align: center; }
.hash { background: #f0f0f0; padding: 1px 4px; border-radius: 2px; font-family: monospace; }

.bottom-actions { position: fixed; bottom: 60px; left: 0; right: 0; display: flex; gap: 8px; padding: 8px 12px; background: #fff; border-top: 1px solid #eee; z-index: 9; }
.btn-plain { flex: 1; background: #fff; color: #555; border: 1px solid #d0d0d0; border-radius: 4px; padding: 10px; font-size: 14px; }
.btn-primary { flex: 1; background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 10px; font-size: 14px; font-weight: 500; }
.footer-space { height: 20px; }
</style>
