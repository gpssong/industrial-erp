<template>
  <view class="container">
    <!-- Loading 遮罩 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-mask">
        <text class="loading-text">加载中...</text>
      </view>
    </view>
    <view class="card">
      <text class="title">📤 扫码出库 (销售)</text>
      <text class="muted">对应 PC 端销售出库: 选客户 → 扫商品加入明细 → 提交生成草稿销售出库单, 待 PC 端审核</text>
    </view>

    <!-- 客户选择 + 仓库选择 -->
    <view class="card">
      <view class="form-item">
        <text class="label">客户 (必选) *</text>
        <view class="row" style="gap:6px">
          <picker mode="selector" :range="customers" range-key="customerName" :value="customerIdx" @change="onCustomerPick" style="flex:1">
            <view class="input picker-trigger" :class="{ placeholder: !form.customerId }">{{ form.customerName || '点击选择客户' }}</view>
          </picker>
        </view>
      </view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">仓库 (必选) *</text>
        <view class="row" style="gap:6px">
          <picker mode="selector" :range="warehouses" range-key="warehouseName" :value="warehouseIdx" @change="onWarehousePick" style="flex:1">
            <view class="input picker-trigger" :class="{ placeholder: !form.warehouseId }">{{ form.warehouseName || '点击选择仓库' }}</view>
          </picker>
        </view>
      </view>
    </view>

    <!-- 商品扫码 -->
    <view v-if="form.customerId && form.warehouseId" class="card">
      <view class="form-item">
        <text class="label">商品编码/条码</text>
        <view class="row" style="gap:6px">
          <input class="input" v-model="code" placeholder="输入商品编码或条码" @confirm="onSearch" />
          <button class="btn-sm" @click="onSearch">搜索</button>
        </view>
      </view>
      <view class="row" style="gap:6px;margin-top:8px">
        <button class="btn" @click="onScan" style="flex:1">📷 扫一扫</button>
      </view>
    </view>

    <!-- 命中商品 + 加入明细 -->
    <view v-if="product" class="card">
      <view class="row"><text class="label">商品:</text><text>{{ product.productName }}</text></view>
      <view class="row" style="margin-top:4px"><text class="label">编码:</text><text class="muted">{{ product.productCode }}</text></view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">价格 (可编辑) ¥</text>
        <input class="input" type="digit" v-model.number="price" />
      </view>
      <view class="form-item">
        <text class="label">数量</text>
        <input class="input" type="number" v-model.number="qty" @confirm="onAdd" />
      </view>
      <view class="form-item">
        <text class="label">备注</text>
        <input class="input" v-model="remark" />
      </view>
      <view class="row" style="margin-top:6px">
        <button class="btn" @click="onAdd" style="width:100%">添加</button>
      </view>
    </view>

    <!-- 已加入明细列表 -->
    <view v-if="form.customerId && form.warehouseId" class="card" v-for="(item, i) in list" :key="i">
      <view class="row">
        <view style="flex:1">
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }}</text>
          <text v-if="item.batchNo" class="muted" style="display:block">批次: {{ item.batchNo }}</text>
        </view>
        <text style="color:var(--primary)">× {{ item.qty }}</text>
      </view>
      <view class="row" style="margin-top:6px">
        <text class="muted" @click="list.splice(i, 1)" style="cursor:pointer">🗑 删除</text>
      </view>
    </view>
    <view v-if="form.customerId && form.warehouseId && !list.length" class="empty">已选客户+仓库, 请扫码或搜索商品加入明细</view>
    <view v-if="!form.customerId || !form.warehouseId" class="empty">请先选择客户和仓库</view>

    <!-- 客户历史销售 (v1.1.7+ 新增: 与 PC 端 Delivery.vue 一致) -->
    <view v-if="form.customerId && historyList.length" class="card">
      <text class="title">📜 该客户历史销售</text>
      <text class="muted" style="display:block;margin-bottom:6px">{{ historyList.length }} 条, 单击行加入明细</text>
      <view v-for="(h, j) in historyList" :key="j" class="history-row" @click="onHistoryPick(h)">
        <view style="flex:1">
          <text style="font-weight:bold">{{ h.productName }}</text>
          <text class="muted" style="display:block">{{ h.productCode }} | {{ h.unitName }}</text>
          <text class="muted" style="display:block;font-size:10px">{{ h.billDate }} {{ h.billNo }} (上次出库)</text>
        </view>
        <view style="text-align:right">
          <text class="muted" style="display:block">× {{ Number(h.qty || 0).toString() }}</text>
          <text style="color:var(--primary)">¥ {{ Number(h.price || 0).toString() }}</text>
        </view>
      </view>
    </view>

    <!-- 操作按钮 -->
    <view class="btn btn-block" @click="onClear" v-if="list.length && !submitted" style="margin-top:10px;background:#95a5a6">清空列表</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length && !submitted" style="margin-top:10px;background:var(--success)">提交出库单 ({{ list.length }})</view>
    <view v-if="submitted" class="card" style="background:#d4edda;color:#155724">✓ 已提交, 单号 {{ submittedBillNo }}, 待 PC 端审核入库扣减</view>
  </view>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, stopScan } from '../../utils/scan.js'

const code = ref('')
const product = ref(null)
const price = ref(0)
const qty = ref(1)
const remark = ref('')
const list = ref([])
const customers = ref([])
const customerIdx = ref(-1)
const warehouses = ref([])
const warehouseIdx = ref(-1)
const historyList = ref([])
const historyLoading = ref(false)
const submitted = ref(false)
const submittedBillNo = ref('')
const loading = ref(false)

