<template>
  <view class="container">
    <view class="card">
      <text class="title">📥 扫码入库</text>
      <view class="row" style="margin: 12px 0">
        <text>扫到的条码</text>
        <text style="font-weight:bold">{{ code || '(等待扫码)' }}</text>
      </view>
      <view class="btn btn-block" @click="onScan">📷 扫一扫</view>
    </view>
    <view class="card" v-if="product">
      <text style="font-size:18px;font-weight:bold">{{ product.productName }}</text>
      <text class="muted" style="display:block;margin:4px 0">{{ product.spec }}</text>
      <view class="form-item"><text class="label">数量</text><input class="input" type="number" v-model="qty" /></view>
      <view class="form-item"><text class="label">单价</text><input class="input" type="digit" v-model="price" /></view>
      <view class="form-item"><text class="label">批次</text><input class="input" v-model="batchNo" placeholder="可选" /></view>
      <view class="btn btn-block" @click="onSubmit">确认入库</view>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../api/index.js'
const code = ref(''); const product = ref(null)
const qty = ref(1); const price = ref(0); const batchNo = ref('')
function onScan() {
  uni.scanCode({ success: (res) => { code.value = res.result; onSearch() }, fail: () => {
    uni.showModal({ title: '手动输入条码', editable: true, success: (r) => { if (r.confirm) { code.value = r.content; onSearch() } } })
  }})
}
async function onSearch() {
  const r = await api.stockPage({ pageNum: 1, pageSize: 1, keyword: code.value })
  if (r.records && r.records[0]) { product.value = r.records[0]; price.value = r.records[0].purchasePrice }
  else uni.showToast({ title: '商品未找到', icon: 'none' })
}
async function onSubmit() {
  if (!product.value) return
  const detail = { productId: product.value.productId, productCode: product.value.productCode, productName: product.value.productName, spec: product.value.spec, unitName: product.value.unitName, qty: +qty.value, price: +price.value, taxRate: 13, batchNo: batchNo.value }
  detail.amount = detail.qty * detail.price
  detail.taxAmount = detail.amount * 0.13
  detail.amountTax = detail.amount + detail.taxAmount
  const bill = { billDate: new Date().toISOString().substring(0,10), supplierId: 1, warehouseId: product.value.warehouseId, billType: 'NORMAL', billStatus: 'DRAFT', details: [detail] }
  await api.purchaseReceiptAdd(bill)
  uni.showToast({ title: '入库成功' })
  setTimeout(() => uni.navigateBack(), 800)
}
</script>
<style scoped>.form-item { margin: 8px 0; } .label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }</style>
