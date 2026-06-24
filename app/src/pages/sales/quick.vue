<template>
  <view class="container">
    <view class="card">
      <text class="title">📝 手机开单</text>
      <text class="muted">开单后请到 PC 端做「审核」, 库存才会扣减</text>
    </view>
    <view class="card">
      <view class="form-item">
        <text class="label">客户</text>
        <picker mode="selector" :range="customers" range-key="customerName" @change="e=>pickCustomer(customers[e.detail.value])">
          <view class="input">{{ customerName || '请选择客户' }}</view>
        </picker>
      </view>
    </view>
    <view class="card">
      <text class="title">商品明细 ({{ details.length }})</text>
      <view class="row" style="margin-top:8px;gap:8px">
        <view class="btn" @click="scanAdd" style="flex:1;background:var(--success)">📷 扫码添加</view>
        <view class="btn" @click="addLine" style="flex:1;background:var(--primary)">✏️ 手动输入</view>
      </view>
      <view v-for="(d, i) in details" :key="i" style="border-top:1px solid #eee;padding:8px 0">
        <view class="row">
          <text>{{ d.productName }}</text>
          <text class="muted" @click="details.splice(i, 1)">删除</text>
        </view>
        <view class="row" style="margin-top:4px">
          <text class="muted">{{ d.spec }} / {{ d.unitName }}</text>
          <text style="color:var(--primary)">¥ {{ d.amount.toFixed(2) }}</text>
        </view>
        <view class="row" style="margin-top:4px;gap:6px">
          <input class="input" style="width:33%" type="number" v-model="d.qty" @change="recalc(d)" />
          <input class="input" style="width:33%" type="digit" v-model="d.price" @change="recalc(d)" />
          <input class="input" style="width:33%" v-model="d.remark" placeholder="备注" />
        </view>
      </view>
    </view>
    <view class="card" v-if="details.length">
      <view class="row"><text class="muted">合计数量</text><text>{{ totalQty }}</text></view>
      <view class="row"><text class="muted">合计金额</text><text style="color:var(--primary);font-weight:bold">¥ {{ totalAmount.toFixed(2) }}</text></view>
      <view class="btn btn-block" @click="onSave" style="margin-top:10px">保存草稿</view>
    </view>
  </view>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../../api/index.js'
const customers = ref([])
onMounted(async () => { customers.value = await api.customerList() })
const customerId = ref(null); const customerName = ref('')
const details = ref([])
const totalQty = computed(() => details.value.reduce((s, d) => s + (+d.qty || 0), 0))
const totalAmount = computed(() => details.value.reduce((s, d) => s + (d.amount || 0), 0))
function pickCustomer(c) { customerId.value = c.id; customerName.value = c.customerName }
function addLine() {
  // H5 环境: 用 prompt 输入代替 uni.showModal
  if (typeof uni === 'undefined' || typeof uni.showModal !== 'function') {
    const code = prompt('请输入商品编码或名称')
    if (!code) return
    searchAndAdd(code)
    return
  }
  uni.showModal({ title: '添加商品', editable: true, placeholderText: '请输入商品编码',
    success: async (r) => {
      if (!r.confirm || !r.content) return
      await searchAndAdd(r.content)
    }
  })
}
async function searchAndAdd(keyword) {
  const r = await api.stockPage({ pageNum: 1, pageSize: 1, productName: keyword })
  if (r.records && r.records[0]) {
    addProduct(r.records[0])
    toast('已添加: ' + r.records[0].productName)
  } else {
    toast('商品未找到: ' + keyword)
  }
}
function scanAdd() {
  // H5 环境: 扫码用 prompt 模拟
  if (typeof uni === 'undefined' || typeof uni.scanCode !== 'function') {
    addLine()
    return
  }
  uni.scanCode({
    success: async (res) => {
      await searchAndAdd(res.result)
    },
    fail: () => { toast('扫码取消') }
  })
}
function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}
function addProduct(p) {
  details.value.push({ productId: p.productId, productCode: p.productCode, productName: p.productName, spec: p.spec, unitName: p.unitName, qty: 1, price: p.salesPrice, taxRate: 13, remark: '', amount: 0 })
  recalc(details.value[details.value.length - 1])
}
function recalc(d) { d.amount = (+d.qty || 0) * (+d.price || 0); d.taxAmount = d.amount * 0.13; d.amountTax = d.amount + d.taxAmount }
async function onSave() {
  if (!customerId.value) return uni.showToast({ title: '请选择客户', icon: 'none' })
  if (!details.value.length) return uni.showToast({ title: '请添加商品', icon: 'none' })
  const bill = { billDate: new Date().toISOString().substring(0,10), customerId: customerId.value, warehouseId: 1, billType: 'NORMAL', billStatus: 'DRAFT', details: details.value }
  await api.salesDeliveryAdd(bill)
  uni.showToast({ title: '已保存草稿' })
  setTimeout(() => { details.value = []; uni.switchTab({ url: '/pages/dashboard/index' }) }, 800)
}
</script>
<style scoped>.form-item { margin: 8px 0; } .label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }</style>