// 表单 (主要存 customerId/customerName/warehouseId/warehouseName)
const form = ref({ customerId: null, customerName: '', warehouseId: null, warehouseName: '', billDate: new Date().toISOString().substring(0, 10), details: [] })

function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}

async function loadCustomers() {
  loading.value = true
  try {
    const r = await api.customerList()
    customers.value = r || []
  } catch (e) { /* ignore */ } finally { loading.value = false }
}

async function loadWarehouses() {
  loading.value = true
  try {
    const r = await api.warehouseList()
    warehouses.value = r || []
  } catch (e) { warehouses.value = [] } finally { loading.value = false }
}

function onCustomerPick(e) {
  const idx = Number(e.detail.value)
  const c = customers.value[idx]
  if (!c) return
  customerIdx.value = idx
  form.value.customerId = c.id
  form.value.customerName = c.customerName
  // 触发加载历史销售
  loadHistory()
}

function onWarehousePick(e) {
  const idx = Number(e.detail.value)
  const w = warehouses.value[idx]
  if (!w) return
  warehouseIdx.value = idx
  form.value.warehouseId = w.id
  form.value.warehouseName = w.warehouseName
}

async function loadHistory() {
  if (!form.value.customerId) { historyList.value = []; return }
  historyLoading.value = true
  try {
    const r = await api.customerHistoryProducts(form.value.customerId)
    historyList.value = r || []
  } catch (e) {
    historyList.value = []
    toast('加载历史销售失败')
  } finally { historyLoading.value = false }
}

function onHistoryPick(row) {
  const newLine = {
    productId: row.productId,
    productCode: row.productCode,
    productName: row.productName,
    spec: row.spec,
    unitId: row.unitId,
    unitName: row.unitName,
    qty: Number(row.qty) || 0,
    price: Number(row.price) || 0,
    taxRate: Number(row.taxRate) || 13,
    batchNo: row.batchNo,
    locationName: '',
    remark: row.remark
  }
  // 合并: 同 productId+batchNo 自动合并数量
  const exist = list.value.find(d => d.productId === newLine.productId && (d.batchNo || '') === (newLine.batchNo || ''))
  if (exist) {
    exist.qty = (+exist.qty || 0) + newLine.qty
    toast(`已合并 (累计 ${exist.qty})`)
    return
  }
  list.value.push(newLine)
  toast('已加入明细')
}

async function onSearch() {
  if (!code.value) return
  loading.value = true
  try {
    const r = await api.productAppSearch(code.value)
    if (r && r.records && r.records.length > 0) {
      const exact = r.records.find(p => p.productCode === code.value || p.barcode === code.value)
      const found = exact || r.records[0]
      product.value = found
      price.value = found.salesPrice || 0
    } else {
      product.value = null
      toast('商品未找到: ' + code.value)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  } finally { loading.value = false }
}

function onScan() {
  doScan({
    onResult: (text) => { code.value = text; onSearch() },
    onCancel: () => {},
    onError: (err) => toast('扫码失败: ' + (err.message || err))
  })
}

function onAdd() {
  if (!product.value) { toast('请先搜索商品'); return }
  if (Number(qty.value) <= 0) { toast('数量必须大于 0'); return }
  if (Number(price.value) < 0) { toast('价格不能为负'); return }
  list.value.push({
    productId: product.value.id,
    productCode: product.value.productCode,
    productName: product.value.productName,
    spec: product.value.spec,
    unitId: product.value.mainUnitId,
    unitName: product.value.mainUnitName || '',
    qty: Number(qty.value),
    price: Number(price.value || 0),
    batchNo: '',
    locationName: '',
    remark: remark.value
  })
  product.value = null; qty.value = 1; remark.value = ''; code.value = ''; price.value = 0
  toast('已添加')
}

function onClear() { list.value = [] }

async function onSubmit() {
  if (!form.value.customerId) { toast('请先选择客户'); return }
  if (!form.value.warehouseId) { toast('请先选择仓库'); return }
  if (!list.value.length) { toast('请先添加商品'); return }
  loading.value = true
  const payload = {
    customerId: form.value.customerId,
    warehouseId: form.value.warehouseId,
    billDate: form.value.billDate,
    details: list.value.map(d => ({
      productId: d.productId, qty: d.qty, price: d.price,
      batchNo: d.batchNo || '', remark: d.remark || ''
    }))
  }
  try {
    await api.salesDeliveryAdd(payload)
    submitted.value = true
    submittedBillNo.value = '已生成 (待 PC 审核)'
    list.value = []
    toast('提交成功, 等 PC 端审核')
  } catch (e) {
    toast('提交失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    loading.value = false
  }
}

onMounted(() => { loadCustomers(); loadWarehouses() })
onUnmounted(() => { stopScan() })
</script>

<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 15px; font-weight: bold; display: block; margin-bottom: 4px; }
.muted { color: #999; font-size: 11px; }
.form-item { margin: 4px 0; }
.label { display: block; font-size: 11px; color: #999; margin-bottom: 2px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.picker-trigger { line-height: 36px; }
.placeholder { color: #999; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-block { width: 100%; }
.btn-sm { background: #1e6091; color: #fff; padding: 6px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: #999; padding: 30px; }
.history-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px dashed #eee; }
.history-row:last-child { border-bottom: none; }
.loading-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 9999; display: flex; align-items: center; justify-content: center; }
.loading-mask { background: rgba(0,0,0,0.4); border-radius: 8px; padding: 20px 30px; }
.loading-text { color: #fff; font-size: 14px; }
</style>
