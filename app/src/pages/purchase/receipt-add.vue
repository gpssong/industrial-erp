<template>
  <view class="container">
    <view class="xk-strip nf-strip">
      <view class="strip-bread">供应链 / 采购入库</view>
      <view class="strip-title">非食品采购 · 免检直通</view>
    </view>

    <!-- 免检直通说明条 (对比原料入库的 XK 受控条) -->
    <view class="nf-note">
      <text class="nf-note-ico">✓</text>
      <text class="nf-note-txt">本单走「免检直通」: 不需 IQC 来料检验, 不受供应商合格名录限制。仅限**非食品级**商品 (食品接触原料请用「原料入库 · XK受控」流程)。</text>
    </view>

    <view v-if="loading" class="empty">加载中...</view>
    <template v-else>
      <!-- 基本信息 -->
      <view class="card">
        <view class="section-title">基本信息</view>
        <view class="form-row">
          <text class="form-label">入库日期</text>
          <picker mode="date" :value="form.billDate" @change="onPickDate" class="form-pick">
            <view class="pick-val">{{ form.billDate || '选择日期' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">供应商</text>
          <picker :range="supplierNames" @change="onPickSupplier" class="form-pick">
            <view class="pick-val" :class="{ active: form.supplierId }">{{ pickedSupplier ? pickedSupplier.supplierName : '请选择供应商' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">入库仓库</text>
          <picker :range="whNames" @change="onPickWh" class="form-pick">
            <view class="pick-val" :class="{ active: form.warehouseId }">{{ whName || '请选择仓库' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">备注</text>
          <input class="form-input" v-model="form.remark" placeholder="选填" />
        </view>
      </view>

      <!-- 入库明细 -->
      <view class="card">
        <view class="section-title">入库明细 ({{ details.length }})</view>
        <!-- 商品搜索 (编码/品名/条码模糊) + 扫码 -->
        <view class="search-row">
          <input class="search-input" v-model="kw" placeholder="输入编码/品名搜索商品" @confirm="onSearch" />
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
            <view class="si-tags">
              <!-- 食品级商品在本流程不能选 (须走 XK 受控流程) -->
              <text v-if="p.isFoodGrade === 1" class="tag-food">食品级</text>
              <text v-else class="tag-nonfood">非食品</text>
              <text v-if="p.spec" class="si-spec">{{ p.spec }}</text>
            </view>
          </view>
        </view>

        <!-- 已选明细 -->
        <view v-if="!details.length" class="list-state empty-mini">暂无明细, 请搜索或扫码添加商品</view>
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
              <text class="di-label">批次</text>
              <input class="di-input" v-model="d.batchNo" placeholder="选填" />
            </view>
          </view>
          <view class="dr-foot">
            <text class="muted">金额 ¥ {{ lineAmount(d) }}</text>
            <text v-if="d.unitName" class="muted">单位 {{ d.unitName }}</text>
          </view>
        </view>

        <view v-if="details.length" class="total-line">
          <text>合计数量 {{ totalQty }}</text>
          <text class="total-amount">¥ {{ totalAmount }}</text>
        </view>

        <!-- 兜底提示: 明细含食品级 → 拒绝提交 (与后端 anyFood 同口径) -->
        <view v-if="hasFoodLine" class="nf-block-tip">
          ⚠ 本单含食品级商品, 不能走免检直通 — 请改用「原料入库 · XK受控」流程建单
        </view>
      </view>

      <!-- 供应商历史采购 (点击行带入明细) -->
      <view class="card" v-if="form.supplierId">
        <view class="section-title">该供应商历史采购</view>
        <view class="muted small" style="margin-bottom:8px">最近 50 条入库记录, 点击一行把该明细带入上方</view>
        <view v-if="historyLoading" class="list-state">加载中...</view>
        <view v-else-if="!history.length" class="list-state empty-mini">该供应商暂无历史采购记录</view>
        <view v-for="(h, i) in history" :key="i" class="history-row" @click="onHistoryPick(h)">
          <view class="hr-main">
            <text class="hr-name">{{ h.productName || '—' }}</text>
            <text v-if="h.productCode" class="hr-code">{{ h.productCode }}</text>
          </view>
          <text class="hr-qty">{{ h.qty }} {{ h.unitName || '' }}</text>
          <text v-if="h.price != null" class="hr-price">¥ {{ h.price }}</text>
        </view>
      </view>

      <view class="foot">
        <button class="btn btn-save" :disabled="submitting" @click="onSaveDraft">保存草稿</button>
      </view>
      <view class="foot-tip">保存后在「采购入库单」列表点「审核」完成入库</view>
    </template>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const loading = ref(true)
const submitting = ref(false)
const searching = ref(false)
const historyLoading = ref(false)

const suppliers = ref([])
const warehouses = ref([])
const kw = ref('')
const searchResults = ref([])
const details = ref([])
const history = ref([])

const form = ref({
  billDate: new Date().toISOString().slice(0, 10),
  supplierId: null,
  warehouseId: null,
  remark: ''
})

const supplierNames = computed(() => suppliers.value.map((s) => s.supplierName || ('供应商#' + s.id)))
const pickedSupplier = computed(() => suppliers.value.find((s) => String(s.id) === String(form.value.supplierId)))
const whNames = computed(() => warehouses.value.map((w) => w.warehouseName || ('仓#' + w.id)))
const whName = computed(() => {
  const w = warehouses.value.find((x) => String(x.id) === String(form.value.warehouseId))
  return w ? (w.warehouseName || ('仓#' + w.id)) : ''
})
const totalQty = computed(() => details.value.reduce((s, d) => s + (Number(d.qty) || 0), 0).toLocaleString())
const totalAmount = computed(() => {
  const v = details.value.reduce((s, d) => s + (Number(d.qty) || 0) * (Number(d.price) || 0), 0)
  return v.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
})
// v1.1.125: 明细行是否含食品级 (与后端 PurReceiptService.isFoodGradeLine 同口径).
//   商品级 base_product.is_food_grade=1 → 必须走 XK 受控流程, 本页不允许提交.
const hasFoodLine = computed(() => details.value.some((d) => d.isFoodGrade === 1))

function lineAmount(d) {
  return ((Number(d.qty) || 0) * (Number(d.price) || 0)).toFixed(2)
}

onLoad(async () => {
  // v1.1.80+: 模块停用守卫
  if (!guardPage('purchase')) return
  await loadAll()
})

async function loadAll() {
  try {
    const [s, w] = await Promise.all([
      api.supplierList(),
      api.warehouseList()
    ])
    suppliers.value = (s && (s.data || s)) || []
    warehouses.value = (w && (w.data || w)) || []
    // 默认第一个仓库
    if (warehouses.value.length) form.value.warehouseId = String(warehouses.value[0].id)
  } catch (e) {
    // v1.1.61 R13: 拦截器已弹后端 msg, catch 不重复弹
    console.warn('[purchase-receipt-add.loadAll] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
}

function onPickDate(e) { form.value.billDate = e.detail.value }
function onPickWh(e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (w) form.value.warehouseId = String(w.id)
}
async function onPickSupplier(e) {
  const s = suppliers.value[Number(e.detail.value)]
  if (!s) return
  form.value.supplierId = String(s.id)
  await loadHistory()
}

async function onSearch() {
  const k = kw.value.trim()
  if (!k) { searchResults.value = []; return }
  searching.value = true
  try {
    const r = await api.productAppSearch(k)
    // ⚠ R23: api/index.js 拦截器已 resolve(d.data), r 就是 PageResult 本身,
    //   不是数组 — 必须读 r.records (读 r.data.records 会恒空).
    const recs = (r && r.records) || (r && r.data && r.data.records) || []
    searchResults.value = recs.slice(0, 30)
  } catch (e) {
    console.warn('[purchase-receipt-add.search] 失败:', e)
    if (e && e.code === 401) return
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

// 扫码加商品 (扫商品条码/编码)
function onScan() {
  doScan({
    onResult: (code) => {
      const v = String(code || '').trim()
      if (!v) return
      kw.value = v
      onSearch()
    },
    onCancel: () => {}
  })
}

async function onPickProduct(p) {
  if (p.isFoodGrade === 1) {
    uni.showModal({
      title: '食品级商品',
      content: '「' + (p.productName || p.productCode) + '」是食品级原料, 须走「原料入库 · XK受控」流程 (IQC 来料检验 + 合格供应商名录), 不能在本页入库。',
      showCancel: false
    })
    searchResults.value = []
    kw.value = ''
    return
  }
  // 同商品同批次 → 数量累加 (与 PC 端 onNfHistoryRowClick 同口径)
  const batch = ''
  const exist = details.value.find((d) => String(d.productId) === String(p.id) && (d.batchNo || '') === batch)
  if (exist) {
    exist.qty = String((Number(exist.qty) || 0) + 1)
  } else {
    const line = {
      productId: String(p.id),
      productCode: p.productCode || '',
      productName: p.productName || '',
      spec: p.spec || '',
      unitId: p.mainUnitId != null ? String(p.mainUnitId) : null,
      unitName: '',
      isFoodGrade: 0,
      qty: '1',
      price: p.purchasePrice != null ? String(p.purchasePrice) : '0',
      batchNo: ''
    }
    details.value.push(line)
    // ⚠ app-search 返回的 base_product **没有单位名字段** (只有 mainUnitId),
    //   base_product 实体也没有 unitName 列 —— 单位名只在 base_product_unit 表里.
    //   所以必须拉 productDetail 拿 units[] 才能带出真实单位名 (如「只」/「卷」).
    //   别用兜底文案顶替 (R: 前端兜底文案不能当显示值).
    await fillUnit(line)
    await fillPrice(line)
  }
  searchResults.value = []
  kw.value = ''
}

// 带出该商品的主单位 (真实单位名) — app-search 无此字段, 走 productDetail
async function fillUnit(line) {
  if (!line.productId) return
  try {
    const r = await api.productDetail(line.productId)
    // ⚠ R23: 拦截器已 resolve(d.data), r 就是 {product,units,stockSummary} 本身,
    //   再读 r.data 恒 undefined → units 恒空.
    const pd = (r && (r.data || r)) || {}
    const list = (pd.units || []).filter((u) => u.unitName && String(u.unitName).trim())
    if (!list.length) return
    // 主单位排最前 (后端 isMain 标注); 注意 unit_id 可能为 0 (历史脏数据),
    //   所以排序 key 用行主键 id, 不用 unitId —— 用 unitId 去重会误吞并存单位.
    const main = list.find((u) => u.isMain === 1 || u.is_main === 1) || list[0]
    line.unitId = main.unitId != null ? String(main.unitId) : line.unitId
    line.unitName = main.unitName
  } catch (e) {
    console.warn('[purchase-receipt-add.fillUnit] 失败:', e)
    if (e && e.code === 401) return
  }
}

function removeDetail(i) { details.value.splice(i, 1) }

// 带出该供应商对此商品的上次入库单价 (没有则用商品档案采购价)
async function fillPrice(line) {
  if (!form.value.supplierId || !line.productId) return
  try {
    const r = await api.purchaseReceiptLastPrice(form.value.supplierId, line.productId)
    const v = (r && (r.data || r))
    if (v != null && Number(v) > 0) { line.price = String(v); return }
  } catch (e) {
    console.warn('[purchase-receipt-add.fillPrice] 失败:', e)
    if (e && e.code === 401) return
  }
}

async function loadHistory() {
  if (!form.value.supplierId) { history.value = []; return }
  historyLoading.value = true
  try {
    const r = await api.purchaseReceiptSupplierHistory(form.value.supplierId)
    const list = (r && (r.data || r)) || []
    history.value = Array.isArray(list) ? list : []
  } catch (e) {
    console.warn('[purchase-receipt-add.loadHistory] 失败:', e)
    if (e && e.code === 401) return
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

// 点历史采购行 → 带入明细 (同 productId+batchNo 累加)
async function onHistoryPick(h) {
  const exist = details.value.find((d) => String(d.productId) === String(h.productId) && (d.batchNo || '') === (h.batchNo || ''))
  if (exist) {
    exist.qty = String((Number(exist.qty) || 0) + (Number(h.qty) || 0))
    uni.showToast({ title: '已合并 (累计 ' + exist.qty + ')', icon: 'none' })
    return
  }
  const line = {
    productId: String(h.productId),
    productCode: h.productCode || '',
    productName: h.productName || '',
    spec: h.spec || '',
    unitId: h.unitId != null ? String(h.unitId) : null,
    unitName: h.unitName || '',
    // ⚠ 历史明细 (pur_receipt_detail) **不带食品级标记** —— is_food_grade 是商品级字段.
    //   这里必须查商品档案判定, 否则食品级行会被当成非食品行放行 (前端门禁失效).
    //   下面异步补, 补不到就保守按食品级处理 (宁可拦住, 不可漏放).
    isFoodGrade: 1,
    qty: String(h.qty != null ? h.qty : 0),
    price: h.price != null ? String(h.price) : '0',
    batchNo: h.batchNo || ''
  }
  details.value.push(line)
  await resolveFoodGrade(line)
  if (line.isFoodGrade === 1) {
    uni.showModal({
      title: '食品级商品',
      content: '「' + (line.productName || line.productCode) + '」是食品级原料, 不能走免检直通 — 请改用「原料入库 · XK受控」流程建单。',
      showCancel: false
    })
  } else {
    uni.showToast({ title: '已带入明细', icon: 'success' })
  }
}

// 查商品档案判定食品级 (历史明细没这字段)
async function resolveFoodGrade(line) {
  try {
    const r = await api.productDetail(line.productId)
    const pd = (r && (r.data || r)) || {}
    const p = pd.product || {}
    // 判定只认 isFoodGrade === 1 (与后端 isFoodGradeLine 同口径)
    line.isFoodGrade = p.isFoodGrade === 1 ? 1 : 0
  } catch (e) {
    console.warn('[purchase-receipt-add.resolveFoodGrade] 失败:', e)
    if (e && e.code === 401) return
    // 查不到 → 保持 line.isFoodGrade = 1 (保守拦截)
  }
}

async function onSaveDraft() {
  if (!form.value.supplierId) { uni.showToast({ title: '请选择供应商', icon: 'none' }); return }
  if (!form.value.warehouseId) { uni.showToast({ title: '请选择仓库', icon: 'none' }); return }
  if (!details.value.length) { uni.showToast({ title: '请添加入库明细', icon: 'none' }); return }
  // 明细行必须选商品且数量 > 0
  for (let i = 0; i < details.value.length; i++) {
    const d = details.value[i]
    if (!d.productId) { uni.showToast({ title: '第 ' + (i + 1) + ' 行未选商品', icon: 'none' }); return }
    if (!(Number(d.qty) > 0)) { uni.showToast({ title: '第 ' + (i + 1) + ' 行数量必须大于 0', icon: 'none' }); return }
    if (!(Number(d.price) >= 0)) { uni.showToast({ title: '第 ' + (i + 1) + ' 行单价不能为负', icon: 'none' }); return }
  }
  // 含食品级行 → 拒绝提交 (前端按明细行判, 后端 add() 也按行判, 双保险)
  if (hasFoodLine.value) {
    uni.showModal({
      title: '含食品级商品',
      content: '本单含食品级原料, 不能走免检直通。请改用「原料入库 · XK受控」流程建单。',
      showCancel: false
    })
    return
  }
  submitting.value = true
  if (uni.showLoading) uni.showLoading({ title: '保存中...', mask: true })
  try {
    const payload = {
      billDate: form.value.billDate || undefined,
      supplierId: form.value.supplierId,
      warehouseId: form.value.warehouseId,
      remark: form.value.remark || 'App 非食品采购 · 免检直通',
      details: details.value.map((d) => ({
        productId: d.productId,
        productCode: d.productCode || undefined,
        productName: d.productName || undefined,
        spec: d.spec || undefined,
        unitId: d.unitId || undefined,
        unitName: d.unitName || undefined,
        qty: Number(d.qty),
        price: Number(d.price),
        batchNo: d.batchNo || undefined,
        locationName: d.locationName || undefined
      }))
    }
    await api.purchaseReceiptAdd(payload)
    if (uni.hideLoading) uni.hideLoading()
    uni.showToast({ title: '草稿已保存', icon: 'success' })
    // 回列表页审核 (职责分离: 现场只建草稿, 审核在列表页做)
    setTimeout(() => uni.navigateBack(), 800)
  } catch (e) {
    if (uni.hideLoading) uni.hideLoading()
    // v1.1.61 R13: 拦截器已弹后端 msg, 这里只兜底非后端错误
    if (e && e.code === 401) { submitting.value = false; return }
    if (!(e && e.msg)) uni.showToast({ title: '保存失败', icon: 'none' })
  } finally { submitting.value = false }
}
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 96px; }
.xk-strip { background: #1e6091; color: #fff; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.strip-bread { font-size: 11px; opacity: .8; }
.strip-title { font-size: 16px; font-weight: 600; margin-top: 2px; }
.nf-strip { background: #67c23a; }
.nf-note { display: flex; gap: 8px; align-items: flex-start; background: #f0f9eb; border: 1px solid #e1f3d8; border-radius: 8px; padding: 10px; margin-bottom: 10px; }
.nf-note-ico { color: #67c23a; font-weight: 700; font-size: 14px; }
.nf-note-txt { flex: 1; font-size: 12px; color: #606266; line-height: 1.6; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.muted { color: #909399; font-size: 12px; }
.muted.small { font-size: 11px; }
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
.si-name { font-size: 14px; font-weight: 500; color: #303133; }
.si-code { font-size: 11px; color: #909399; }
.si-tags { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
.si-spec { font-size: 12px; color: #606266; }
.tag-food { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: #fdf6ec; color: #e6a23c; border: 1px solid #f5dab1; }
.tag-nonfood { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: #f0f9eb; color: #67c23a; border: 1px solid #c2e7b0; }
.detail-row { border: 1px solid #f0f0f0; border-radius: 8px; padding: 10px; margin-top: 8px; }
.dr-head { display: flex; justify-content: space-between; align-items: center; }
.dr-name { font-size: 14px; font-weight: 600; color: #303133; }
.dr-del { font-size: 12px; color: #f56c6c; padding: 2px 6px; }
.dr-spec { font-size: 12px; color: #909399; margin-top: 2px; }
.dr-inputs { display: flex; gap: 8px; margin-top: 8px; }
.di-item { flex: 1; }
.di-label { font-size: 11px; color: #999; display: block; }
.di-input { width: 100%; height: 32px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 13px; box-sizing: border-box; margin-top: 2px; }
.dr-foot { display: flex; justify-content: space-between; margin-top: 6px; font-size: 12px; }
.total-line { display: flex; justify-content: space-between; margin-top: 10px; padding-top: 10px; border-top: 1px solid #f0f0f0; font-size: 13px; color: #606266; }
.total-amount { color: #1e6091; font-weight: bold; font-size: 15px; }
.nf-block-tip { margin-top: 10px; padding: 8px; border-radius: 6px; background: #fdf6ec; border: 1px solid #f5dab1; color: #e6a23c; font-size: 12px; line-height: 1.6; }
.history-row { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
.history-row:active { background: #f5f7fa; }
.hr-main { flex: 1; min-width: 0; }
.hr-name { font-size: 13px; color: #303133; }
.hr-code { font-size: 11px; color: #909399; margin-left: 6px; }
.hr-qty { font-size: 12px; color: #606266; }
.hr-price { font-size: 12px; color: #1e6091; }
.list-state { text-align: center; color: #999; padding: 16px; font-size: 13px; }
.empty-mini { padding: 20px; }
.empty { text-align: center; color: #999; padding: 60px; font-size: 14px; }
.foot { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; box-shadow: 0 -2px 8px rgba(0,0,0,.06); }
.btn { width: 100%; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 15px; }
.btn-save { background: #67c23a; color: #fff; }
.btn[disabled] { opacity: .5; }
.foot-tip { position: fixed; bottom: 62px; left: 0; right: 0; text-align: center; font-size: 11px; color: #909399; background: #fff; padding-bottom: 4px; }
</style>
