<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / 仓管 · 成品入库</view>
      <view class="strip-title">成品入库</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 成品入库列表 (对齐 PC 端 FinishedIn.vue) -->
      <view class="card">
        <view class="section-title">成品入库列表</view>
        <view v-if="listLoading" class="list-state">加载中...</view>
        <view v-else-if="!inRecords.length" class="list-state empty-mini">暂无成品入库记录</view>
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
            <!-- 列表行内审核/反审核 (DRAFT 绿, CHECKED 橙) -->
            <view v-if="canCheck || canUncheck" class="in-actions">
              <button v-if="r.billStatus !== 'CHECKED' && canCheck" class="row-btn row-btn-ok" :disabled="rowActing" @click="onRowCheck(r)">审核</button>
              <button v-else-if="r.billStatus === 'CHECKED' && canUncheck" class="row-btn row-btn-warn" :disabled="rowActing" @click="onRowUncheck(r)">反审核</button>
            </view>
          </view>
          <button class="btn-reload" @click="loadInList">刷新列表</button>
        </template>
      </view>

      <!-- 选生产工单 → 带出 成品/规格/数量/仓库 -->
      <view class="card">
        <view class="section-title">关联生产工单</view>
        <!-- v1.1.111: 扫码关联 (扫工单条码/单号二维码 → 自动带入) -->
        <button class="scan-btn" @click="onScanOrder">📷 扫码关联工单</button>
        <picker :range="orderOptions" @change="onPickOrder">
          <view class="picker-box" :class="{ active: orderId }">
            <text v-if="pickedOrder">{{ pickedOrder.billNo }} · {{ pickedOrder.productName || '成品' }}</text>
            <text v-else-if="orderList.length">请选择生产工单</text>
            <text v-else class="muted">暂无生产工单</text>
          </view>
        </picker>
        <view v-if="pickedOrder" class="info-grid">
          <view class="info-item"><text class="il">成品</text><text class="iv">{{ pickedOrder.productName || '-' }}</text></view>
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
        <!-- v1.1.117: 单位选择器, 默认带出该成品的主单位 (真实单位名), 可切该商品其它单位 -->
        <view class="form-row"><text class="form-label">单位</text>
          <picker :range="unitNames" @change="onPickUnit" class="form-pick">
            <view class="pick-val">{{ unitTip }}</view></picker></view>
        <view class="form-row"><text class="form-label">数量</text>
          <input class="form-input" v-model="form.qty" type="digit" placeholder="0" /></view>
        <view class="form-row"><text class="form-label">批号</text>
          <input class="form-input" v-model="form.batchNo" placeholder="默认=工单号, 可改" /></view>
      </view>

      <!-- XK 门禁提示 -->
      <view class="card gate-card">
        <view class="gate-title">🔒 XK 门禁</view>
        <view class="muted">食品级成品须: ① 该工单全部工序已完工 (工序门禁); ② 若已关联 OQC 成品检, 须已签合格 (软门禁)。
          工业品商品免检, 可直接入库。</view>
      </view>

      <view class="foot">
        <button class="btn btn-save" :disabled="!orderId || submitting || draftId" @click="onSaveDraft">保存草稿</button>
        <button class="btn btn-check" :disabled="!draftId || submitting" @click="onCheck">
          {{ submitting ? '处理中...' : '审核入库' }}
        </button>
      </view>

      <view v-if="checked" class="done-tip">✓ 成品已入库</view>
    </template>
  </view>
</template>

<script>
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'

