<template>
  <view class="container">
    <view class="card">
      <text class="title">📝 手机开单</text>
      <text class="muted">开单后请到 PC 端做「审核」, 库存才会扣减</text>
    </view>
    <view class="card">
      <view class="form-item">
        <text class="label">客户</text>
        <picker mode="selector" :value="customerIdx" @change="onCustomerChange">
          <view class="picker">{{ customers[customerIdx]?.customerName || '请选择客户' }}</view>
        </picker>
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
      <view class="row" style="margin-top:4px"><text class="label">价格:</text><text>¥{{ price.toFixed(2) }}</text></view>
      <view class="form-item" style="margin-top:8px">
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
        <view>
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }}</text>
        </view>
        <text style="color:var(--primary)">× {{ item.qty }}</text>
      </view>
      <view class="row" style="margin-top:6px">
        <text class="muted" @click="list.splice(i, 1)" style="cursor:pointer">🗑 删除</text>
      </view>
    </view>
    <view v-if="!list.length" class="empty">暂无商品，请先扫描或搜索</view>
    <view class="btn btn-block" @click="onClear" v-if="list.length && !submitted" style="margin-top:10px;background:#95a5a6">清空列表</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length && !submitted" style="margin-top:10px;background:var(--success)">确认开单 ({{ list.length }})</view>
    <view v-if="submitted" class="card" style="background:#d4edda;color:#155724">✓ 已提交销售订单</view>
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
const customers = ref([])
const customerIdx = ref(0)

function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}

async function searchAndAdd(keyword) {
  try {
    const r = await api.productAppSearch(keyword)
    if (r && r.records && r.records.length > 0) {
      const exact = r.records.find(p => p.productCode === keyword || p.barcode === keyword)
      const found = exact || r.records[0]
      product.value = found
      price.value = found.salesPrice || 0
    } else {
      product.value = null
      toast('商品未找到：' + keyword)
    }
  } catch (e) {
    toast('查询失败：' + (e.msg || e.message || '网络错误'))
  }
}

function onSearch() { searchAndAdd(code.value) }

function onScan() {
  doScan({
    onResult: (text) => { code.value = text; searchAndAdd(text) },
    onCancel: () => {},
    onError: (err) => toast('扫码失败：' + (err.message || err))
  })
}

function onAdd() {
  if (!product.value) { toast('请先搜索商品'); return }
  if (Number(qty.value) <= 0) { toast('数量必须大于 0'); return }
  list.value.push({ ...product.value, qty: Number(qty.value), remark: remark.value })
  product.value = null; qty.value = 1; remark.value = ''; code.value = ''
  toast('已添加')
}

function onCustomerChange(e) { customerIdx.value = e.detail.value }

function onClear() { list.value = [] }

function onSubmit() {
  if (confirm('确定提交？将在服务器生成待审核的销售订单')) {
    toast('已创建销售订单，待审核')
    submitted.value = true; list.value = []
  }
}

async function loadCustomers() {
  try {
    const r = await api.customerList()
    customers.value = r.data || []
    if (customers.value.length > 0) customerIdx.value = 0
  } catch (e) { console.error('加载客户失败', e) }
}

onMounted(() => { loadCustomers() })
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
.picker { height: 36px; line-height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-block { width: 100%; }
.btn-sm { background: #1e6091; color: #fff; padding: 6px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: #999; padding: 40px; }
</style>
