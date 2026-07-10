<template>
  <view class="container">
    <view class="card">
      <text class="title">📥 扫码入库</text>
      <text class="muted">扫商品条码或输入编码后点"添加", 完成后点"确认入库"</text>
    </view>

    <!-- 供应商选择 -->
    <view class="card">
      <view class="form-item">
        <text class="label">供应商 (必选) *</text>
        <view class="picker" @click="showSupplierModal = true">
          <text style="color:#333">{{ currentSupplierName }}</text>
        </view>
      </view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">仓库 (必选) *</text>
        <view class="picker" @click="showWarehouseModal = true">
          <text :class="{ placeholder: !form.warehouseId }" style="color:#333">{{ currentWarehouseName }}</text>
        </view>
      </view>
    </view>

    <!-- 供应商选择弹窗 -->
    <view v-if="showSupplierModal" class="mask" @click="showSupplierModal = false">
      <view class="modal" @click.stop>
        <text class="modal-title">选择供应商</text>
        <view v-if="!suppliers.length" class="empty">正在加载供应商列表...</view>
        <view v-for="(s, i) in suppliers" :key="s.id" class="modal-item" @click="onSupplierSelect(i)">
          <text>{{ s.supplierName || '(未命名)' }}</text>
        </view>
        <view class="modal-close" @click="showSupplierModal = false">取消</view>
      </view>
    </view>

    <!-- 仓库选择弹窗 -->
    <view v-if="showWarehouseModal" class="mask" @click="showWarehouseModal = false">
      <view class="modal" @click.stop>
        <text class="modal-title">选择仓库</text>
        <view v-if="!warehouses.length" class="empty">正在加载仓库列表...</view>
        <view v-for="(w, i) in warehouses" :key="w.id" class="modal-item" @click="onWarehouseSelect(i)">
          <text>{{ w.warehouseName || '(未命名)' }}</text>
        </view>
        <view class="modal-close" @click="showWarehouseModal = false">取消</view>
      </view>
    </view>

    <view class="card">
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

    <view class="card" v-for="(item, i) in list" :key="i">
      <view class="row">
        <view style="flex:1">
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }}</text>
        </view>
        <view style="text-align:right">
          <text style="color:var(--primary)">× {{ item.qty }}</text>
          <text class="muted" style="display:block">¥ {{ Number(item.price || 0).toString() }}</text>
        </view>
      </view>
      <view class="row" style="margin-top:6px">
        <text class="muted" @click="list.splice(i, 1)" style="cursor:pointer">🗑 删除</text>
      </view>
    </view>

    <view v-if="!list.length" class="empty">暂无商品，请先扫描或搜索</view>
    <view class="btn btn-block" @click="onClear" v-if="list.length && !submitted" style="margin-top:10px;background:#95a5a6">清空列表</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length && !submitted" style="margin-top:10px;background:var(--success)">确认入库 ({{ list.length }})</view>
    <view v-if="submitted" class="card" style="background:#d4edda;color:#155724">✓ 已提交入库单</view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, stopScan } from '../../utils/scan.js'

const code = ref('')
const product = ref(null)
const price = ref(0)
const qty = ref(1)
const remark = ref('')
const list = ref([])
const submitted = ref(false)
const suppliers = ref([])
const supplierIdx = ref(-1)
const showSupplierModal = ref(false)
const warehouses = ref([])
const warehouseIdx = ref(-1)
const showWarehouseModal = ref(false)
const form = ref({ warehouseId: null })

const currentSupplierName = computed(() => {
  if (supplierIdx.value >= 0 && suppliers.value[supplierIdx.value]) {
    return suppliers.value[supplierIdx.value].supplierName || '(未命名)'
  }
  if (suppliers.value.length === 0) return '正在加载...'
  return '点击选择供应商'
})

const currentWarehouseName = computed(() => {
  if (warehouseIdx.value >= 0 && warehouses.value[warehouseIdx.value]) {
    return warehouses.value[warehouseIdx.value].warehouseName || '(未命名)'
  }
  if (warehouses.value.length === 0) return '正在加载...'
  return '点击选择仓库'
})

function onSupplierSelect(idx) {
  supplierIdx.value = idx
  showSupplierModal.value = false
}

function onWarehouseSelect(idx) {
  warehouseIdx.value = idx
  form.value.warehouseId = warehouses.value[idx].id
  showWarehouseModal.value = false
}

function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}

async function onSearch() {
  if (!code.value) return
  try {
    const r = await api.productAppSearch(code.value)
    if (r && r.records && r.records.length > 0) {
      const exact = r.records.find(p => p.productCode === code.value || p.barcode === code.value)
      const found = exact || r.records[0]
      product.value = found
      price.value = found.purchasePrice || 0
    } else {
      product.value = null
      toast('商品未找到：' + code.value)
    }
  } catch (e) {
    toast('查询失败：' + (e.msg || e.message || '网络错误'))
  }
}

function onScan() {
  doScan({
    onResult: (text) => { code.value = text; onSearch() },
    onCancel: () => {},
    onError: (err) => toast('扫码失败：' + (err.message || err))
  })
}

