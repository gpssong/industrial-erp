<template>
  <div class="container">
    <div class="card">
      <div class="title">📝 手机开单</div>
      <div class="muted">开单后请到 PC 端做「审核」, 库存才会扣减</div>
    </div>
    <div class="card">
      <div class="form-item">
        <label class="label">客户</label>
        <select class="input" v-model="customerId">
          <option value="">请选择客户</option>
          <option v-for="c in customers" :key="c.id" :value="c.id">{{ c.customerName }}</option>
        </select>
      </div>
    </div>
    <div class="card">
      <div class="title">商品明细 ({{ details.length }})</div>
      <div class="row" style="margin-top:8px;gap:8px">
        <button class="btn" @click="scanAdd" style="flex:1;background:#27ae60">📷 扫码添加</button>
        <button class="btn" @click="addLine" style="flex:1;background:#1e6091">✏️ 手动输入</button>
      </div>
      <div v-for="(d, i) in details" :key="i" style="border-top:1px solid #eee;padding:8px 0">
        <div class="row">
          <span>{{ d.productName }}</span>
          <span class="muted" @click="details.splice(i, 1)" style="cursor:pointer">删除</span>
        </div>
        <div class="row" style="margin-top:4px">
          <span class="muted">{{ d.spec }} / {{ d.unitName }}</span>
          <span style="color:#1e6091">¥ {{ d.amount.toFixed(2) }}</span>
        </div>
        <div class="row" style="margin-top:4px;gap:6px">
          <div style="width:33%">
            <div class="input-label">数量</div>
            <input class="input" type="number" v-model="d.qty" @change="recalc(d)" placeholder="0" />
          </div>
          <div style="width:33%">
            <div class="input-label">单价</div>
            <input class="input" type="number" step="0.01" v-model="d.price" @change="recalc(d)" placeholder="0.00" />
          </div>
          <div style="width:33%">
            <div class="input-label">备注</div>
            <input class="input" v-model="d.remark" placeholder="选填" />
          </div>
        </div>
      </div>
    </div>
    <div class="card" v-if="details.length">
      <div class="row"><span class="muted">合计数量</span><span>{{ totalQty }}</span></div>
      <div class="row"><span class="muted">合计金额</span><span style="color:#1e6091;font-weight:bold">¥ {{ totalAmount.toFixed(2) }}</span></div>
      <button class="btn btn-block" @click="onSave" style="margin-top:10px">保存草稿</button>
    </div>
    <!-- 扫码弹窗 -->
    <div v-if="showScanner" class="scanner-mask">
      <div class="scanner-box">
        <div class="scanner-header">
          <span>扫描商品条码</span>
          <button class="btn-close" @click="closeScanner">✕</button>
        </div>
        <div id="qr-reader" style="width:100%"></div>
        <div class="scanner-tip">将条码对准摄像头</div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import api from '../../api/index.js'
import { doScan, isH5 } from '../../utils/scan.js'

const customers = ref([])
const customerId = ref('')
const details = ref([])
const showScanner = ref(false)
let html5QrCode = null

onMounted(async () => {
  try { customers.value = await api.customerList() || [] } catch (e) { customers.value = [] }
})

const totalQty = computed(() => details.value.reduce((s, d) => s + (+d.qty || 0), 0))
const totalAmount = computed(() => details.value.reduce((s, d) => s + (d.amount || 0), 0))

function toast(msg) { alert(msg) }

async function searchAndAdd(keyword) {
  try {
    const r = await api.stockPage({ pageNum: 1, pageSize: 1, keyword })
    if (r && r.records && r.records[0]) {
      addProduct(r.records[0])
      toast('已添加: ' + r.records[0].productName)
    } else {
      toast('商品未找到: ' + keyword)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  }
}

async function scanAdd() {
  if (isH5()) {
    await openH5Scanner()
    return
  }
  doScan({ onResult: async (text) => { await searchAndAdd(text) }, onCancel: () => toast('扫码取消') })
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
      async (decodedText) => {
        await closeScanner()
        await searchAndAdd(decodedText)
      },
      () => {}
    )
  } catch (err) {
    console.error('摄像头启动失败:', err)
    await closeScanner()
    const c = prompt('摄像头不可用,请输入商品编码:')
    if (c) await searchAndAdd(c)
  }
}

async function closeScanner() {
  showScanner.value = false
  if (html5QrCode) {
    try { await html5QrCode.stop() } catch (e) {}
    html5QrCode = null
  }
}

function addLine() {
  const c = prompt('请输入商品编码或名称')
  if (c) searchAndAdd(c)
}

function addProduct(p) {
  details.value.push({
    productId: p.productId, productCode: p.productCode,
    productName: p.productName, spec: p.spec, unitName: p.unitName,
    qty: 1, price: p.salesPrice || 0, taxRate: 13, remark: '', amount: 0
  })
  recalc(details.value[details.value.length - 1])
}

function recalc(d) {
  d.amount = (+d.qty || 0) * (+d.price || 0)
  d.taxAmount = d.amount * 0.13
  d.amountTax = d.amount + d.taxAmount
}

async function onSave() {
  if (!customerId.value) return toast('请选择客户')
  if (!details.value.length) return toast('请添加商品')
  const bill = {
    billDate: new Date().toISOString().substring(0, 10),
    customerId: customerId.value, warehouseId: 1,
    billType: 'NORMAL', billStatus: 'DRAFT', details: details.value
  }
  try {
    await api.salesDeliveryAdd(bill)
    toast('已保存草稿')
    setTimeout(() => { details.value = []; window.location.hash = '#/pages/dashboard/index' }, 800)
  } catch (e) {
    toast('保存失败: ' + (e.msg || e.message || '网络错误'))
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
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; background: #fff; }
.input:focus { border-color: #1e6091; outline: none; }
.input-label { font-size: 11px; color: #666; margin-bottom: 2px; }
.btn { background: #1e6091; color: #fff; padding: 10px 12px; border-radius: 6px; border: none; cursor: pointer; font-size: 14px; text-align: center; }
.btn:hover { background: #2980b9; }
.btn-block { width: 100%; }
.muted { color: #999; font-size: 12px; }
.scanner-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.8); z-index: 9999; display: flex; align-items: center; justify-content: center; }
.scanner-box { background: #fff; border-radius: 12px; padding: 16px; width: 90%; max-width: 360px; }
.scanner-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-size: 16px; font-weight: bold; }
.btn-close { background: none; border: none; font-size: 20px; cursor: pointer; color: #999; }
.scanner-tip { text-align: center; color: #666; font-size: 13px; margin-top: 10px; }
</style>
