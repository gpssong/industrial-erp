<template>
  <div class="container">
    <div class="card">
      <div class="title">📥 扫码入库</div>
      <div class="row" style="margin: 12px 0">
        <span>扫到的条码</span>
        <span style="font-weight:bold">{{ code || '(等待扫码)' }}</span>
      </div>
      <button class="btn btn-block" @click="onScan">📷 扫一扫</button>
      <button class="btn btn-block btn-outline" style="margin-top:8px" @click="onManualInput">✏️ 手动输入</button>
    </div>
    <!-- 扫码弹窗已移除，使用原生扫码 -->
    <div class="card" v-if="product">
      <div style="font-size:18px;font-weight:bold">{{ product.productName }}</div>
      <div class="muted" style="margin:4px 0">{{ product.spec }}</div>
      <div class="form-item"><label class="label">数量</label><input class="input" type="number" v-model="qty" /></div>
      <div class="form-item"><label class="label">单价</label><input class="input" type="number" step="0.01" v-model="price" /></div>
      <div class="form-item"><label class="label">批次</label><input class="input" v-model="batchNo" placeholder="可选" /></div>
      <button class="btn btn-block" @click="onSubmit">确认入库</button>
    </div>
  </div>
</template>
<script setup>
import { ref } from 'vue'
import api from '../../api/index.js'
import { doScan } from '../../utils/scan.js'

const code = ref('')
const product = ref(null)
const qty = ref(1)
const price = ref(0)
const batchNo = ref('')

function toast(msg) { alert(msg) }

function onScan() {
  doScan({
    onResult: (text) => { code.value = text; onSearch() },
    onCancel: () => {},
    onError: (err) => { toast('扫码失败: ' + (err.message || err)) }
  })
}

function onManualInput() {
  const c = prompt('请输入商品编码或条码:')
  if (c) { code.value = c; onSearch() }
}

async function onSearch() {
  if (!code.value) return
  try {
    const r = await api.stockPage({ pageNum: 1, pageSize: 10, keyword: code.value })
    if (r && r.records && r.records.length > 0) {
      // 精确匹配: 优先匹配 productCode 或 barcode, 其次匹配第一个结果
      const exact = r.records.find(p => p.productCode === code.value || p.barcode === code.value)
      const found = exact || r.records[0]
      product.value = found
      price.value = found.purchasePrice || 0
      if (!exact && r.records.length > 1) {
        toast('找到 ' + r.records.length + ' 个商品, 显示第一个: ' + found.productName)
      }
    } else {
      product.value = null
      toast('商品未找到: ' + code.value)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  }
}

async function onSubmit() {
  if (!product.value) return
  const detail = {
    productId: product.value.productId, productCode: product.value.productCode,
    productName: product.value.productName, spec: product.value.spec,
    unitName: product.value.unitName, qty: +qty.value, price: +price.value,
    taxRate: 13, batchNo: batchNo.value
  }
  detail.amount = detail.qty * detail.price
  detail.taxAmount = detail.amount * 0.13
  detail.amountTax = detail.amount + detail.taxAmount
  const bill = {
    billDate: new Date().toISOString().substring(0, 10),
    supplierId: 1, warehouseId: product.value.warehouseId || 1,
    billType: 'NORMAL', billStatus: 'DRAFT', details: [detail]
  }
  try {
    await api.purchaseReceiptAdd(bill)
    toast('入库成功!')
    setTimeout(() => window.history.back(), 800)
  } catch (e) {
    toast('入库失败: ' + (e.msg || e.message || '网络错误'))
  }
}

</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 16px; font-weight: bold; color: #333; margin-bottom: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.form-item { margin: 8px 0; }
.label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn:hover { background: #2980b9; }
.btn-outline { background: transparent; color: #1e6091; border: 1px solid #1e6091; }
.muted { color: #999; font-size: 12px; }
.scanner-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.8); z-index: 9999; display: flex; align-items: center; justify-content: center; }
.scanner-box { background: #fff; border-radius: 12px; padding: 16px; width: 90%; max-width: 360px; }
.scanner-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-size: 16px; font-weight: bold; }
.btn-close { background: none; border: none; font-size: 20px; cursor: pointer; color: #999; }
.scanner-tip { text-align: center; color: #666; font-size: 13px; margin-top: 10px; }
</style>
