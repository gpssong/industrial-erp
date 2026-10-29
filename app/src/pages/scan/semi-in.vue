<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 仓管 · 半成品入库</view>
      <view class="strip-title">半成品入库</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 半成品入库列表 (对齐 PC 端 SemiFinishedIn) -->
      <view class="card">
        <view class="section-title">半成品入库列表</view>
        <view v-if="listLoading" class="list-state">加载中...</view>
        <view v-else-if="!inRecords.length" class="list-state empty-mini">暂无半成品入库记录</view>
        <template v-else>
          <view v-for="r in inRecords" :key="r.id" class="in-row">
            <view class="in-main">
              <text class="in-no">{{ r.billNo || '-' }}</text>
              <text class="in-st" :class="r.billStatus === 'CHECKED' ? 'ok' : 'draft'">
                {{ r.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
              </text>
            </view>
            <view class="in-sub">
              <text>{{ r.productName || '-' }}</text>
              <text v-if="r.spec"> · {{ r.spec }}</text>
            </view>
            <view class="in-sub">
              <text>数量: {{ r.qty || 0 }}</text>
              <text v-if="r.unitName"> {{ r.unitName }}</text>
              <text v-if="r.batchNo"> · 批号 {{ r.batchNo }}</text>
            </view>
            <!-- 列表行内审核/反审核 (DRAFT 显绿, CHECKED 显橙) -->
            <view v-if="canCheck || canUncheck" class="in-actions">
              <button v-if="r.billStatus !== 'CHECKED' && canCheck" class="row-btn row-btn-ok" :disabled="rowActing" @click="onRowCheck(r)">审核</button>
              <button v-else-if="r.billStatus === 'CHECKED' && canUncheck" class="row-btn row-btn-warn" :disabled="rowActing" @click="onRowUncheck(r)">反审核</button>
            </view>
          </view>
          <button class="btn-reload" @click="loadInList">刷新列表</button>
        </template>
      </view>

      <!-- 选生产工单 → 带出 半成品/规格/数量/仓库 -->
      <view class="card">
        <view class="section-title">关联生产工单</view>
        <picker :range="orderOptions" @change="onPickOrder">
          <view class="picker-box" :class="{ active: orderId }">
            <text v-if="pickedOrder">{{ pickedOrder.billNo }} · {{ pickedOrder.productName || '半成品' }}</text>
            <text v-else-if="orderList.length">请选择生产工单</text>
            <text v-else class="muted">暂无生产工单</text>
          </view>
        </picker>
        <view v-if="pickedOrder" class="info-grid">
          <view class="info-item"><text class="il">半成品</text><text class="iv">{{ pickedOrder.productName || '-' }}</text></view>
          <view class="info-item"><text class="il">规格</text><text class="iv">{{ pickedOrder.spec || '-' }}</text></view>
          <view class="info-item"><text class="il">计划量</text><text class="iv">{{ pickedOrder.planQty || 0 }}</text></view>
        </view>
      </view>

      <!-- 入库信息 -->
      <view class="card">
        <view class="section-title">入库信息</view>
        <view class="form-row"><text class="form-label">入库仓库</text>
          <picker :range="whNames" @change="onPickWh" class="form-pick">
            <view class="pick-val">{{ whName || '请选择仓库' }}</view></picker></view>
        <view class="form-row"><text class="form-label">数量</text>
          <input class="form-input" v-model="form.qty" type="digit" placeholder="0" /></view>
        <view class="form-row"><text class="form-label">批号</text>
          <input class="form-input" v-model="form.batchNo" placeholder="留空自动生成" /></view>
      </view>

      <!-- XK 门禁提示 -->
      <view class="card gate-card">
        <view class="gate-title">🔒 XK 门禁</view>
        <view class="muted">食品级原料须已关联合格 FAI 首检单 (检验员先在「首检」页签合格, 再回此审核)；工业品商品免检, 可直接入库。</view>
      </view>

      <!-- 固定底部双按钮 -->
      <view class="foot">
        <button class="btn btn-save" :disabled="!orderId || submitting || draftId" @click="onSaveDraft">保存草稿</button>
        <button class="btn btn-check" :disabled="!draftId || submitting" @click="onCheck">
          {{ submitting ? '处理中...' : '审核入库' }}
        </button>
      </view>

      <view v-if="checked" class="done-tip">✓ 半成品已入库</view>
    </template>
  </view>
</template>

<script>
import api from '../../api/index.js'

export default {
  data() {
    return {
      loading: true,
      orderList: [],
      warehouses: [],
      orderId: null,
      warehouseId: null,
      form: { qty: '', batchNo: '' },
      submitting: false,
      draftId: null,
      draftNo: null,
      checked: false,
      inRecords: [],
      listLoading: false,
      rowActing: false
    }
  },
  computed: {
    // v1.1.131: 单号后带商品名称, 选单时不用再回查是哪个工单
    orderOptions() {
      return this.orderList.map((x) => {
        const no = x.billNo || ('#' + x.id)
        const name = x.productName || x.productCode || ''
        return name ? no + ' · ' + name : no
      })
    },
    pickedOrder() { return this.orderList.find((x) => String(x.id) === String(this.orderId)) },
    whNames() { return this.warehouses.map((w) => w.warehouseName || ('仓#' + w.id)) },
    whName() { const w = this.warehouses.find((x) => String(x.id) === String(this.warehouseId)); return w ? (w.warehouseName || ('仓#' + w.id)) : '' },
    // v1.1.102 hotfix-3: 列表行内审核/反审核 perm (PC 端 SemiFinishedIn.vue 门禁一致)
    canCheck() {
      const perms = this.getMyAppPerms()
      return perms.includes('production:semi-in:check') || perms.includes('admin') || this.isMyAdmin()
    },
    canUncheck() {
      const perms = this.getMyAppPerms()
      return perms.includes('production:semi-in:uncheck') || perms.includes('admin') || this.isMyAdmin()
    }
  },
  onLoad(query) {
    this.loadAll()
    this.loadInList()
    if (query && query.orderId) { this.orderId = String(query.orderId); this.onOrderPicked() }
  },
  methods: {
    // v1.1.102 hotfix-3: 读 App 端 perm 集合 (erp_menus 派生, 兼容 erp_permissions 兜底)
    getMyAppPerms() {
      try {
        const menusRaw = uni.getStorageSync('erp_menus')
        const menus = typeof menusRaw === 'string' ? JSON.parse(menusRaw || '[]') : (Array.isArray(menusRaw) ? menusRaw : [])
        if (menus.length) {
          const set = new Set()
          for (const m of menus) {
            if (!m.perms) continue
            for (const p of String(m.perms).split(',')) {
              const t = p.trim(); if (t) set.add(t)
            }
          }
          if (set.size) return Array.from(set)
        }
        const raw = uni.getStorageSync('erp_permissions')
        if (typeof raw === 'string') return JSON.parse(raw || '[]')
        if (Array.isArray(raw)) return raw
      } catch (e) {}
      return []
    },
    isMyAdmin() {
      try {
        const raw = uni.getStorageSync('erp_user')
        const u = typeof raw === 'object' ? raw : JSON.parse(raw || '{}')
        if (!u) return false
        if (u.userId === 1 || u.userId === '1' || u.userId === 0 || u.userId === '0') return true
        if (u.isAdmin === 1 || u.isAdmin === true) return true
        return (u.roles || []).includes('SUPER_ADMIN')
      } catch (e) { return false }
    },
    // 列表行内审核 (走 prdSemiInCheck 端点, 与底部「审核入库」同后端)
    async onRowCheck(row) {
      const ok = await new Promise((resolve) => {
        uni.showModal({ title: '审核确认', content: `确认审核入库单 ${row.billNo}? 须已关联合格 FAI 检验单。`, confirmText: '确认审核', cancelText: '取消',
          success: (r) => resolve(r.confirm), fail: () => resolve(false) })
      })
      if (!ok) return
      this.rowActing = true
      try {
        await api.prdSemiInCheck(row.id)
        uni.showToast({ title: '审核成功', icon: 'success' })
        this.loadInList()
      } catch (e) {
        if (e && e.code === 401) { this.rowActing = false; return }
        uni.showModal({ title: '审核失败', content: (e && e.msg) || '请先完成合格 FAI 首检', showCancel: false })
      } finally { this.rowActing = false }
    },
    // 列表行内反审核
    async onRowUncheck(row) {
      const ok = await new Promise((resolve) => {
        uni.showModal({ title: '反审核确认', content: `确认反审核入库单 ${row.billNo}? 将回退入库库存并回到草稿。`, confirmText: '确认反审核', cancelText: '取消',
          success: (r) => resolve(r.confirm), fail: () => resolve(false) })
      })
      if (!ok) return
      this.rowActing = true
      try {
        await api.prdSemiInUncheck(row.id)
        uni.showToast({ title: '已反审核', icon: 'success' })
        this.loadInList()
      } catch (e) {
        if (e && e.code === 401) { this.rowActing = false; return }
        uni.showModal({ title: '反审核失败', content: (e && e.msg) || '反审核失败', showCancel: false })
      } finally { this.rowActing = false }
    },
    // 半成品入库列表 (对齐 PC 端, 只显示最近 50 条)
    async loadInList() {
      this.listLoading = true
      try {
        const r = await api.prdSemiInPage({ pageNum: 1, pageSize: 50 })
        this.inRecords = (r && r.records) || (r && r.data && r.data.records) || []
      } catch (e) {
        this.inRecords = []
      } finally { this.listLoading = false }
    },
    async loadAll() {
      try {
        const [o, w] = await Promise.all([
          api.prdOrderPage({ pageNum: 1, pageSize: 100 }),
          api.warehouseList()
        ])
        this.orderList = (o && o.records) || (o && o.data && o.data.records) || []
        this.warehouses = (w && (w.data || w)) || []
        this.loading = false
        // 默认选半成品仓 (warehouseType=SEMI); 老库没分类过则按仓名兜底
        // (warehouses[0] 往往是成品仓, 直接退到它会静默把半成品入到成品仓)
        const semi = this.warehouses.find((x) => x.warehouseType === 'SEMI')
          || this.warehouses.find((x) => (x.warehouseName || '').includes('半成品'))
          || this.warehouses[0]
        if (semi) this.warehouseId = String(semi.id)
      } catch (e) {
        this.loading = false
        if (e && e.code === 401) return
        uni.showToast({ title: '加载失败', icon: 'none' })
      }
    },
    onPickOrder(e) {
      const item = this.orderList[Number(e.detail.value)]
      if (item) { this.orderId = String(item.id); this.onOrderPicked() }
    },
    onOrderPicked() {
      const o = this.pickedOrder
      if (!o) return
      this.form.qty = String(o.planQty || o.actualQty || 0)
      this.draftId = null; this.checked = false
    },
    onPickWh(e) { this.warehouseId = String(this.warehouses[Number(e.detail.value)].id) },
    async onSaveDraft() {
      const o = this.pickedOrder
      if (!o) { uni.showToast({ title: '请先选生产工单', icon: 'none' }); return }
      if (!this.warehouseId) { uni.showToast({ title: '请选择仓库', icon: 'none' }); return }
      this.submitting = true
      try {
        await api.prdSemiInAdd({
          prdOrderId: this.orderId,
          prdOrderNo: o.billNo,
          productId: o.productId, productCode: o.productCode, productName: o.productName,
          spec: o.spec, unitId: o.unitId, unitName: o.unitName,
          qty: this.form.qty || 0, batchNo: this.form.batchNo || undefined,
          warehouseId: this.warehouseId, remark: 'App 半成品入库'
        })
        // 拉刚建的草稿 (按工单号查最新)
        const r = await api.prdSemiInPage({ pageNum: 1, pageSize: 5, billNo: o.billNo })
        const recs = (r && r.records) || (r && r.data && r.data.records) || []
        const d = recs.find((x) => x.billStatus === 'DRAFT') || recs[0]
        if (d) { this.draftId = String(d.id); this.draftNo = d.billNo }
        uni.showToast({ title: '草稿已保存 ' + (this.draftNo || ''), icon: 'success' })
        this.loadInList()
      } catch (e) {
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none' })
      } finally { this.submitting = false }
    },
    async onCheck() {
      if (!this.draftId) { uni.showToast({ title: '请先保存草稿', icon: 'none' }); return }
      this.submitting = true
      try {
        await api.prdSemiInCheck(this.draftId)
        this.checked = true
        uni.showToast({ title: '半成品已入库', icon: 'success' })
        this.loadInList()
      } catch (e) {
        console.warn('[semi-in.check] 门禁拦截:', e)
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showModal({ title: '审核失败', content: (e && e.msg) || '请先完成合格 FAI 首检', showCancel: false })
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
.picker-box { height: 40px; line-height: 40px; border: 1px solid #dcdfe6; border-radius: 6px; padding: 0 10px; font-size: 14px; color: #303133; }
.picker-box.active { border-color: #1e6091; background: #ecf5fb; }
.info-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 10px; padding-top: 10px; border-top: 1px solid #f0f0f0; }
.info-item { display: flex; flex-direction: column; }
.il { font-size: 11px; color: #999; }
.iv { font-size: 13px; color: #303133; margin-top: 2px; }
.form-row { display: flex; align-items: center; margin-bottom: 8px; }
.form-label { width: 64px; font-size: 13px; color: #606266; }
.form-input { flex: 1; height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; box-sizing: border-box; }
.form-pick { flex: 1; }
.pick-val { height: 34px; line-height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; background: #fafafa; }
.gate-card { background: #fff7e6; border: 1px solid #faecd8; }
.gate-title { font-size: 14px; font-weight: 600; color: #e6a23c; margin-bottom: 6px; }
.list-state { text-align: center; color: #999; padding: 20px; font-size: 13px; }
.empty-mini { padding: 20px; }
.in-row { border: 1px solid #f0f0f0; border-radius: 8px; padding: 10px; margin-bottom: 8px; }
.in-main { display: flex; align-items: center; justify-content: space-between; }
.in-no { font-size: 14px; font-weight: 600; color: #303133; }
.in-st { font-size: 11px; padding: 2px 8px; border-radius: 4px; }
.in-st.ok { background: #f0f9eb; color: #67c23a; }
.in-st.draft { background: #f4f4f5; color: #909399; }
.in-sub { font-size: 12px; color: #606266; margin-top: 4px; }
.in-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.row-btn { font-size: 12px; line-height: 26px; height: 26px; padding: 0 12px; border: none; border-radius: 4px; }
.row-btn-ok { background: #67c23a; color: #fff; }
.row-btn-warn { background: #e6a23c; color: #fff; }
.btn-reload { margin-top: 6px; width: 100%; background: #fff; border: 1px solid #dcdfe6; color: #606266; font-size: 13px; border-radius: 6px; }
.empty { text-align: center; color: #999; padding: 60px; font-size: 14px; }
.foot { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; box-shadow: 0 -2px 8px rgba(0,0,0,.06); display: flex; gap: 8px; }
.btn { flex: 1; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-save { background: #ecf5fb; color: #1e6091; }
.btn-check { background: #67c23a; color: #fff; }
.btn[disabled] { opacity: .5; }
.done-tip { text-align: center; color: #67c23a; font-size: 13px; font-weight: 600; padding: 8px 0; }
</style>