export default {
  data() {
    return {
      loading: true,
      orderList: [],
      warehouses: [],
      orderId: null,
      warehouseId: null,
      // v1.1.117: 单位选择器 (默认工单主单位, 可切该商品其它单位)
      units: [],
      unitIdx: 0,
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
    orderOptions() { return this.orderList.map((x) => x.billNo || ('#' + x.id)) },
    pickedOrder() { return this.orderList.find((x) => String(x.id) === String(this.orderId)) },
    whNames() { return this.warehouses.map((w) => w.warehouseName || ('仓#' + w.id)) },
    whName() { const w = this.warehouses.find((x) => String(x.id) === String(this.warehouseId)); return w ? (w.warehouseName || ('仓#' + w.id)) : '' },
    // v1.1.117: 单位下拉 — 默认商品主单位 (真实单位名, 如「只」/「卷」); 该商品没配单位时显示「未配置」
    unitNames() { return this.units.map((u) => u.unitName) },
    unitName() { return this.units[this.unitIdx] ? this.units[this.unitIdx].unitName : '' },
    unitTip() { return this.unitName || (this.pickedOrder ? '该商品未配置单位' : '请选择单位') },
    // v1.1.102 hotfix-3: 列表行内审核/反审核 perm
    canCheck() {
      const perms = this.getMyAppPerms()
      return perms.includes('production:finished-in:check') || perms.includes('admin') || this.isMyAdmin()
    },
    canUncheck() {
      const perms = this.getMyAppPerms()
      return perms.includes('production:finished-in:uncheck') || perms.includes('admin') || this.isMyAdmin()
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
    // 列表行内审核 (走 prdFinishedInCheck 端点, 与底部「审核入库」同后端)
    async onRowCheck(row) {
      const ok = await new Promise((resolve) => {
        uni.showModal({ title: '审核确认', content: `确认审核入库单 ${row.billNo}? 须工序全完工, 食品级须 OQC 合格。`, confirmText: '确认审核', cancelText: '取消',
          success: (r) => resolve(r.confirm), fail: () => resolve(false) })
      })
      if (!ok) return
      this.rowActing = true
      try {
        await api.prdFinishedInCheck(row.id)
        uni.showToast({ title: '审核成功', icon: 'success' })
        this.loadInList()
      } catch (e) {
        if (e && e.code === 401) { this.rowActing = false; return }
        uni.showModal({ title: '审核失败', content: (e && e.msg) || '工序未完工或 OQC 未放行, 无法入库', showCancel: false })
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
        await api.prdFinishedInUncheck(row.id)
        uni.showToast({ title: '已反审核', icon: 'success' })
        this.loadInList()
      } catch (e) {
        if (e && e.code === 401) { this.rowActing = false; return }
        uni.showModal({ title: '反审核失败', content: (e && e.msg) || '反审核失败', showCancel: false })
      } finally { this.rowActing = false }
    },
    // 成品入库列表 (对齐 PC 端, 只显示最近 50 条)
    async loadInList() {
      this.listLoading = true
      try {
        const r = await api.prdFinishedInPage({ pageNum: 1, pageSize: 50 })
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
        // 默认选第一个 成品仓 (warehouseType=FINISHED/FG, 无则回退第一个)
        const fin = this.warehouses.find((x) => ['FINISHED', 'FG', 'FINISHED_GD'].includes(x.warehouseType)) || this.warehouses[0]
        if (fin) this.warehouseId = String(fin.id)
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
    // v1.1.111: 扫码关联工单 — 扫工单条码/单号二维码, 后端按 billNo LIKE 查到即带入
    async onScanOrder() {
      doScan({
        onResult: async (code) => {
          const billNo = String(code || '').trim()
          if (!billNo) return
          // 1) 先在本地列表里找 (picker 已加载的前 100 条), 命中最快
          const local = this.orderList.find((o) => o.billNo === billNo)
          if (local) {
            this.orderId = String(local.id); this.onOrderPicked()
            uni.showToast({ title: '已关联 ' + billNo, icon: 'success' })
            return
          }
          // 2) 本地没命中 → 按 billNo 精确查后端
          try {
            const r = await api.prdOrderPage({ pageNum: 1, pageSize: 1, billNo })
            const recs = (r && r.records) || (r && r.data && r.data.records) || []
            if (recs.length) {
              this.orderList.push(recs[0])
              this.orderId = String(recs[0].id); this.onOrderPicked()
              uni.showToast({ title: '已关联 ' + (recs[0].billNo || billNo), icon: 'success' })
            } else {
              uni.showModal({ title: '未找到工单', content: '没有单号为 ' + billNo + ' 的生产工单', showCancel: false })
            }
          } catch (e) {
            if (e && e.code === 401) return
            uni.showModal({ title: '查询失败', content: (e && e.msg) || '按单号查工单失败', showCancel: false })
          }
        },
        onCancel: () => {}
      })
    },
    onOrderPicked() {
      const o = this.pickedOrder
      if (!o) return
      this.form.qty = String(o.actualQty || o.planQty || 0)
      // v1.1.117: 批号默认 = 工单号 (可改)
      this.form.batchNo = o.billNo || ''
      this.draftId = null; this.checked = false
      this.loadUnits(o)
    },
    // v1.1.117: 加载该成品可用单位, 默认选主单位.
    // ⚠️ prd_order.unit_id/unit_name 很多老工单是 NULL (开单时没写单位),
    //    prdOrderPage 接口也不带单位 → 工单上的单位经常拿不到, 不能拿它当默认,
    //    否则界面会显示兜底的「主单位」三个字而不是真实单位名 (如「只」/「卷」).
    //    以 base_product_unit 的真实主单位为准, 工单单位仅在商品无单位表时才兜底.
    async loadUnits(o) {
      this.units = []
      this.unitIdx = 0
      let loaded = false
      if (o.productId) {
        try {
          const r = await api.productDetail(o.productId)
          const pd = (r && r.data) || {}
          const list = (pd.units || []).filter((u) => u.unitId && u.unitName)
          if (list.length) {
            // 主单位排最前 (后端 isMain 标注); 工单指定的单位若也在列表里, 优先选中它
            const main = list.find((u) => u.isMain)
              || list.find((u) => String(u.unitId) === String(o.unitId))
              || list[0]
            const others = list.filter((u) => String(u.unitId) !== String(main.unitId))
            this.units = [main].concat(others)
            const idx = this.units.findIndex((u) => String(u.unitId) === String(o.unitId))
            this.unitIdx = idx >= 0 ? idx : 0
            loaded = true
          }
        } catch (e) {
          console.warn('[finished-in.loadUnits] 失败:', e)
        }
      }
      // 兜底: 商品没配单位表时用工单上的单位 (仍无则留空, 界面显示「请选择单位」)
      if (!loaded && (o.unitId || o.unitName)) {
        this.units = [{ unitId: o.unitId, unitName: o.unitName || '' }]
        this.unitIdx = 0
      }
    },
    onPickUnit(e) { this.unitIdx = Number(e.detail.value) },
    onPickWh(e) { this.warehouseId = String(this.warehouses[Number(e.detail.value)].id) },
    async onSaveDraft() {
      const o = this.pickedOrder
      if (!o) { uni.showToast({ title: '请先选生产工单', icon: 'none' }); return }
      if (!this.warehouseId) { uni.showToast({ title: '请选择仓库', icon: 'none' }); return }
      this.submitting = true
      try {
        // v1.1.117: 带上选中的单位 (默认成品主单位) + 批号 (默认=工单号)
        const u = this.units[this.unitIdx] || {}
        await api.prdFinishedInAdd({
          prdOrderId: this.orderId,
          prdOrderNo: o.billNo,
          productId: o.productId, productCode: o.productCode, productName: o.productName,
          spec: o.spec,
          unitId: u.unitId != null ? u.unitId : o.unitId,
          unitName: u.unitName || o.unitName,
          qty: this.form.qty || 0, batchNo: this.form.batchNo || undefined,
          warehouseId: this.warehouseId, remark: 'App 成品入库'
        })
        const r = await api.prdFinishedInPage({ pageNum: 1, pageSize: 5, billNo: o.billNo })
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
        await api.prdFinishedInCheck(this.draftId)
        this.checked = true
        uni.showToast({ title: '成品已入库', icon: 'success' })
        this.loadInList()
      } catch (e) {
        console.warn('[finished-in.check] 门禁拦截:', e)
        if (e && e.code === 401) { this.submitting = false; return }
        uni.showModal({ title: '审核失败', content: (e && e.msg) || '工序未完工或 OQC 未放行, 无法入库', showCancel: false })
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
.scan-btn { width: 100%; height: 40px; line-height: 40px; background: #ecf5fb; color: #1e6091; border: 1px solid #cfe3f3; border-radius: 6px; font-size: 14px; font-weight: 600; margin-bottom: 10px; }
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
.btn-check { background: #1e6091; color: #fff; }
.btn[disabled] { opacity: .5; }
.done-tip { text-align: center; color: #67c23a; font-size: 13px; font-weight: 600; padding: 8px 0; }
</style>
