<template>
  <view class="container">
    <view class="xk-strip">
      <view class="strip-bread">销售管理 · 出库</view>
      <view class="strip-title">新建销售出库单</view>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 基本信息 -->
      <view class="card">
        <view class="section-title">基本信息</view>
        <view class="form-row">
          <text class="form-label">客户</text>
          <picker :range="customerNames" @change="onPickCustomer" class="form-pick">
            <view class="pick-val" :class="{ active: form.customerId }">
              {{ pickedCustomer ? pickedCustomer.customerName : '请选择客户' }}
            </view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">出库仓库</text>
          <picker :range="whNames" @change="onPickWh" class="form-pick">
            <view class="pick-val" :class="{ active: form.warehouseId }">{{ whName || '请选择仓库' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">业务日期</text>
          <picker mode="date" :value="form.billDate" @change="onPickDate" class="form-pick">
            <view class="pick-val">{{ form.billDate || '选择日期' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">备注</text>
          <input class="form-input" v-model="form.remark" placeholder="选填" />
        </view>
      </view>

      <!-- 商品明细 -->
      <view class="card">
        <view class="section-title">出库明细</view>
        <!-- 商品搜索 -->
        <view class="search-row">
          <input class="search-input" v-model="kw" placeholder="输入品名/编码搜索商品" @confirm="onSearch" />
          <button class="btn-search" @click="onSearch">搜索</button>
          <button class="btn-scan" @click="onScan">📷</button>
        </view>

        <view v-if="searching" class="list-state">搜索中...</view>
        <view v-else-if="searchResults.length" class="search-box">
          <view v-for="p in searchResults" :key="p.id" class="search-item" @click="onPickProduct(p)">
            <view class="si-main">
              <text class="si-name">{{ p.productName || '—' }}</text>
              <text v-if="p.productCode" class="si-code">{{ p.productCode }}</text>
            </view>
            <text v-if="p.spec" class="si-spec">{{ p.spec }}</text>
          </view>
        </view>

        <!-- 已选明细 -->
        <view v-if="!details.length" class="list-state empty-mini">暂无明细, 请搜索商品添加</view>
        <view v-for="(d, i) in details" :key="i" class="detail-row">
          <view class="dr-head">
            <text class="dr-name">{{ i + 1 }}. {{ d.productName }}</text>
            <text class="dr-del" @click="removeDetail(i)">删除</text>
          </view>
          <view v-if="d.spec" class="dr-spec">{{ d.spec }}</view>
          <view class="dr-inputs">
            <view class="di-item">
              <text class="di-label">数量</text>
              <input class="di-input" type="digit" v-model="d.qty" placeholder="0" />
            </view>
            <view class="di-item">
              <text class="di-label">单价</text>
              <input class="di-input" type="digit" v-model="d.price" placeholder="0.00" />
            </view>
            <view class="di-item">
              <text class="di-label">金额</text>
              <text class="di-amount">{{ lineAmount(d) }}</text>
            </view>
          </view>
        </view>

        <view v-if="details.length" class="total-line">
          <text>合计数量 {{ totalQty }}</text>
          <text class="total-amount">¥ {{ totalAmount }}</text>
        </view>
      </view>

      <view class="foot">
        <button class="btn btn-save" :disabled="submitting" @click="onSubmit(false)">存草稿</button>
        <button class="btn btn-check" :disabled="submitting" @click="onSubmit(true)">
          {{ submitting ? '处理中...' : '保存并审核' }}
        </button>
      </view>
    </template>
  </view>
</template>

<script>
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
}

export default {
  data() {
    return {
      loading: true,
      submitting: false,
      customers: [],
      warehouses: [],
      form: { customerId: null, warehouseId: null, billDate: todayStr(), remark: '' },
      details: [],
      kw: '',
      searching: false,
      searchResults: []
    }
  },
  computed: {
    customerNames() { return this.customers.map((c) => c.customerName || ('客户#' + c.id)) },
    pickedCustomer() { return this.customers.find((c) => String(c.id) === String(this.form.customerId)) },
    whNames() { return this.warehouses.map((w) => w.warehouseName || ('仓#' + w.id)) },
    whName() {
      const w = this.warehouses.find((x) => String(x.id) === String(this.form.warehouseId))
      return w ? (w.warehouseName || ('仓#' + w.id)) : ''
    },
    totalQty() {
      return this.details.reduce((s, d) => s + (Number(d.qty) || 0), 0).toLocaleString()
    },
    totalAmount() {
      const v = this.details.reduce((s, d) => s + (Number(d.qty) || 0) * (Number(d.price) || 0), 0)
      return v.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
    }
  },
  onLoad() { this.loadAll() },
  methods: {
    lineAmount(d) {
      return ((Number(d.qty) || 0) * (Number(d.price) || 0)).toFixed(2)
    },
    async loadAll() {
      try {
        const [c, w] = await Promise.all([api.customerList(), api.warehouseList()])
        this.customers = (c && (c.data || c)) || []
        this.warehouses = (w && (w.data || w)) || []
        // 默认第一个成品仓 (warehouseType=FINISHED/FG), 回退第一个
        const fin = this.warehouses.find((x) => ['FINISHED', 'FG', 'FINISHED_GD'].includes(x.warehouseType)) || this.warehouses[0]
        if (fin) this.form.warehouseId = String(fin.id)
      } catch (e) {
        if (!(e && e.code === 401)) console.warn('[sales-delivery-add.loadAll] 失败:', e)
      } finally {
        this.loading = false
      }
    },
    onPickCustomer(e) {
      const c = this.customers[Number(e.detail.value)]
      if (c) this.form.customerId = String(c.id)
    },
    onPickWh(e) {
      const w = this.warehouses[Number(e.detail.value)]
      if (w) this.form.warehouseId = String(w.id)
    },
    onPickDate(e) { this.form.billDate = e.detail.value },
    onSearch() {
      const kw = this.kw.trim()
      if (!kw) { this.searchResults = []; return }
      this.searching = true
      api.productAppSearch(kw).then((r) => {
        const list = (r && (r.data || r)) || []
        this.searchResults = Array.isArray(list) ? list.slice(0, 30) : []
      }).catch((e) => {
        if (!(e && e.code === 401)) console.warn('[sales-delivery-add.search] 失败:', e)
        this.searchResults = []
      }).finally(() => { this.searching = false })
    },
    // 扫码加商品 (扫商品条码/编码)
    onScan() {
      doScan({
        onResult: (code) => {
          const v = String(code || '').trim()
          if (!v) return
          this.kw = v
          this.onSearch()
        },
        onCancel: () => {}
      })
    },
    onPickProduct(p) {
      // 已在明细里 → 数量 +1
      const exist = this.details.find((d) => String(d.productId) === String(p.id))
      if (exist) {
        exist.qty = String((Number(exist.qty) || 0) + 1)
      } else {
        this.details.push({
          productId: p.id,
          productCode: p.productCode || '',
          productName: p.productName || '',
          spec: p.spec || '',
          unitId: p.unitId,
          unitName: p.unitName || '',
          qty: '1',
          // v1.1.99 商品表有售价字段则带出, 否则留 0 由用户填
          price: p.salePrice != null ? String(p.salePrice) : (p.price != null ? String(p.price) : '0')
        })
      }
      this.searchResults = []
      this.kw = ''
    },
    removeDetail(i) { this.details.splice(i, 1) },
    async onSubmit(alsoCheck) {
      if (!this.form.customerId) { uni.showToast({ title: '请先选择客户', icon: 'none' }); return }
      if (!this.form.warehouseId) { uni.showToast({ title: '请选择仓库', icon: 'none' }); return }
      if (!this.details.length) { uni.showToast({ title: '请添加出库明细', icon: 'none' }); return }
      // 本地先校验 (后端也会校验, 这里提前拦省一次往返)
      for (let i = 0; i < this.details.length; i++) {
        const q = Number(this.details[i].qty)
        const pr = Number(this.details[i].price)
        if (!(q > 0)) { uni.showToast({ title: '第 ' + (i + 1) + ' 行数量必须大于 0', icon: 'none' }); return }
        if (!(pr >= 0)) { uni.showToast({ title: '第 ' + (i + 1) + ' 行单价不能为负', icon: 'none' }); return }
      }
      this.submitting = true
      if (uni.showLoading) uni.showLoading({ title: '提交中...', mask: true })
      try {
        const payload = {
          customerId: Number(this.form.customerId),
          warehouseId: Number(this.form.warehouseId),
          billDate: this.form.billDate || undefined,
          remark: this.form.remark || 'App 新建销售出库',
          details: this.details.map((d) => ({
            productId: d.productId,
            productCode: d.productCode || undefined,
            productName: d.productName || undefined,
            spec: d.spec || undefined,
            unitId: d.unitId || undefined,
            unitName: d.unitName || undefined,
            qty: Number(d.qty),
            price: Number(d.price)
          }))
        }
        await api.salesDeliveryAdd(payload)
        // add 只返回 R<Void> 不带 id, 回查最新一单拿 id 再审核
        let newId = null
        if (alsoCheck) {
          const r = await api.salesDeliveryPage({ pageNum: 1, pageSize: 1, customerId: payload.customerId })
          const recs = (r && r.records) || []
          if (recs.length) newId = recs[0].id
        }
        if (alsoCheck && newId) {
          await api.salesDeliveryCheck(newId)
          if (uni.hideLoading) uni.hideLoading()
          uni.showToast({ title: '已出库', icon: 'success' })
          setTimeout(() => uni.navigateBack(), 800)
        } else {
          if (uni.hideLoading) uni.hideLoading()
          uni.showToast({ title: '草稿已保存', icon: 'success' })
          setTimeout(() => uni.navigateBack(), 800)
        }
      } catch (e) {
        if (uni.hideLoading) uni.hideLoading()
        // v1.1.61 R13: 拦截器已弹后端 msg, 这里只兜底非后端错误
        if (e && e.code === 401) { this.submitting = false; return }
        if (!(e && e.msg)) uni.showToast({ title: '提交失败', icon: 'none' })
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
.form-row { display: flex; align-items: center; margin-bottom: 8px; }
.form-label { width: 68px; font-size: 13px; color: #606266; }
.form-input { flex: 1; height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; box-sizing: border-box; }
.form-pick { flex: 1; }
.pick-val { height: 34px; line-height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; background: #fafafa; }
.pick-val.active { border-color: #1e6091; background: #ecf5fb; }
.search-row { display: flex; gap: 6px; align-items: center; }
.search-input { flex: 1; height: 34px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 14px; box-sizing: border-box; }
.btn-search { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 0 12px; height: 34px; font-size: 13px; }
.btn-scan { background: #ecf5fb; color: #1e6091; border: 1px solid #cfe3f3; border-radius: 4px; padding: 0 10px; height: 34px; font-size: 15px; }
.search-box { margin-top: 8px; border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; }
.search-item { padding: 9px 10px; border-bottom: 1px solid #f0f0f0; }
.search-item:active { background: #f5f7fa; }
.si-main { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.si-name { font-size: 14px; color: #303133; font-weight: 500; }
.si-code { font-size: 11px; color: #909399; }
.si-spec { font-size: 12px; color: #606266; margin-top: 2px; }
.detail-row { border: 1px solid #f0f0f0; border-radius: 8px; padding: 10px; margin-top: 8px; }
.dr-head { display: flex; justify-content: space-between; align-items: center; }
.dr-name { font-size: 14px; font-weight: 600; color: #303133; }
.dr-del { font-size: 12px; color: #f56c6c; padding: 2px 6px; }
.dr-spec { font-size: 12px; color: #909399; margin-top: 2px; }
.dr-inputs { display: flex; gap: 8px; margin-top: 8px; }
.di-item { flex: 1; }
.di-label { font-size: 11px; color: #999; display: block; }
.di-input { width: 100%; height: 32px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 13px; box-sizing: border-box; margin-top: 2px; }
.di-amount { display: block; font-size: 13px; color: #1e6091; font-weight: 600; margin-top: 15px; }
.total-line { display: flex; justify-content: space-between; margin-top: 10px; padding-top: 10px; border-top: 1px solid #f0f0f0; font-size: 13px; color: #606266; }
.total-amount { color: #1e6091; font-weight: bold; font-size: 15px; }
.list-state { text-align: center; color: #999; padding: 16px; font-size: 13px; }
.empty-mini { padding: 20px; }
.empty { text-align: center; color: #999; padding: 60px; font-size: 14px; }
.foot { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; box-shadow: 0 -2px 8px rgba(0,0,0,.06); display: flex; gap: 8px; }
.btn { flex: 1; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-save { background: #ecf5fb; color: #1e6091; }
.btn-check { background: #1e6091; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>