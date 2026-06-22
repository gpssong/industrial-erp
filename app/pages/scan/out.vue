<template>
  <view class="container">
    <view class="card">
      <text class="title">📤 扫码出库 (销售)</text>
      <view class="row" style="margin: 12px 0">
        <text>扫到的条码</text>
        <text style="font-weight:bold">{{ code || '(等待扫码)' }}</text>
      </view>
      <view class="btn btn-block" @click="onScan">📷 扫一扫</view>
    </view>
    <view class="card" v-if="product">
      <text style="font-size:18px;font-weight:bold">{{ product.productName }}</text>
      <view class="row" style="margin: 6px 0">
        <text class="muted">当前库存</text>
        <text style="color:var(--primary)">{{ product.qty }} {{ product.unitName }}</text>
      </view>
      <view class="form-item"><text class="label">销售数量</text><input class="input" type="number" v-model="qty" /></view>
      <view class="form-item"><text class="label">销售单价</text><input class="input" type="digit" v-model="price" /></view>
      <view class="form-item"><text class="label">客户ID</text><input class="input" type="number" v-model="customerId" /></view>
      <view class="btn btn-block" @click="onSubmit">确认出库</view>
    </view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
import api from '../../api/index.js'
const code = ref(''); const product = ref(null)
const qty = ref(1); const price = ref(0); const customerId = ref(1)
function onScan() {
  if (typeof uni === 'undefined' || typeof uni.scanCode !== 'function') {
    const c = prompt('请输入商品编码或名称 (H5 模拟扫码)')
    if (!c) return
    code.value = c; search()
    return
  }
  uni.scanCode({ success: (res) => { code.value = res.result; search() } })
}
async function search() {
  const r = await api.stockPage({ pageNum: 1, pageSize: 1, productName: code.value })
  if (r && r.records && r.records[0]) { product.value = r.records[0]; price.value = r.records[0].salesPrice || 0 }
}
function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}
async function onSubmit() {
  if (!product.value) return
  const detail = { productId: product.value.productId, productCode: product.value.productCode, productName: product.value.productName, spec: product.value.spec, unitName: product.value.unitName, qty: +qty.value, price: +price.value, taxRate: 13 }
  detail.amount = detail.qty * detail.price
  detail.taxAmount = detail.amount * 0.13
  detail.amountTax = detail.amount + detail.taxAmount
  const bill = { billDate: new Date().toISOString().substring(0,10), customerId: customerId.value, warehouseId: product.value.warehouseId || 1, billType: 'NORMAL', billStatus: 'DRAFT', details: [detail] }
  await api.salesDeliveryAdd(bill)
  toast('出库成功')
  setTimeout(() => { if (typeof uni !== 'undefined' && uni.navigateBack) uni.navigateBack(); else if (typeof window !== 'undefined') window.history.back() }, 800)
}
</script>
<style scoped>.form-item { margin: 8px 0; } .label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }</style>
