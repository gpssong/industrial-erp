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
        <view class="paper-meta">唯一编号: RK-20250618-0086 · 生成时间: 2025-06-18 14:26:32 · 操作人: 张伟</view>

        <view class="paper-table">
          <view class="pt-row">
            <text class="pt-th">供应商</text><text class="pt-td">中石化华东</text>
            <text class="pt-th">入库日期</text><text class="pt-td">2025-06-18</text>
          </view>
          <view class="pt-row">
            <text class="pt-th">仓库</text><text class="pt-td">原料一库</text>
            <text class="pt-th">制单人</text><text class="pt-td">张伟</text>
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
          <view class="dt-body">
            <text class="dt-td">LLDPE 7042</text>
            <text class="dt-td">RM20250618-031</text>
            <text class="dt-td">12,800 kg</text>
            <text class="dt-td">A-03-12</text>
            <text class="dt-td"><text class="tag-pass">合格</text></text>
          </view>
        </view>

        <view class="photos-section">
          <view class="section-title">📷 原料标签及到货照片</view>
          <view class="photo-row">
            <view class="photo-cell">
              <image src="https://images.unsplash.com/photo-1586528116311-ad8dd3c8310d?w=400" mode="aspectFill" class="photo" />
              <view class="photo-cap">包装标签正面</view>
            </view>
            <view class="photo-cell">
              <image src="https://images.unsplash.com/photo-1565514020179-026b92b84bb6?w=400" mode="aspectFill" class="photo" />
              <view class="photo-cap">到货外观</view>
            </view>
          </view>
        </view>

        <view class="note-block">
          <text class="note-label">检验备注: </text>包装完整无破损, 标签批次与送货单一致, 抽检指标符合入库标准。
        </view>

        <view class="sign-table">
          <view class="sign-row">
            <text class="sign-th">仓库员</text><text class="sign-td">张伟</text>
            <text class="sign-th">质量 QA</text><text class="sign-td">李敏</text>
            <text class="sign-th">审核人</text><text class="sign-td">陈军</text>
          </view>
        </view>

        <view class="paper-footer">
          <text>华塑包装制造有限公司 · 系统生成 · 校验值 SHA256: <text class="hash">4FEB…92A1</text> · 不可篡改</text>
        </view>
      </view>
    </view>

    <view class="bottom-actions">
      <button class="btn-plain">下载 PDF</button>
      <button class="btn-primary" @click="onPrint">🖨 打印单据</button>
    </view>
    <view class="footer-space"></view>
  </view>
</template>

<script>
export default {
  methods: {
    onPrint() { uni.showToast({ title: '已发送到打印机 (mock)', icon: 'success' }) }
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