function onAdd() {
  if (supplierIdx.value < 0) { toast('请先选择供应商'); return }
  if (!form.value.warehouseId) { toast('请先选择仓库'); return }
  if (!product.value) { toast('请先搜索商品'); return }
  if (Number(qty.value) <= 0) { toast('数量必须大于 0'); return }
  if (Number(price.value) < 0) { toast('价格不能为负'); return }
  // 合并: 同 productId 自动累加数量
  const newItem = { ...product.value, qty: Number(qty.value), price: Number(price.value || 0), remark: remark.value }
  const exist = list.value.find(d => (d.id || d.productId) === newItem.id)
  if (exist) {
    exist.qty = Number(exist.qty || 0) + newItem.qty
    toast(`已合并 (累计 ${exist.qty})`)
  } else {
    list.value.push(newItem)
    toast('已添加')
  }
  product.value = null; qty.value = 1; remark.value = ''; code.value = ''; price.value = 0
}

function onClear() { list.value = [] }

async function onSubmit() {
  if (list.value.length === 0) { toast('请先添加商品'); return }
  if (supplierIdx.value < 0) { toast('请先选择供应商'); return }
  if (!form.value.warehouseId) { toast('请先选择仓库'); return }
  try {
    const selectedSupplier = suppliers.value[supplierIdx.value]
    const details = list.value.map(item => ({
      productId: item.id || item.productId,
      productCode: item.productCode,
      productName: item.productName,
      spec: item.spec || '',
      unitId: item.unitId,
      unitName: item.unitName || '个',
      qty: Number(item.qty),
      price: Number(item.price || 0),
      taxRate: selectedSupplier.taxRate || 13.00,
      remark: item.remark || ''
    }))
    const r = await api.purchaseReceiptAdd({
      billType: 'PURCHASE',
      supplierId: selectedSupplier.id,
      warehouseId: form.value.warehouseId,
      details: details,
      remark: 'App 扫码入库'
    })
    console.log('[in.vue] purchaseReceiptAdd result:', JSON.stringify(r))
    const billNo = (r && r.billNo) || (r && r.data && r.data.billNo) || (r && r.data && typeof r.data === 'string' ? r.data : '') || ''
    toast('入库单已提交：' + (billNo || '成功'))
    submitted.value = true
    list.value = []
  } catch (e) {
    console.error('提交入库失败:', e)
    toast('提交失败：' + (e.msg || (e && e.message) || '网络错误'))
  }
}

async function loadSuppliers() {
  console.log('[in.vue] loadSuppliers started')
  try {
    // 直接用 fetch 而不是 api.supplierList, 避免 uni.request 在 Capacitor 下的兼容问题
    const base = (typeof localStorage !== 'undefined' && localStorage.getItem('erp_api_base')) || 'http://home.93gushi.com:8088/api'
    const token = (typeof localStorage !== 'undefined' && localStorage.getItem('erp_token')) || ''
    const url = base + '/base/supplier/list'
    console.log('[in.vue] fetching:', url, 'token:', token.substring(0, 20))

    const r = await fetch(url, {
      method: 'GET',
      headers: { 'Authorization': token }
    })
    console.log('[in.vue] response status:', r.status)
    const data = await r.json()
    console.log('[in.vue] response data:', JSON.stringify(data).substring(0, 200))

    if (data && data.code === 200 && Array.isArray(data.data)) {
      suppliers.value = data.data
      if (suppliers.value.length > 0) {
        supplierIdx.value = 0
        console.log('[in.vue] loaded', suppliers.value.length, 'suppliers, first:', suppliers.value[0].supplierName)
      } else {
        console.warn('[in.vue] supplier list is empty')
      }
    } else {
      console.error('[in.vue] invalid response:', data)
    }
  } catch (e) {
    console.error('[in.vue] loadSuppliers error:', e.message)
  }
}

async function loadWarehouses() {
  try {
    const list = await api.warehouseList()
    warehouses.value = list || []
    console.log('[in.vue] loaded warehouses:', warehouses.value.length)
  } catch (e) {
    console.error('[in.vue] loadWarehouses error:', e)
    warehouses.value = []
  }
}

onMounted(() => { loadSuppliers(); loadWarehouses() })
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
.picker { height: 36px; line-height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; background: #fff; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-block { width: 100%; }
.btn-sm { background: #1e6091; color: #fff; padding: 6px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: #999; padding: 40px; }

.mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 999; display: flex; align-items: center; justify-content: center; }
.modal { background: #fff; border-radius: 12px; width: 80%; max-width: 360px; max-height: 70vh; overflow-y: auto; padding: 16px; }
.modal-title { font-size: 16px; font-weight: bold; margin-bottom: 12px; display: block; }
.modal-item { padding: 12px 8px; border-bottom: 1px solid #eee; font-size: 14px; }
.modal-item:active { background: #f5f5f5; }
.modal-close { padding: 12px; text-align: center; color: #c0392b; margin-top: 8px; border-top: 1px solid #eee; }
</style>
