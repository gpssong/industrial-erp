<template>
  <div class="container">
    <div class="card">
      <div class="title">📤 扫码出库 (销售)</div>
      <div class="row" style="margin: 12px 0">
        <span>扫到的条码</span>
        <span style="font-weight:bold">{{ code || '(等待扫码)' }}</span>
      </div>
      <button class="btn btn-block" @click="onScan">📷 扫一扫</button>
      <button class="btn btn-block btn-outline" style="margin-top:8px" @click="onManualInput">✏️ 手动输入</button>
    </div>
    <!-- H5 扫码弹窗 -->
    <div v-if="showScanner" class="scanner-mask">
      <div class="scanner-box">
        <div class="scanner-header">
          <span>扫描条码/二维码</span>
          <button class="btn-close" @click="closeScanner">✕</button>
        </div>
        <div id="qr-reader" style="width:100%"></div>
        <div class="scanner-tip">将条码对准摄像头</div>
      </div>
    </div>
    <div class="card" v-if="product">
      <div style="font-size:18px;font-weight:bold">{{ product.productName }}</div>
      <div class="row" style="margin: 6px 0">
        <span class="muted">当前库存</span>
        <span style="color:#1e6091">{{ product.qty }} {{ product.unitName }}</span>
      </div>
      <div class="form-item"><label class="label">销售数量</label><input class="input" type="number" v-model="qty" /></div>
      <div class="form-item"><label class="label">销售单价</label><input class="input" type="number" step="0.01" v-model="price" /></div>
      <div class="form-item"><label class="label">客户ID</label><input class="input" type="number" v-model="customerId" /></div>
      <button class="btn btn-block" @click="onSubmit">确认出库</button>
    </div>
  </div>
</template>
<script setup>
import { ref, nextTick, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, isH5 } from '../../utils/scan.js'

const code = ref('')
const product = ref(null)
const qty = ref(1)
const price = ref(0)
const customerId = ref(1)
const showScanner = ref(false)
let html5QrCode = null

function toast(msg) { alert(msg) }

function onScan() {
  if (isH5()) {
    openH5Scanner()
    return
  }
  doScan({ onResult: (text) => { code.value = text; onSearch() }, onCancel: () => {} })
}

async function openH5Scanner() {
  showScanner.value = true
  await nextTick()
  try {
    const { Html5Qrcode } = await import('html5-qrcode')
    html5QrCode = new Html5Qrcode('qr-reader')
    await html5QrCode.start(
      { facingMode: 'environment' },
      { fps: 10, qrbox: { width: 250, height: 250 }, aspectRatio: 1.0 },
      (decodedText) => {
        code.value = decodedText
        closeScanner()
        onSearch()
      },
      () => {}
    )
  } catch (err) {
    console.error('摄像头启动失败:', err)
    closeScanner()
    const c = prompt('摄像头不可用,请输入条码:')
    if (c) { code.value = c; onSearch() }
  }
}

async function closeScanner() {
  showScanner.value = false
  if (html5QrCode) {
    try { await html5QrCode.stop() } catch (e) {}
    html5QrCode = null
  }
}

function onManualInput() {
  const c = prompt('请输入商品编码或条码:')
  if (c) { code.value = c; onSearch() }
}

async function onSearch() {
  if (!code.value) return
  try {
    const r = await api.stockPage({ pageNum: 1, pageSize: 1, productName: code.value })
    if (r && r.records && r.records[0]) {
      product.value = r.records[0]
      price.value = r.records[0].salesPrice || 0
    } else {
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
    unitName: product.value.unitName, qty: +qty.value, price: +price.value, taxRate: 13
  }
  detail.amount = detail.qty * detail.price
  detail.taxAmount = detail.amount * 0.13
  detail.amountTax = detail.amount + detail.taxAmount
  const bill = {
    billDate: new Date().toISOString().substring(0, 10),
    customerId: customerId.value, warehouseId: product.value.warehouseId || 1,
    billType: 'NORMAL', billStatus: 'DRAFT', details: [detail]
  }
  try {
    await api.salesDeliveryAdd(bill)
    toast('出库成功!')
    setTimeout(() => window.history.back(), 800)
  } catch (e) {
    toast('出库失败: ' + (e.msg || e.message || '网络错误'))
  }
}

onUnmounted(() => { closeScanner() })
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
